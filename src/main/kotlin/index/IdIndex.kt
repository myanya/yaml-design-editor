package index

import model.DocumentId
import model.YNode
import model.YPath
import model.scalarText
import semantic.SemanticDocument
import semantic.SemanticScanner
import schema.EntityRole
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import schema.RefScope
import schema.WorkspaceLayout
import schema.effectiveFields
import schema.entityFor
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/** ワークスペース内で定義された ID 1 件。path は ID を持つ要素（マップ）の位置。 */
data class IdEntry(
    val id: String,
    val file: Path,
    val path: YPath,
    val title: String?,   // 同じ要素の title / name（表示用）
    val schemaId: String?,
    val documentId: String,
    /** スキーマの entity 宣言から解決したエンティティ型（R20-01。null = 宣言なし）。 */
    val entityType: String? = null,
    /** この出現の役割（定義／詳細化。R20-01。null = 宣言なし）。 */
    val role: schema.EntityRole? = null,
)

/** ID への参照 1 件。path は参照値（スカラー）の位置（属性付き参照では to 値の座標。R20-03）。 */
data class RefEntry(
    val targetId: String,
    val file: Path,
    val path: YPath,
    val viaKey: String,   // 参照が書かれていたキー名（表示用）
    val idPatterns: List<String> = emptyList(),
    val scope: RefScope = RefScope(),
    val relation: String? = null,       // スキーマで宣言された関係種別（type: ref の relation）
    val sourceSchemaId: String? = null, // 参照元文書のスキーマID（カバレッジ判定に使う）
    /** 属性付き参照（R20-03）のスカラー属性の写し（表示注記用。値そのものはIDのまま）。 */
    val attributes: Map<String, String> = emptyMap(),
    /** 参照の対象種別（R21-03。ARCHITECTURE §4.31）: エンティティ（既定）／文書。文書参照は別名前空間で解決する。 */
    val targetKind: schema.RefTarget = schema.RefTarget.ENTITY,
) {
    /** 属性の1行注記（例「role: 承認者」）。表示側は括弧に閉じて添える（R20-03。§4.30）。 */
    fun attributeSummary(): String? =
        attributes.entries.takeIf { it.isNotEmpty() }?.joinToString(", ") { (k, v) -> "$k: $v" }
}

fun idMatchesPattern(id: String, pattern: String): Boolean =
    runCatching { schema.idGlobToRegex(pattern).matches(id) }.getOrDefault(false)

fun IdEntry.isAllowedBy(ref: RefEntry): Boolean =
    (ref.idPatterns.isEmpty() || ref.idPatterns.any { idMatchesPattern(id, it) }) &&
        (ref.scope.schemaIds.isEmpty() || schemaId in ref.scope.schemaIds) &&
        (ref.scope.documentIds.isEmpty() || documentId in ref.scope.documentIds)

/**
 * 1 ドキュメントから ID 定義と参照を抽出するスキャナ。
 * 走査の実装本体は semantic-core の SemanticScanner（3層コア移行 SM-001）で、
 * ここは Path ↔ DocumentId の座標変換だけを行う委譲シェル。走査規則は従来どおり:
 * - スキーマがある場合: type: id / type: ref のフィールドを正として扱う
 * - スキーマがない場合: 命名規約ヒューリスティクス（schema.RefNaming）で検出する
 * - スキーマ外のサブツリー: 未知項目の境界で走査を止める
 */
object DocScanner {

    // 命名規約の実体は schema.RefNaming（スキーマ推測 R11-01 と共有。ARCHITECTURE §4.23）
    fun isGenericRefKey(k: String): Boolean = schema.RefNaming.isRefKey(k)

    fun isGenericRefListKey(k: String): Boolean = schema.RefNaming.isRefListKey(k)

    fun scan(root: YNode?, file: Path, schemaDef: SchemaDef?, documentId: String = file.toString()): Pair<List<IdEntry>, List<RefEntry>> {
        if (root == null) return emptyList<IdEntry>() to emptyList()
        val (ids, refs) = SemanticScanner.scan(
            SemanticDocument(
                id = DocumentId(file.toString()),
                root = root,
                schema = schemaDef?.structural,
                schemaId = schemaDef?.id,
                entities = schemaDef?.entities ?: emptyList(),
                documentId = documentId,
            )
        )
        return ids.map { IdEntry(it.id, file, it.path, it.title, it.schemaId, it.documentId, it.entityType, it.role) } to
            refs.map {
                RefEntry(
                    it.targetId, file, it.path, it.viaKey, it.idPatterns, it.scope,
                    it.relation, it.sourceSchemaId, it.attributes, it.targetKind,
                )
            }
    }
}

