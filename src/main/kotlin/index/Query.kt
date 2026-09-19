package index

import model.YNode
import model.YPath
import schema.QueryNodeKind
import schema.QuerySpec
import semantic.SemanticQuery
import semantic.SemanticQueryNodeRef
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * パターン照合エンジン（R22-01。ARCHITECTURE §4.32）のディスク索引向けシェル。
 * 評価アルゴリズムの本体は semantic-core の SemanticQuery（3層コア移行 Phase 6C）で、
 * ここは IdIndex をカーネル入力（DocumentId 座標）へ写して委譲し、結果を Path 座標へ写し戻す。
 * 決定性・判定保留・循環照合の停止・loader 注入の契約はカーネル側に記載。
 * Traverse（core）の隣接1段（`neighborIds`）もここ経由でカーネルに一元化する（R22-02③）。
 */

/** 結果の1ノード。エンティティの座標は定義出現（なければ先頭出現）、文書はファイル＋空パス。 */
data class QueryNodeRef(
    val kind: QueryNodeKind,
    val id: String,
    val title: String?,
    val entityType: String?,
    val file: Path?,
    val path: YPath,
)

/** 束縛した参照（edge の bind）が書かれた場所。 */
data class QueryRefLocation(val file: Path, val path: YPath, val targetId: String, val relation: String?)

/** 結果の1行（束縛名 → ノード。列順は束縛の宣言順）。refs は edge の bind で束縛した参照の場所。 */
data class QueryRow(val bindings: Map<String, QueryNodeRef>, val refs: Map<String, QueryRefLocation> = emptyMap())

/**
 * 照合結果。total は limit 打切り前の全行数。unknownDropped は解探索の途中で未解決参照により
 * 候補が失われた記録（CALC-17 の unknown-dropped。制約判定は「解の不足を理由とする違反」を降格する）。
 */
data class QueryResult(
    val columns: List<String>,
    val rows: List<QueryRow>,
    val total: Int,
    val unknownDropped: Boolean = false,
)

object Query {

