package core

import model.YNode
import model.scalarText
import schema.SchemaLoader
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 文書キャッシュ層の中核（R9-02）。
 * app.OpenDocument（Compose購読のシェル）から、UI非依存のロジック——
 * 複数ドキュメントのパース・正規化テキスト・履歴（Undo/Redo）・dirty判定——を core へ抽出したもの。
 * 単体テストはこの層（`DocumentsTest`）に書く。
 */
class EditBuffer(
    val file: Path,
    var root: YNode?,
    var extraDocs: List<YNode>,   // `---` 区切りの後続ドキュメント（先頭のみ編集対象。保存時に再出力）
    var rawText: String,          // パース不能なときの元テキスト
    var parseError: String?,
) {
    data class Parsed(val root: YNode?, val extraDocs: List<YNode>, val error: String?)

    companion object {
        /** テキストを複数ドキュメントとしてパースする（失敗はエラーメッセージとして返す）。 */
        fun parse(text: String): Parsed = try {
            val docs = YamlIO.parseAll(text)
            Parsed(docs.firstOrNull() ?: YNode.Map(), docs.drop(1), null)
        } catch (e: Exception) {
            Parsed(null, emptyList(), e.message ?: e.toString())
        }

        fun load(file: Path): EditBuffer {
            val text = runCatching { Files.readString(file) }.getOrDefault("")
            val p = parse(text)
            return EditBuffer(file, p.root, p.extraDocs, text, p.error)
        }
    }

    private val history = mutableListOf(if (root != null) canonicalText() else rawText)
    private var savedSnapshot = history.first()
    var historyIndex: Int = 0
        private set
    var dirty: Boolean = false
        private set

    /** 保存・ソース表示に使う正規化テキスト。パース不能なら元テキストを返す。 */
    fun canonicalText(): String = root?.let { YamlIO.dumpAll(listOf(it) + extraDocs) } ?: rawText

    /** 編集をスナップショット履歴に記録し、dirty を更新する（最大100件）。 */
    fun recordEdit() {
        dirty = canonicalText() != savedSnapshot
        val snapshot = canonicalText()
        if (history.getOrNull(historyIndex) != snapshot) {
            while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
            history.add(snapshot)
            if (history.size > 100) history.removeAt(0) else historyIndex++
        }
    }

    fun canUndo(): Boolean = historyIndex > 0
    fun canRedo(): Boolean = historyIndex < history.lastIndex

    fun undo(): Boolean {
        if (!canUndo()) return false
        restore(historyIndex - 1)
        return true
    }

    fun redo(): Boolean {
        if (!canRedo()) return false
        restore(historyIndex + 1)
        return true
    }

    /** ソース編集のテキストをツリーへ取り込む（複数ドキュメントは先頭を編集対象にし後続を保持）。 */
    fun applySourceText(text: String) {
        val docs = YamlIO.parseAll(text)
        root = docs.firstOrNull() ?: YNode.Map()
        extraDocs = docs.drop(1)
        parseError = null
    }

    private fun restore(index: Int) {
        val text = history[index]
        val docs = runCatching { YamlIO.parseAll(text) }.getOrNull()
        root = if (docs != null) (docs.firstOrNull() ?: YNode.Map()) else null
        extraDocs = docs?.drop(1).orEmpty()
        rawText = text
        parseError = if (root == null) "履歴の復元に失敗しました" else null
        historyIndex = index
        dirty = text != savedSnapshot
    }

    /** ディスク再読込などで内容が差し替わったとき、履歴と保存基準を取り直す。 */
    fun resetHistory() {
        history.clear()
        history.add(canonicalText())
        historyIndex = 0
        savedSnapshot = canonicalText()
        dirty = false
    }

    fun markSaved() {
        savedSnapshot = canonicalText()
        dirty = false
    }
}

/** 一覧ビューの1行（ルート直下の代表スカラー）。 */
data class WorkspaceRecord(
    val file: Path,
    val id: String?,
    val title: String?,
    val status: String?,
    val owner: String?,
    val kind: String?,
)

/**
 * 一覧・一括編集のデータ収集（R9-02 で AppState から core へ抽出）。
 * loader には「未保存編集を優先するツリー取得」を注入する（UI: docCache → ディスク。§4.5 の二層構造）。
 */
object WorkspaceRecords {

    fun collect(ws: Workspace, loader: (Path) -> YNode?): List<WorkspaceRecord> = ws.files.asSequence()
        .filter { !ws.relPath(it).startsWith(SchemaLoader.SCHEMA_DIR + "/") }
        .mapNotNull { file ->
            val map = loader(file) as? YNode.Map ?: return@mapNotNull null
            WorkspaceRecord(
                file = file,
                id = scalarText(map.entries["id"]),
                title = scalarText(map.entries["title"]) ?: scalarText(map.entries["name"]),
                status = scalarText(map.entries["status"]),
                owner = scalarText(map.entries["owner"]),
                kind = ws.schemaFor(file)?.name,
            )
        }.sortedBy { ws.relPath(it.file) }.toList()

    /** 一括編集の対象にできるキーの候補: ルート直下に現れるスカラーキー（id・予約キーは除く）。 */
    fun bulkKeyCandidates(ws: Workspace, loader: (Path) -> YNode?): List<String> {
        val keys = linkedSetOf("status", "owner")
        ws.files.asSequence()
            .filter { !ws.relPath(it).startsWith("_") }
            .forEach { file ->
                (loader(file) as? YNode.Map)?.entries?.forEach { (k, v) ->
                    if (v is YNode.Scalar && k != "id" && !k.startsWith("_")) keys.add(k)
                }
            }
        return keys.toList()
    }

    /**
     * ルート直下スカラーの一括変更（R8-05）。複合値（配列・オブジェクト）のキーは壊さないよう飛ばす。
     * コメント・表記スタイルは維持する（inheritMetaFrom）。変更できた件数を返す。
     */
    fun updateRootScalar(files: Collection<Path>, key: String, value: String): Int {
        var count = 0
        files.forEach { file ->
            val docs = runCatching { YamlIO.parseAll(Files.readString(file)) }.getOrNull() ?: return@forEach
            val root = docs.firstOrNull() as? YNode.Map ?: return@forEach
            val existing = root.entries[key]
            if (existing != null && existing !is YNode.Scalar) return@forEach
            val node = YNode.Scalar(value)
            node.inheritMetaFrom(existing)
            root.entries[key] = node
            Files.writeString(file, YamlIO.dumpAll(docs))
            count++
        }
        return count
    }
}
