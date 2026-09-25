package dev.toros.yde.workspace.validate

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.DocumentScan
import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.semantic.ScannedRef
import dev.toros.yde.semantic.SemanticCheckSession
import dev.toros.yde.semantic.SemanticDocument
import dev.toros.yde.semantic.SemanticEngine
import dev.toros.yde.semantic.SemanticEntityGraph
import dev.toros.yde.semantic.SemanticIssue
import dev.toros.yde.semantic.SemanticScanner
import dev.toros.yde.workspace.WorkspaceSchema
import dev.toros.yde.yaml.validate.Severity

/** 文書1つの検証の結果（カーネルの座標）: 指摘と、判定を保留した検査。 */
data class DocumentValidation(val issues: List<CheckIssue>, val withheld: List<WithheldCheck>)

/**
 * 文書1つの検証の全体（P27 段D の D-b で、root の Validator から移した。意図的差分 D-34）。アプリ（root の Validator は、座標を写して
 * ここを呼ぶだけ）・カーネル側の CLI・変更セットの適用が、同じ組み立てで検証する。指摘の並び:
 * 1. 構造の検証（利用者が書いたスキーマから導出した、最小の構造のスキーマで行う。P27 段C の C-1）
 * 2. ID の重複（この文書の中）と、参照の解決（出現の供給は「索引の他の文書の分＋この文書のいまの内容」の二層。architecture/index-references.md「索引の二層」）
 * 3. エンティティ型の宣言に関わる点検 → 宣言のない関係名（グラフ整合検証）→ 定義体の検査 → テンプレートの健全性 → 直せる場所への案内
 *    （WorkspaceChecks.deliver。索引に載っている文書だけ）
 * どの指摘にも出どころ（IssueSource）が付く。判定を保留した検査は、指摘にせず withheld に残す。
 *
 * ids / refs / graph は、ワークスペースの走査結果の一式（未保存の編集や、適用後の姿を重ねたものでもよい）。
 * tree は、この検証で読む、他の文書の中身（検証中の文書は、渡された root をそのまま使う。検証1回の中では、同じ文書を1回だけ読む）。
 */
class DocumentValidator(
    /** ワークスペースの全スキーマ（空なら、この文書のスキーマだけで判定する）。 */
    private val schemas: List<WorkspaceSchema>,
    private val relations: Map<String, RelationDef>,
    private val enforcement: EnforcementDef,
    private val ids: Map<String, List<ScannedId>>,
    private val refs: List<ScannedRef>,
    private val graph: SemanticEntityGraph,
    /** 検査の評価の覚え書き（同じ走査結果の上の検証で共有してよいのは、tree の中身が走査結果と同じ内容のときだけ）。 */
    private val session: SemanticCheckSession,
    /** 宣言のない関係名（グラフ整合検証）の、ワークスペース全体の指摘。走査結果の一式につき1回だけ求めたものを返す。 */
    private val integrity: () -> Map<DocumentId, List<SemanticIssue>>,
    /** 文書のワークスペース相対パス。 */
    private val rel: (DocumentId) -> String,
    private val tree: (DocumentId) -> YNode?,
) {
    /**
     * document はこの文書、schema はこの文書のスキーマ、root はいまの内容（パースできなかったときは null と parseError）。
     * documentId は、この文書の論理ID（`_document.id`。無ければ、走査結果と同じ決め方の代わりの値）。
     * indexed は、この文書が索引（台帳）に載っているか（R13-03）。台帳の外の文書では、索引を前提にした判定が成り立たないため、
     * 相手の見つからない参照を情報レベルへ降格し、ワークスペースの他の文書も見る検査を行わない。
     * scan は、root と同じ内容を走査した結果を、呼び出し側が既に持っているときに渡す（走査し直さない）。
     */
    fun validate(
        document: DocumentId,
        schema: WorkspaceSchema?,
        root: YNode?,
        parseError: String?,
        documentId: String,
        indexed: Boolean = true,
        scan: DocumentScan? = null,
    ): DocumentValidation {
        if (root == null) {
            val source = CodedChecks.source(CodedChecks.YAML, "parse-error", IssueDelivery.REPORT, emptyList(), emptyList())
            return DocumentValidation(
                listOf(CheckIssue(Severity.ERROR, "YAML パースエラー: ${parseError ?: "不明"}", emptyList(), source = source)), emptyList(),
            )
        }
        val issues = mutableListOf<CheckIssue>()
        val withheld = mutableListOf<WithheldCheck>()

        // 0) この文書のいまの内容の走査（ID と参照）。指摘の出どころの「対象の要素」（指摘の場所を含む最も内側の ID を持つ要素）も
        //    ここから決める（コードで実装された検査の出どころ＝CodedChecks。意図的差分 D-29）
        val docScan = scan ?: SemanticScanner.scan(
            SemanticDocument(
                id = document, root = root, schema = schema?.tree, schemaId = schema?.id,
                entities = schema?.entities ?: emptyList(), documentId = documentId,
            )
        ).let { (scannedIds, scannedRefs) -> DocumentScan(document, documentId, scannedIds, scannedRefs) }

        // 1) 構造の検証: 型・必須・選択肢・値の形式的なチェック・cases・未定義キー・フェーズの申告
        schema?.let { s ->
            issues += s.structures.validate(root).map {
                CheckIssue(it.severity, it.message, it.path, source = CodedChecks.structural(it, docScan.ids))
            }
        }

        // 2) ID と参照の検証（判定の本体は SemanticEngine。台帳の外の降格と、保存前の文書が自分自身を指す参照も、そこで判定する）
        issues += SemanticEngine.duplicateIdIssues(docScan).map { it.toCheckIssue(docScan.ids) }
        issues += SemanticEngine.referenceIssues(
            refs = docScan.refs,
            ownDocumentId = documentId,
            occurrences = { target -> docScan.ids.filter { it.id == target } + ids[target].orEmpty() },
            // その論理ID（`_document.id` か相対パス）の文書が、ワークスペースにあるか
            hasDocumentId = { graph.documents.containsKey(it) },
            indexed = indexed,
        ).map { it.toCheckIssue(docScan.ids) }

        // 3) ワークスペースの他の文書も見る検査（索引に載っている文書だけ）。宣言のない関係名の指摘は、今までどおり、
        //    エンティティ型の点検の後ろ・定義体の検査の前に並べる
        if (indexed) {
            val trees = HashMap<DocumentId, YNode?>()
            val checks = WorkspaceChecks(
                schemas, relations, enforcement, ids, refs, graph, session, rel,
                tree = { d -> if (d == document) root else trees.getOrPut(d) { tree(d) } },
            )
            val delivery = checks.deliver(document, schema, root, docScan.ids, docScan.refs)
            val (entityLints, rest) = delivery.issues.partition { it.source.coded && it.source.rule?.at == CodedChecks.ENTITY }
            issues += entityLints
            issues += integrity()[document].orEmpty().map { it.toCheckIssue(docScan.ids) }
            issues += rest
            withheld += delivery.withheld
        }
        // 同じ指摘の重複を除く（出どころは見ない——文面・場所・対応が同じなら、利用者には同じ指摘）
        return DocumentValidation(
            issues.distinctBy { listOf(it.severity, it.message, it.path, it.responses, it.relatedIds) },
            withheld.distinct(),
        )
    }

    private fun SemanticIssue.toCheckIssue(localIds: List<ScannedId>): CheckIssue =
        CheckIssue(severity, message, path, source = CodedChecks.semantic(this, localIds))
}
