package index

import model.DocumentId
import model.YNode
import schema.EntityRole
import schema.SchemaDef
import semantic.DocumentScan
import semantic.ScannedId
import semantic.ScannedRef
import semantic.SemanticDocument
import semantic.SemanticEntityGraph
import semantic.SemanticScanner
import semantic.SemanticSnapshot
import semantic.isAllowedBy as kernelIsAllowedBy
import java.nio.file.Path

/**
 * ワークスペース内で定義された ID 1 件と、ID への参照 1 件。カーネルの型（semantic.ScannedId / ScannedRef）の別名
 * （P27 段D の D-d・D-e。以前は、ファイルの場所を Path で持つ同じ形の型を root にも持ち、行き来のたびに写していた）。
 * - 場所は、文書ID（document＝ワークスペース相対パス。区切りは '/'）と、文書の中のパス（path）。ファイルを開くなど、
 *   Path が要る所でだけ、ワークスペースのルートから解決する（core.Workspace.fileOf / AppState.fileOf）
 * - IdEntry.path は ID を持つ要素（マップ）の位置。RefEntry.path は参照値（スカラー）の位置（属性付き参照では to 値の座標。R20-03）
 */
typealias IdEntry = ScannedId
typealias RefEntry = ScannedRef

/** エンティティグラフ（R20-04。ARCHITECTURE §4.30）と、そのノード。カーネルの型の別名。 */
typealias EntityGraph = SemanticEntityGraph
typealias EntityNode = semantic.SemanticEntityNode
typealias DocumentNode = semantic.SemanticDocumentNode

fun idMatchesPattern(id: String, pattern: String): Boolean = semantic.idMatchesPattern(id, pattern)

/** 参照の制約（idPatterns / scope）がこの ID 出現を許すか（実体はカーネル）。 */
fun IdEntry.isAllowedBy(ref: RefEntry): Boolean = kernelIsAllowedBy(ref)

/**
 * 1 ドキュメントから ID 定義と参照を抽出するスキャナ。走査の実装本体は semantic-core の SemanticScanner（3層コア移行 SM-001）で、
 * ここは、アプリ側のスキーマの型（SchemaDef）から呼ぶための窓口。走査規則は従来どおり:
 * - スキーマがある場合: type: id / type: ref のフィールドを正として扱う
 * - スキーマがない場合: 命名規約ヒューリスティクス（schema.RefNaming）で検出する
 * - スキーマ外のサブツリー: 未知項目の境界で走査を止める
 */
object DocScanner {

    // 命名規約の実体は schema.RefNaming（スキーマ推測 R11-01 と共有。ARCHITECTURE §4.23）
    fun isGenericRefKey(k: String): Boolean = schema.RefNaming.isRefKey(k)

    fun isGenericRefListKey(k: String): Boolean = schema.RefNaming.isRefListKey(k)

    /** document は、この文書の文書ID（ワークスペース相対パス）。documentId は、文書の論理ID（`_document.id`。無ければ相対パス）。 */
    fun scan(
        root: YNode?,
        document: DocumentId,
        schemaDef: SchemaDef?,
        documentId: String = document.value,
    ): Pair<List<IdEntry>, List<RefEntry>> {
        if (root == null) return emptyList<IdEntry>() to emptyList()
        return SemanticScanner.scan(
            SemanticDocument(
                id = document,
                root = root,
                schema = schemaDef?.tree,
                schemaId = schemaDef?.id,
                entities = schemaDef?.entities ?: emptyList(),
                documentId = documentId,
            )
        )
    }
}

/**
 * ワークスペース全体の ID 索引（走査結果の一式と、その上の覚え書き）。
 * 中身は、カーネルの意味解析の結果（SemanticSnapshot＝走査結果・ID の集約・全参照・エンティティグラフ）そのもので、写しを持たない
 * （P27 段D の D-d・D-e）。供給はセッション派生に一本化されている（RB-09）。ディスクを映した索引（core.Workspace.index）は、
 * 未保存の編集を「保存」または「再読込」まで反映しない。未保存の編集を重ねた索引は、別のインスタンス（AppState.overlayIndex）。
 */
class IdIndex {

    /** 文書単位の走査結果（文書ID 順）。ID も参照も持たない文書も含む。 */
    var scans: List<DocumentScan> = emptyList()
        private set

    /** 論理 id -> 文書ごとの出現一覧 */
    var entries: Map<String, List<IdEntry>> = emptyMap()
        private set

    /** ワークスペース内の全参照 */
    var refs: List<RefEntry> = emptyList()
        private set

    /** 意味解析の結果から渡されたグラフ（populate(snapshot)）。走査結果だけを渡されたとき（テスト）は、要るときに導出する。 */
    private var suppliedGraph: SemanticEntityGraph? = null

    /** 走査結果が変わる（populate）たびに作り直す、導出とその覚え書き。 */
    private var derivedState: Derived? = null

    private inner class Derived {
        val graph: SemanticEntityGraph by lazy { suppliedGraph ?: SemanticEntityGraph.build(scans, entries) }

        // 被参照の索引（全参照の並び順＝文書ID 順を保つ）
        val refsByTarget: Map<String, List<RefEntry>> by lazy { refs.groupBy { it.targetId } }
        val refOrder: Map<RefEntry, Int> by lazy {
            HashMap<RefEntry, Int>(refs.size * 2).also { m -> refs.forEachIndexed { i, r -> m.putIfAbsent(r, i) } }
        }
        val logicalIdByDocument: Map<DocumentId, String> by lazy { scans.associate { it.document to it.documentId } }

        // 検査の評価の覚え書き（P27 段B）と、宣言のない関係名の全体の指摘（宣言の内容ごとに1回。BACKLOG UX-116）
        val checkSession: semantic.SemanticCheckSession by lazy { semantic.SemanticCheckSession(entries, refs, graph) }
        val integrityCache = HashMap<semantic.SemanticDeclarations, Map<DocumentId, List<semantic.SemanticIssue>>>()
    }

