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
        /** 連続入力を1エントリへ合体する時間窓（ms。UX-11）。超えるとタイプの区切りとみなし境界になる。 */
        const val COALESCE_WINDOW_MS = 1000L

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

    // 履歴の合体（UX-11。ARCHITECTURE §4.34）: 直前の記録と同じキー・時間窓内なら先端を置換する
    private var lastCoalesceKey: Any? = null
    private var lastEditAtMillis: Long = 0L

    /** 保存・ソース表示に使う正規化テキスト。パース不能なら元テキストを返す。 */
    fun canonicalText(): String = root?.let { YamlIO.dumpAll(listOf(it) + extraDocs) } ?: rawText

    /**
     * 編集をスナップショット履歴に記録し、dirty を更新する（最大100件）。
     * coalesceKey（UX-11）: 連続入力になり得る編集面が渡す合体キー。直前の記録と同じキー（`==`）かつ
     * COALESCE_WINDOW_MS 以内かつ履歴の先端なら、新規エントリを積まず先端スナップショットを置換する
     * （タイプ1文字ごとに Undo 1回が要る粒度の粗さを解消する）。null（既定）は常に境界＝新規エントリ。
     * nowMillis はテストからの時刻注入用（タイマー・sleep を使わない規約）。
     */
    fun recordEdit(coalesceKey: Any? = null, nowMillis: Long = System.currentTimeMillis()) {
        dirty = canonicalText() != savedSnapshot
        val snapshot = canonicalText()
        if (history.getOrNull(historyIndex) == snapshot) return
        val coalesce = coalesceKey != null && coalesceKey == lastCoalesceKey &&
            nowMillis - lastEditAtMillis <= COALESCE_WINDOW_MS &&
            historyIndex == history.lastIndex && historyIndex > 0
        while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
        if (coalesce) {
            history[historyIndex] = snapshot
        } else {
            history.add(snapshot)
            if (history.size > 100) history.removeAt(0) else historyIndex++
        }
        lastCoalesceKey = coalesceKey
        lastEditAtMillis = nowMillis
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
        // 復元した状態へ後続の入力が合体しないよう、合体キーをリセットする（UX-11）
        lastCoalesceKey = null
    }

    /** ディスク再読込などで内容が差し替わったとき、履歴と保存基準を取り直す。 */
    fun resetHistory() {
        history.clear()
        history.add(canonicalText())
        historyIndex = 0
        savedSnapshot = canonicalText()
        dirty = false
        lastCoalesceKey = null
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
        .filter { file ->
            val rel = ws.relPath(file)
            val zone = ws.layout.zoneOf(rel)
            // schemas ゾーンとゾーン外の構成ファイル（.yde/workspace.yaml）は一覧に出さない
            zone != schema.WorkspaceLayout.Zone.SCHEMAS && !(ws.layout.isReserved(rel) && zone == null)
        }
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
            .filter { ws.layout.inLedger(ws.relPath(it)) }
            .forEach { file ->
                (loader(file) as? YNode.Map)?.entries?.forEach { (k, v) ->
                    if (v is YNode.Scalar && k != "id" && !k.startsWith("_")) keys.add(k)
                }
            }
        return keys.toList()
    }

    /**
     * ルート直下スカラーの一括変更のセッション経路（A-2a・D-11）: 対象文書への SetScalar を
     * **1つのトランザクション**として適用してから保存する＝1件でも適用できなければ何も書かない
     * （従来の逐次書込にあった部分適用を廃止）。スキップ規則は updateRootScalar と同じ——
     * ルートが object でない・対象キーが複合値の文書は対象外。コメント・表記は SetScalar が継承する（R8-01）。
     * 履歴一元化（A-3）後は Undo 1回で全ファイルが戻る。対象にできた件数を返す。
     */
    fun bulkUpdateViaSession(
        session: workspace.WorkspaceSession,
        ids: Collection<model.DocumentId>,
        key: String,
        value: String,
    ): Int {
        val documents = session.snapshot.documents
        val targets = ids.filter { id ->
            val root = documents[id]?.document?.documents?.firstOrNull() as? YNode.Map ?: return@filter false
            val existing = root.entries[key]
            existing == null || existing is YNode.Scalar
        }
        if (targets.isEmpty()) return 0
        val applied = session.apply(
            workspace.WorkspaceTransaction(
                baseVersion = session.version,
                intent = workspace.ChangeIntent("一括変更: $key"),
                operations = targets.map {
                    workspace.WorkspaceOperation.EditDocument(it, listOf(edit.YamlOperation.SetScalar(listOf(key), value)))
                },
            )
        )
        require(applied.ok) { applied.message ?: "一括変更を適用できません" }
        val saved = session.save(targets.toSet())
        require(saved.ok) {
            saved.failures.entries.firstOrNull()?.let { (id, msg) -> "${id.value}: $msg" } ?: "書込に失敗しました"
        }
        return targets.size
    }

    /**
     * ルート直下スカラーの一括変更（R8-05）。複合値（配列・オブジェクト）のキーは壊さないよう飛ばす。
     * コメント・表記スタイルは維持する（inheritMetaFrom）。変更できた件数を返す。
     * セッション不在時（A-2a のフォールバック）だけが使う従来の逐次書込。
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
