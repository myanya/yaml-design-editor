package dev.toros.yde.app.core

import dev.toros.yde.workspace.ChangeIntent
import dev.toros.yde.workspace.WorkspaceOperation
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.workspace.WorkspaceTransaction
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 文書キャッシュ層の中核（R9-02）。
 * app.OpenDocument（Compose購読のシェル）から、UI非依存のロジック——
 * 複数ドキュメントのパース・正規化テキスト・保存した内容との比較——を core へ抽出したもの。
 * 単体テストはこの層（`DocumentsTest`）に書く。
 *
 * 履歴（Undo / Redo）は持たない——履歴と dirty の正は、カーネルのセッション（ADR-0006・D-07）。以前は、セッションが無いときのために
 * 文書ごとのスナップショットの履歴を併走させていたが、ワークスペースを開けば必ずセッションも開くので、無くした
 * （P27 段D の D-d・意図的差分 D-37）。ここの dirty は、セッションが文書として扱わないパス（構成ファイル・開いた時点で
 * パースできなかった文書など）のための判定で、聞かれたときにだけ計算する。
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

    /** 保存した内容（開いたとき・保存したとき・ディスクから読み直したときの正規化テキスト）。 */
    private var savedSnapshot = canonicalText()

    /** 保存した内容から変わっているか（聞かれたときに、正規化テキストを比べる）。 */
    val dirty: Boolean get() = canonicalText() != savedSnapshot

    /** 保存・ソース表示に使う正規化テキスト。パース不能なら元テキストを返す。 */
    fun canonicalText(): String = root?.let { YamlIO.dumpAll(listOf(it) + extraDocs) } ?: rawText

    /** ソース編集のテキストをツリーへ取り込む（複数ドキュメントは先頭を編集対象にし後続を保持）。 */
    fun applySourceText(text: String) {
        val docs = YamlIO.parseAll(text)
        root = docs.firstOrNull() ?: YNode.Map()
        extraDocs = docs.drop(1)
        parseError = null
    }

    /** いまの内容を、保存した内容にする（保存したとき・ディスク再読込などで内容が差し替わったとき）。 */
    fun markSaved() {
        savedSnapshot = canonicalText()
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
            zone != WorkspaceLayout.Zone.SCHEMAS && !(ws.layout.isReserved(rel) && zone == null)
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
        session: WorkspaceSession,
        ids: Collection<DocumentId>,
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
            WorkspaceTransaction(
                baseVersion = session.version,
                intent = ChangeIntent("一括変更: $key"),
                operations = targets.map {
                    WorkspaceOperation.EditDocument(it, listOf(YamlOperation.SetScalar(listOf(key), value)))
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