    private fun derived(): Derived = derivedState ?: Derived().also { derivedState = it }

    /** カーネルの意味解析の結果を、そのまま索引にする（集約もグラフも、作り直さない）。既存の内容は置き換えられる。 */
    fun populate(snapshot: SemanticSnapshot) {
        scans = snapshot.scans
        entries = snapshot.ids
        refs = snapshot.refs
        suppliedGraph = snapshot.graph
        derivedState = null
    }

    /**
     * 走査結果だけから索引を作る（テスト用の索引フィクスチャなど）。文書ID 順に並べ直してから集約する
     * （並び・導出は、セッションの意味解析と同じ）。
     */
    fun populate(scans: List<DocumentScan>) {
        val sorted = scans.sortedBy { it.document.value }
        val ids = LinkedHashMap<String, MutableList<IdEntry>>()
        val allRefs = ArrayList<RefEntry>()
        for (scan in sorted) {
            for (e in scan.ids) ids.getOrPut(e.id) { mutableListOf() }.add(e)
            allRefs += scan.refs
        }
        this.scans = sorted
        entries = ids
        refs = allRefs
        suppliedGraph = null
        derivedState = null
    }

    /**
     * 走査結果から導出したエンティティグラフ（R20-04。ARCHITECTURE §4.30）。走査結果の更新で入れ替わる。
     * ディスク・オーバーレイ・ステージングの各 IdIndex インスタンスが自分のグラフを持つため、§4.5 の二層構造は崩れない。
     */
    val graph: EntityGraph get() = derived().graph

    /**
     * 検査の評価の覚え書き（P27 段B）: この索引の上では、検査 × 要素の結果を1回だけ評価する。全体を回す経路
     * （CLI validate・準備完了レポート・提出ゲート・アプリの全体検証）が共有する。走査結果が変わると、覚え書きも一緒に捨てられる。
     * 共有してよいのは、loader の中身がこの索引と同じ内容の呼び出しだけ（SemanticCheckSession の前提）。
     */
    val checkSession: semantic.SemanticCheckSession get() = derived().checkSession

    /**
     * グラフ整合（宣言のない関係名）のワークスペース全体の指摘。文書ごとの検証が、そのたびに全参照を調べ直さないように、
     * 宣言の内容ごとに1回だけ求める。
     */
    fun integrityIssues(declarations: semantic.SemanticDeclarations): Map<DocumentId, List<semantic.SemanticIssue>> {
        val d = derived()
        return synchronized(d.integrityCache) {
            d.integrityCache.getOrPut(declarations) {
                semantic.SemanticGraphIntegrity.issues(refs, d.graph, declarations).groupBy { it.document }
            }
        }
    }

    fun occurrences(id: String): List<IdEntry> = entries[id].orEmpty()

    /** ID の代表出現。定義出現（role=DEFINING。R20-01）があればそれを優先し、なければ先頭（文書ID 順）。 */
    fun resolve(id: String): IdEntry? =
        occurrences(id).let { occs -> occs.firstOrNull { it.role == EntityRole.DEFINING } ?: occs.firstOrNull() }

    fun resolve(ref: RefEntry): List<IdEntry> = occurrences(ref.targetId).filter { it.isAllowedBy(ref) }

    /** 文書の論理ID（索引に載っていればその値。載っていなければ、いまの内容の `_document.id`。無ければ相対パス）。 */
    fun documentIdFor(document: DocumentId, root: YNode): String =
        derived().logicalIdByDocument[document] ?: documentIdOf(root, document.value)

    /** 指定 ID 群を参照しているエントリ（被参照＝バックリンク）。全参照の並び順（文書ID 順）を保つ。 */
    fun backlinksTo(idSet: Set<String>): List<RefEntry> {
        val d = derived()
        val merged = idSet.flatMap { d.refsByTarget[it].orEmpty() }
        return if (idSet.size <= 1) merged else merged.sortedBy { d.refOrder[it] ?: Int.MAX_VALUE }
    }

    /**
     * 参照値の位置を含む、最も内側（パスが最長）の ID 出現＝参照を書いた要素。
     * バックリンクの表示解決（「どの要素からの参照か」を ID・タイトルで見せる。R10-01）に使う。
     */
    fun sourceEntryOf(ref: RefEntry): IdEntry? = graph.sourceEntryOf(ref)

    /** ID の全出現のサブツリー配下に書かれた参照（出次エッジ）。トラバース・related が共用する（R20-04）。 */
    fun outgoingRefs(id: String): List<RefEntry> = graph.outgoingRefs(id)

    /** ベンチ計測用: 渡されたグラフも、導出とその覚え書きも捨てる（次のアクセスで、走査結果からグラフを作り直させる）。 */
    internal fun dropGraphCache() {
        suppliedGraph = null
        derivedState = null
    }

    companion object {
        /** 意味解析の結果から索引を作る。 */
        fun of(snapshot: SemanticSnapshot): IdIndex = IdIndex().also { it.populate(snapshot) }

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