    private val diskLoader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() }

    /** パターンを評価して束縛の表を返す。 */
    fun run(index: IdIndex, spec: QuerySpec, loader: (Path) -> YNode? = diskLoader): QueryResult {
        val view = index.toKernelView()
        val result = SemanticQuery.run(view.entries, view.refs, view.graph, spec) { d -> loader(Path.of(d.value)) }
        return QueryResult(
            columns = result.columns,
            rows = result.rows.map { it.toRow() },
            total = result.total,
            unknownDropped = result.unknownDropped,
        )
    }

    /**
     * 起点を固定した存在判定（R22-03 パターン制約用）: startId を起点束縛に固定したとき、
     * パターン全体（起点フィルタ・hops・also・where）に解が1つ以上あるか。
     */
    fun anyMatchFrom(index: IdIndex, spec: QuerySpec, startId: String, loader: (Path) -> YNode? = diskLoader): Boolean {
        val view = index.toKernelView()
        return SemanticQuery.anyMatchFrom(view.entries, view.refs, view.graph, spec, startId) { d -> loader(Path.of(d.value)) }
    }

    /**
     * 起点を固定した解の件数（R28-02 count 制約用）: 束縛の組（bind 列）の distinct 件数
     * （limit 打切り前の total）。数えたい対象のノードに bind を書くこと。
     */
    fun countMatchesFrom(index: IdIndex, spec: QuerySpec, startId: String, loader: (Path) -> YNode? = diskLoader): Int {
        val view = index.toKernelView()
        return SemanticQuery.countMatchesFrom(view.entries, view.refs, view.graph, spec, startId) { d -> loader(Path.of(d.value)) }
    }

    /**
     * 起点を固定した解の列挙（R28-05: 制約指摘の説明用）: 束縛の表（limit まで。total は打切り前）。
     */
    fun matchesFrom(index: IdIndex, spec: QuerySpec, startId: String, loader: (Path) -> YNode? = diskLoader): QueryResult {
        val view = index.toKernelView()
        val result = SemanticQuery.matchesFrom(view.entries, view.refs, view.graph, spec, startId) { d -> loader(Path.of(d.value)) }
        return QueryResult(
            columns = result.columns,
            rows = result.rows.map { it.toRow() },
            total = result.total,
            unknownDropped = result.unknownDropped,
        )
    }

    /**
     * 起点を固定した check 判定（R28-08）: 解ごとに判定述語（束縛間の値比較）を
     * 出現割当て σ で反例量化し、違反した組（束縛・出現・値）を返す。
     * 空リスト＝違反なし（充足、または評価可能な組がない判定保留）。
     */
    fun checkFrom(
        index: IdIndex,
        spec: QuerySpec,
        checks: List<schema.ConstraintCheck>,
        startId: String,
        loader: (Path) -> YNode? = diskLoader,
        /** ワークスペース相対パス（値を読む文書の指定の fileGlob 判定に使う。既定はパス文字列そのまま）。 */
        rel: (Path) -> String = { it.toString().replace('\\', '/') },
        /** 値を読む出現の固定（指摘を「値が書かれた場所」へ出す検査用。束縛と文書の指定 → その出現）。 */
        pin: Pair<schema.ValueExpr.ScopeRef, Pair<Path, YPath>>? = null,
    ): List<semantic.SemanticCheckViolation> {
        val view = index.toKernelView()
        return SemanticQuery.checkFrom(
            view.entries, view.refs, view.graph, spec, checks, startId,
            rel = { d -> rel(Path.of(d.value)) },
            pin = pin?.let { (source, occ) ->
                source to semantic.SemanticExpression.Occurrence(model.DocumentId(occ.first.toString()), occ.second)
            },
        ) { d -> loader(Path.of(d.value)) }
    }

    /**
     * 網羅マトリクス（R28-03）: 行 rowId の不足セル（証人のない列。ID昇順）。
     * 行の母集団外・列の母集団が空のときは空リスト（判定保留）。
     */
    fun matrixMissingColumns(index: IdIndex, rule: schema.MatrixRule, rowId: String): List<semantic.SemanticMatrixColumn> {
        val view = index.toKernelView()
        return semantic.SemanticMatrix.missingColumns(view.graph, rule, rowId)
    }

    /**
     * 遷移検証（R28-04）: フロー規則の全指摘（ノードID昇順）。母集団が空・開始データなしは
     * 判定保留（空リスト）。未解決参照の候補喪失時は解の不足系の判定を黙らせる（CALC-17）。
     */
    fun flowFindings(index: IdIndex, rule: schema.FlowRule): List<semantic.SemanticFlowFinding> {
        val view = index.toKernelView()
        return semantic.SemanticFlow.findings(view.graph, view.refs, rule)
    }

    /**
     * startId が起点束縛の母集団（起点 node のフィルタ＋起点束縛の where 述語）に入るか（R22-03）。
     * 制約の「対象外は黙る」（判定保留 §4.26）と「解なし」を区別するために使う。
     */
    fun startMatches(index: IdIndex, spec: QuerySpec, startId: String, loader: (Path) -> YNode? = diskLoader): Boolean {
        val view = index.toKernelView()
        return SemanticQuery.startMatches(view.entries, view.refs, view.graph, spec, startId) { d -> loader(Path.of(d.value)) }
    }

    /**
     * Traverse 互換の隣接1段（R22-02③）。out = フロンティア配下に書かれた参照の targetId、
     * in = フロンティアIDへの被参照の書き手（最内のID要素）。distinct＋ID昇順（visited・limit は呼び出し側）。
     */
    fun neighborIds(
        index: IdIndex,
        frontier: List<String>,
        follow: String,
        relation: String? = null,
        via: String? = null,
        schemaId: String? = null,
    ): List<String> {
        val view = index.toKernelView()
        return SemanticQuery.neighborIds(view.entries, view.refs, view.graph, frontier, follow, relation, via, schemaId)
    }

    /** カーネルの行を Path 座標の行へ写す（check の違反が持つ行にも使う）。 */
    fun semantic.SemanticQueryRow.toRow(): QueryRow = QueryRow(
        bindings = bindings.mapValues { (_, ref) -> ref.toNodeRef() },
        refs = refs.mapValues { (_, r) -> QueryRefLocation(Path.of(r.document.value), r.path, r.targetId, r.relation) },
    )

    private fun SemanticQueryNodeRef.toNodeRef() = QueryNodeRef(
        kind = kind,
        id = id,
        title = title,
        entityType = entityType,
        file = document?.let { Path.of(it.value) },
        path = path,
    )
}
