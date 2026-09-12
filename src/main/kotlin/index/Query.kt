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

/** 結果の1行（束縛名 → ノード。列順は束縛の宣言順）。 */
data class QueryRow(val bindings: Map<String, QueryNodeRef>)

/** 照合結果。total は limit 打切り前の全行数。 */
data class QueryResult(val columns: List<String>, val rows: List<QueryRow>, val total: Int)

object Query {

    private val diskLoader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() }

    /** パターンを評価して束縛の表を返す。 */
    fun run(index: IdIndex, spec: QuerySpec, loader: (Path) -> YNode? = diskLoader): QueryResult {
        val view = index.toKernelView()
        val result = SemanticQuery.run(view.entries, view.refs, view.graph, spec) { d -> loader(Path.of(d.value)) }
        return QueryResult(
            columns = result.columns,
            rows = result.rows.map { row -> QueryRow(row.bindings.mapValues { (_, ref) -> ref.toNodeRef() }) },
            total = result.total,
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
            rows = result.rows.map { row -> QueryRow(row.bindings.mapValues { (_, ref) -> ref.toNodeRef() }) },
            total = result.total,
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
    ): List<semantic.SemanticCheckViolation> {
        val view = index.toKernelView()
        return SemanticQuery.checkFrom(view.entries, view.refs, view.graph, spec, checks, startId) { d -> loader(Path.of(d.value)) }
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

    private fun SemanticQueryNodeRef.toNodeRef() = QueryNodeRef(
        kind = kind,
        id = id,
        title = title,
        entityType = entityType,
        file = document?.let { Path.of(it.value) },
        path = path,
    )
}
