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
