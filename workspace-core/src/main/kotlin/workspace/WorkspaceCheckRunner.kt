package workspace

import model.DocumentId
import semantic.SemanticCheckSession
import validate.CheckDelivery
import validate.CheckSchema
import validate.WorkspaceChecks

/**
 * 読み取ったワークスペース全体について、定義体の検査を実行する（P27 段B-4）。カーネル側の CLI（`workspace validate`）が使う。
 *
 * 派生状態の導出（WorkspaceReader.derive）には含めない——導出はセッションの編集のたびに走るため、そこでワークスペース全体の
 * 検査を評価すると、アプリで1文字打つたびに全体を評価することになる。全体の検査が要る場面（CLI・準備完了レポートなど）が、
 * 明示的に呼ぶ。評価の覚え書き（SemanticCheckSession）を1つ共有するため、時間は文書数に比例する。
 */
object WorkspaceCheckRunner {

    /** スキーマを、検査を配るのに要る情報の形へ写す（検査は同じものをそのまま渡す）。 */
    fun checkSchemaOf(schema: WorkspaceSchema): CheckSchema =
        CheckSchema(schema.id, schema.kind, schema.match, schema.tree, schema.entities, schema.constraints, schema.checkers)

    /**
     * 索引に載っている文書（意味解析の走査結果を持つ文書）ごとの、定義体の検査の結果。文書ID 順。
     * 指摘も保留も無い文書は含めない。
     */
    fun deliverAll(loaded: LoadedWorkspace): Map<DocumentId, CheckDelivery> {
        val semantic = loaded.derived.semantic
        val checkSchemas = loaded.schemas.map(::checkSchemaOf)
        val schemaById = checkSchemas.associateBy { it.id }
        val documents = loaded.snapshot.documents
        val checks = WorkspaceChecks(
            checkSchemas, loaded.declarations.relations, loaded.declarations.enforcement,
            semantic.ids, semantic.refs, semantic.graph,
            SemanticCheckSession(semantic.ids, semantic.refs, semantic.graph),
            rel = { it.value },
            tree = { d -> documents[d]?.document?.documents?.firstOrNull() },
        )
        val out = LinkedHashMap<DocumentId, CheckDelivery>()
        for (scan in semantic.scans.sortedBy { it.document.value }) {
            val document = documents[scan.document] ?: continue
            val root = document.document.documents.firstOrNull() ?: continue
            val delivery = checks.deliver(scan.document, document.schemaId?.let(schemaById::get), root, scan.ids, scan.refs)
            if (delivery.issues.isNotEmpty() || delivery.withheld.isNotEmpty()) out[scan.document] = delivery
        }
        return out
    }
}
