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
                schema = schemaDef?.tree,
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

    /**
     * カーネル入力一式（走査列・ID集約・全参照・カーネルのグラフ）のキャッシュ。graphCache と同じく、走査結果が変わる
     * （derive）たびに無効化する。検査はクエリを「規則 × 要素」の数だけ呼ぶため、呼び出しごとに作り直すと
     * 全体検証の時間の大半をここで使ってしまう（BACKLOG UX-112）。
     */
    private var kernelViewCache: KernelIndexView? = null

    /** カーネル入力一式を、この索引につき1回だけ作る（作り方は SemanticBridge の toKernelView が渡す）。 */
    internal fun cachedKernelView(build: () -> KernelIndexView): KernelIndexView =
        kernelViewCache ?: build().also { kernelViewCache = it }

    /** 論理 id -> 文書ごとの出現一覧 */
    var entries: Map<String, List<IdEntry>> = emptyMap()
        private set

    /** ワークスペース内の全参照 */
    var refs: List<RefEntry> = emptyList()
        private set

    // rebuild / overlay / update（ディスク走査＋オーバーレイ走査の経路）は RB-09 で削除——
    // 供給はセッション派生（populateFromScans / derivedIdIndex。SemanticBridge）に一本化された。
    // 走査の実体はカーネル（SemanticScanner）、オーバーレイは WorkspaceSession.derivedWith が担う。

    /** populate の入力1件（ファイル単位の走査結果）。 */
    data class PopulatedScan(val file: Path, val documentId: String, val ids: List<IdEntry>, val refs: List<RefEntry>)

    /**
     * 外部で組み立てた走査結果の一括投入（RA-02）。セッション派生（derived）を供給元にする
     * IdIndex ファサードの構築（index.derivedIdIndex）が使う。既存の内容は置き換えられ、
     * 集約（entries / refs / graph）は通常の derive と同じ経路＝並び・導出は従来と同一。
     */
    fun populate(scans: List<PopulatedScan>) {
        byFile.clear()
        scans.forEach { byFile[it.file] = FileScan(it.ids, it.refs, it.documentId) }
        derive()
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
        kernelViewCache = null
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

        /** 文書の論理ID（`_document.id` ＞ fallback）。実装は semantic-core の DocumentMeta（Phase 6C）。 */
        fun documentIdOf(root: YNode, fallback: String): String = semantic.DocumentMeta.idOf(root, fallback)

        /** ルートの予約メタデータに宣言された表示用の文書名。 */
        fun documentNameOf(root: YNode): String? = semantic.DocumentMeta.nameOf(root)

        /** ルートの予約メタデータに宣言された、文書全体の概要。 */
        fun documentDescriptionOf(root: YNode): String? = semantic.DocumentMeta.descriptionOf(root)
    }
}