/**
 * ワークスペース全体の ID 索引。
 * ディスク上のファイルを対象とするため、未保存の編集は「保存」または「再読込」まで反映されない。
 * ファイル単位の走査結果を保持し、変更ファイルだけの差し替え（増分更新。R8-02）ができる。
 */
class IdIndex {

    private class FileScan(val ids: List<IdEntry>, val refs: List<RefEntry>, val documentId: String)

    /** ファイル -> 走査結果。集約（entries / refs）はここから導出する。 */
    private val byFile = HashMap<Path, FileScan>()

    /** 導出グラフ（R20-04）。走査結果が変わる（derive）たびに無効化し、次のアクセスで再導出する。 */
    private var graphCache: EntityGraph? = null

    /** 論理 id -> 文書ごとの出現一覧 */
    var entries: Map<String, List<IdEntry>> = emptyMap()
        private set

    /** ワークスペース内の全参照 */
    var refs: List<RefEntry> = emptyList()
        private set

    /** ディスクから読む既定のローダ（複数ドキュメントYAMLは先頭を索引化する。R8-05）。 */
    private val diskLoader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() }

    /**
     * 索引を全ファイルから再構築する。loader を差し替えると、ディスク以外（例: エージェントのステージング）の
     * 内容で索引を組める。schemas ゾーンと機械が書く実行記録 runs ゾーンは索引対象外（`layout.indexExcluded`）。
     */
    fun rebuild(
        workspace: Path,
        files: List<Path>,
        schemas: List<SchemaDef>,
        layout: WorkspaceLayout = WorkspaceLayout.LEGACY,
        loader: (Path) -> YNode? = diskLoader,
    ) {
        byFile.clear()
        for (f in files) {
            scanFile(workspace, f, schemas, layout, loader)?.let { byFile[f] = it }
        }
        derive()
    }

    /**
     * 未保存編集を重ねたオーバーレイ索引（UX-03）: この索引の走査結果を複製し、指定ファイルだけを
     * loader の内容（編集中ツリー）で走査し直した**新しい索引**を返す（この索引自体は変更しない）。
     * コストは走査結果の複製＋対象ファイル数に比例する。UIの「見たまま判定」（ARCHITECTURE §4.5）に使う。
     */
    fun overlay(
        workspace: Path,
        files: Collection<Path>,
        schemas: List<SchemaDef>,
        layout: WorkspaceLayout = WorkspaceLayout.LEGACY,
        loader: (Path) -> YNode?,
    ): IdIndex {
        val out = IdIndex()
        out.byFile.putAll(byFile)
        out.update(workspace, files, emptyList(), schemas, layout, loader)
        return out
    }

    /**
     * 変更・削除されたファイルの分だけ走査し直して索引を差し替える（R8-02: 増分更新）。
     * スキーマ自体が変わった場合は全ファイルの走査規則が変わるため、rebuild を使うこと。
     */
    fun update(
        workspace: Path,
        changed: Collection<Path>,
        removed: Collection<Path> = emptyList(),
        schemas: List<SchemaDef>,
        layout: WorkspaceLayout = WorkspaceLayout.LEGACY,
        loader: (Path) -> YNode? = diskLoader,
    ) {
        removed.forEach { byFile.remove(it) }
        changed.forEach { f ->
            val scan = scanFile(workspace, f, schemas, layout, loader)
            if (scan != null) byFile[f] = scan else byFile.remove(f)
        }
        derive()
    }

    /** 1 ファイルを走査する。索引対象外・読取不能なら null。 */
    private fun scanFile(workspace: Path, f: Path, schemas: List<SchemaDef>, layout: WorkspaceLayout, loader: (Path) -> YNode?): FileScan? {
        val rel = relPathOf(workspace, f)
        if (layout.indexExcluded(rel)) return null
        // 台帳外（ledger 宣言の対象外・隠しパス）は、増分更新（update）で個別に渡されても索引に載せない（R13-02）
        if (!layout.inLedger(rel) && layout.zoneOf(rel) == null) return null
        val root = loader(f) ?: return null
        val schemaDef = schemas.firstOrNull { it.matches(rel) }
        val documentId = documentIdOf(root, rel)
        val (docIds, docRefs) = DocScanner.scan(root, f, schemaDef, documentId)
        return FileScan(docIds, docRefs, documentId)
    }

    /** byFile から集約ビュー（entries / refs）を導出する。順序はパス順で安定させる。 */
    private fun derive() {
        val ids = mutableMapOf<String, MutableList<IdEntry>>()
        val allRefs = mutableListOf<RefEntry>()
        for (f in byFile.keys.sortedBy { it.toString() }) {
            val scan = byFile.getValue(f)
            for (e in scan.ids) ids.getOrPut(e.id) { mutableListOf() }.add(e)
            allRefs += scan.refs
        }
        entries = ids
        refs = allRefs
        graphCache = null
    }

    /**
     * 走査結果から導出したエンティティグラフ（R20-04。ARCHITECTURE §4.30）。lazy に導出し、
     * 走査結果の更新（rebuild / update / overlay）で無効化される。ディスク・オーバーレイ・ステージングの
     * 各 IdIndex インスタンスが自分のグラフを持つため、§4.5 の二層構造は崩れない。
     */
    val graph: EntityGraph
        get() = graphCache ?: EntityGraph.build(
            byFile.entries.sortedBy { it.key.toString() }
                .map { (f, scan) -> FileScanView(f, scan.documentId, scan.ids, scan.refs) },
            entries, refs,
        ).also { graphCache = it }

    fun occurrences(id: String): List<IdEntry> = entries[id].orEmpty()

    /** ID の代表出現。定義出現（role=DEFINING。R20-01）があればそれを優先し、なければ先頭（パス順）。 */
    fun resolve(id: String): IdEntry? =
        occurrences(id).let { occs -> occs.firstOrNull { it.role == EntityRole.DEFINING } ?: occs.firstOrNull() }

    fun resolve(ref: RefEntry): List<IdEntry> = occurrences(ref.targetId).filter { it.isAllowedBy(ref) }

    fun documentIdFor(file: Path, root: YNode): String =
        byFile[file]?.documentId ?: documentIdOf(root, file.toString())

    /** 指定 ID 群を参照しているエントリ（被参照＝バックリンク）。全参照の並び順（パス順）を保つ。 */
    fun backlinksTo(idSet: Set<String>): List<RefEntry> = graph.backlinksTo(idSet)

    /**
     * 参照値の位置を含む、最も内側（パスが最長）の ID 出現＝参照を書いた要素。
     * バックリンクの表示解決（「どの要素からの参照か」を ID・タイトルで見せる。R10-01）に使う。
     */
    fun sourceEntryOf(ref: RefEntry): IdEntry? = graph.sourceEntryOf(ref)

    /** ID の全出現のサブツリー配下に書かれた参照（出次エッジ）。トラバース・related が共用する（R20-04）。 */
    fun outgoingRefs(id: String): List<RefEntry> = graph.outgoingRefs(id)

    /** ベンチ計測用: 導出グラフのキャッシュを捨てる（次の graph アクセスで再導出させる）。 */
    internal fun dropGraphCache() {
        graphCache = null
    }

    companion object {
        fun relPathOf(workspace: Path, file: Path): String =
            workspace.relativize(file).toString().replace('\\', '/')

        fun documentIdOf(root: YNode, fallback: String): String {
            val metadata = (root as? YNode.Map)?.entries?.get("_document") as? YNode.Map
            return scalarText(metadata?.entries?.get("id")) ?: fallback
        }

        /** ルートの予約メタデータに宣言された表示用の文書名。 */
        fun documentNameOf(root: YNode): String? {
            val metadata = (root as? YNode.Map)?.entries?.get("_document") as? YNode.Map
            return scalarText(metadata?.entries?.get("name"))?.takeIf { it.isNotBlank() }
        }

        /** ルートの予約メタデータに宣言された、文書全体の概要。 */
        fun documentDescriptionOf(root: YNode): String? {
            val metadata = (root as? YNode.Map)?.entries?.get("_document") as? YNode.Map
            return scalarText(metadata?.entries?.get("description"))?.takeIf { it.isNotBlank() }
        }
    }
}
