package validate

import model.DocumentId
import model.YNode
import model.YPath
import model.pretty
import schema.CheckerRule
import schema.ConstraintExpect
import schema.ConstraintRule
import schema.CoverageLevel
import schema.EnforcementDef
import schema.EntityDecl
import schema.QueryDir
import schema.RelationDef
import schema.ResponseDecl
import schema.SchemaKind
import schema.SchemaTree
import semantic.CheckOutcome
import semantic.CheckReason
import semantic.ScannedId
import semantic.ScannedRef
import semantic.SemanticCheckSession
import semantic.SemanticCheckSide
import semantic.SemanticCheckViolation
import semantic.SemanticCheckerFinding
import semantic.SemanticEntityGraph
import semantic.SemanticExpression
import semantic.SemanticQuery
import semantic.SemanticQueryRow
import semantic.SemanticRuleResult
import semantic.SemanticValueInput
import semantic.phaseActive
import workspace.CheckCatalog
import workspace.WorkspaceSchema

/** 指摘の配り方（P27 段A）: 同じ違反が、どの立場でこの場所に出ているか。 */
enum class IssueDelivery {
    /** 報告先（対象の要素を定義した場所）への指摘。違反1件につき1つ。 */
    REPORT,

    /** 同じ要素の、他の文書の出現への案内（報告先の指摘の写し）。 */
    NOTICE,

    /** 直せる場所への案内（検査の宣言 fixSite）。 */
    FIX_SITE,

    /** 値が書かれた場所への指摘（検査の宣言 reportAt）。 */
    VALUE,

    /** 参照が書かれた場所への指摘（検査の宣言 reportAt の ref・専用チェッカー）。 */
    REF,
}

/**
 * 指摘の出どころ（P27 段A。BACKLOG UX-114）: どの検査の・どの要素についての指摘かを、文面とは別にデータとして持つ。
 * - rule: 検査の出どころ（どの文書の・どの書き方から展開された・何番目の検査か）。単体でパースした検査は null
 * - ruleId: 検査の呼び名（利用者が付けた id。無ければ null）
 * - kind: 専用チェッカーの指摘の種類（unreachable など。クエリの検査は null）
 * - subjectId: 対象の要素（検査の起点）
 * - evidence: 根拠に関わった要素の ID（対象の要素を除く・ID 昇順・全件。relatedIds は表示用に先頭だけを持つ）
 * - within: 対象の要素の中の場所（配列の番号を除いたキーの並び。コードで実装された検査の指摘だけが持つ）
 * 構造の検証・参照の解決など、定義体を通らない指摘（コードで実装された検査）の出どころは CodedChecks が作る
 * （検査の出どころの owner が `(code)`。P27 段B の残り・意図的差分 D-29）。
 */
data class IssueSource(
    val rule: schema.RuleOrigin?,
    val ruleId: String?,
    val kind: String?,
    val subjectId: String?,
    val delivery: IssueDelivery,
    val evidence: List<String> = emptyList(),
    val within: String? = null,
) {
    /** コードで実装された検査（定義体を通らない指摘）か。 */
    val coded: Boolean get() = rule?.owner == CodedChecks.OWNER

    /**
     * 「同じ検査の・同じ要素についての・同じ配り方の指摘」を見分けるキー。場所の番号と文面は含めない——要素の挿入で場所の番号が
     * ずれても、文面の件数が変わっても、同じ指摘として扱える（提出ゲート no-new-errors が使う）。出どころの分からない検査は null。
     */
    val identity: String?
        get() = rule?.let { "${it.key}|${kind.orEmpty()}|${subjectId.orEmpty()}|$delivery" + (within?.let { w -> "|$w" } ?: "") }
}

/**
 * 判定を保留した検査1件（P27 段A。BACKLOG UX-113）: データの不備（相手の見つからない参照など）のために、
 * 充足とも違反とも決められなかった「検査 × 要素」。指摘ではない——検証パネルには出さず、準備完了レポートと CLI が件数と内訳を示す。
 * 集める文書は1つに決まる: 報告先へ出す検査と、参照の場所へ出す検査は、対象の要素の報告先（対象の要素を定義した場所）の文書で。
 * 値の場所へ出す検査は、その値が書かれた文書で（path は値の場所）。
 */
data class WithheldCheck(
    val rule: schema.RuleOrigin?,
    val ruleId: String?,
    val description: String?,
    /** 専用チェッカーの指摘の種類（クエリの検査は null）。 */
    val kind: String?,
    val subjectId: String,
    val path: YPath,
    val reason: CheckReason,
) {
    /** 表示用の1行。 */
    val text: String
        get() = "ID「$subjectId」の検査を保留しました: " + (description ?: ruleId ?: rule?.at ?: "（名前のない検査）") +
            (kind?.let { "（$it）" } ?: "") + " — ${reason.text}"
}

/**
 * エラー時の対応（SP-09）の、カーネルの座標（DocumentId）での形。対応の種類は閉じた一覧で、どれを・どのラベルで示すかは
 * 検査の定義（YAML）が宣言する。アプリ側は、これをファイルの座標（Path）の IssueResponse へ写す。
 */
sealed interface CheckResponse {
    val label: String

    /** その場所へ移動する。path が無ければ文書を開く。note はメニューでラベルの下に添える補足。 */
    data class Jump(override val label: String, val document: DocumentId, val path: YPath?, val note: String? = null) : CheckResponse

    /** 値を、指摘の場所へ書き込む。 */
    data class ApplyValue(override val label: String, val value: String) : CheckResponse

    /** この ID の詳細文書を、指定のスキーマの置き場所に新しく作って開く。 */
    data class CreateDetail(override val label: String, val schemaId: String, val id: String, val note: String? = null) : CheckResponse
}

/**
 * 文書へ配る指摘1件（場所は、配り先の文書の中のパス）。定義体の検査から出た指摘と、コードで実装された検査のうち
 * ワークスペースの他の文書も見るもの（CodedDocumentChecks。出どころの owner が `(code)`）の指摘。
 */
data class CheckIssue(
    val severity: Severity,
    val message: String,
    val path: YPath,
    val responses: List<CheckResponse> = emptyList(),
    val relatedIds: List<String> = emptyList(),
    val source: IssueSource,
)

/**
 * 文書1つへ配られた検査の結果: 指摘と、判定を保留した検査。指摘の並びは、エンティティ型の宣言に関わる点検 → 定義体の検査
 * （報告先・値と参照の場所・他の出現への案内）→ テンプレートの健全性 → 直せる場所への案内。
 */
data class CheckDelivery(val issues: List<CheckIssue>, val withheld: List<WithheldCheck>)

/**
 * 検査の状況1件（SP-08。`status` を宣言した検査×ID。右パネルのカバレッジ・ID 横ぐし・ビューの状態列・エージェントの related が共用）。
 * - active: activeFrom のフェーズに達しているか（false は指摘にせず「フェーズ未到達」として表示する）
 * - outcome / reason: 検査の結果（違反・充足・保留・対象外）と、保留・対象外の理由（P27 段A）
 * - satisfied: 検査を満たしているか（結果が「充足」。保留は充足に数えない＝意図的差分 D-26 ③）
 * - summary: 状況の1行（検査の status のひな型に、いまの値を埋めたもの。例「出現 1 件（必要 1 件）」。評価できなければ null）
 * - message: 満たしていないときの指摘の文面（検証パネルに出るものと同じ）
 */
data class RuleStatus(
    val rule: ConstraintRule,
    val id: String,
    val active: Boolean,
    val outcome: CheckOutcome,
    val reason: CheckReason?,
    val summary: String?,
    val message: String?,
) {
    val satisfied: Boolean get() = outcome == CheckOutcome.SATISFIED

    /** 確かめられなかった（保留）か。 */
    val withheld: Boolean get() = outcome == CheckOutcome.WITHHELD

    /** 充足とも不足とも言えない（保留・対象外）か。UI は充足・不足と別の見せ方にする。 */
    val undetermined: Boolean get() = outcome == CheckOutcome.WITHHELD || outcome == CheckOutcome.NOT_APPLICABLE

    /** 表示用の1行（UI とエージェントで共用）。 */
    val text: String
        get() {
            val note = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
            return when {
                !active -> "フェーズ未到達（${rule.activeFrom} から判定" + (summary?.let { "。現在 $it" } ?: "") + "）" + note
                satisfied -> (summary ?: "充足") + note
                withheld -> "判定保留（${reason?.text ?: "確かめられませんでした"}）" + note
                outcome == CheckOutcome.NOT_APPLICABLE -> "対象外（${reason?.text ?: "確かめるものがありません"}）" + note
                else -> message ?: ((summary ?: "不足") + note)
            }
        }
}

/**
 * 定義体の検査を評価して、指摘を文書へ配る（P27 段B-3）。以前は root の Validator と RuleDiagnostics が持っていた
 * 配り方の規則と文面の組み立てを、カーネルの座標（DocumentId）でここへ移した——アプリとカーネル側の CLI が同じ結果を返す。
 *
 * 配り方の規則（検査 × 対象の要素の「違反」を、どの文書のどこへ出すか）:
 * - 報告先（REPORT）: 対象の要素の報告先の文書の検証でだけ報告する（多重に報告しない）。スキーマの検査は、その検査を宣言した
 *   スキーマの文書の中で決める（定義出現がそのスキーマの文書にあればそこ、なければ先頭の出現。無ければ報告しない）。
 *   特定のスキーマに属さない検査（関係レジストリ・kind: library・組込み定義体に直接書いた検査）は、定義出現（なければ先頭の出現）
 * - 他の出現への案内（NOTICE）: 報告先が他の文書にある要素の出現に、報告先で出ている指摘を案内する（警告級は INFO・エラー級は同格の ERROR）
 * - 値の場所（VALUE）・参照の場所（REF）: 検査の宣言 reportAt に従い、この文書に書かれた値・参照の場所へ指摘する
 * - 直せる場所への案内（FIX_SITE）: この文書のスキーマが検査の「書き足し先」なら、違反している要素の一覧を案内する（INFO）
 * コードで実装された検査のうち、ワークスペースの他の文書も見るもの（ID のパターンからの逸脱・重複所有・テンプレートの健全性）も、
 * ここが一緒に返す（実体は CodedDocumentChecks。P27 段D の D-a・意図的差分 D-33）。
 * 判定そのもの（違反・充足・保留・対象外）は semantic.SemanticRules、その覚え書きは SemanticCheckSession。
 * tree は、この検証で読む文書の中身（検証中の文書は、呼び出し側がいまの内容を返す）。
 */
class WorkspaceChecks(
    /** ワークスペースの全スキーマ（他の出現への案内・直せる場所への案内・対応の導出に使う。空なら自スキーマの報告だけ）。 */
    private val schemas: List<WorkspaceSchema>,
    private val relations: Map<String, RelationDef>,
    private val enforcement: EnforcementDef,
    private val ids: Map<String, List<ScannedId>>,
    private val refs: List<ScannedRef>,
    private val graph: SemanticEntityGraph,
    private val session: SemanticCheckSession,
    /** 文書のワークスペース相対パス。 */
    private val rel: (DocumentId) -> String,
    private val tree: (DocumentId) -> YNode?,
    /** 特定のスキーマに属さない検査のうち、組込み定義体に直接書かれたもの。 */
    private val builtinRules: List<ConstraintRule> = CheckCatalog.builtinRules,
    private val builtinCheckers: List<CheckerRule> = CheckCatalog.builtinCheckers,
) {
    private class RuleGroup<R>(val key: String, val owner: WorkspaceSchema?, val rules: List<R>)

    /** コードで実装された検査のうち、ワークスペースの他の文書も見るもの。 */
    private val coded = CodedDocumentChecks(schemas, ids, rel, tree)

    // 特定のスキーマに属さない検査: 組込み定義体に直接書いた検査と、kind: relations / library の文書の検査
    private val sharedRuleGroups: List<RuleGroup<ConstraintRule>> =
        listOf(RuleGroup(CheckCatalog.BUILTIN_OWNER, null, builtinRules)) +
            schemas.filter { it.kind != SchemaKind.DOCUMENT }.map { RuleGroup(it.id, null, it.constraints) }
    private val sharedRules: List<ConstraintRule> = sharedRuleGroups.flatMap { it.rules }

    // 検査の報告先と活性を決める単位: スキーマの検査（owner あり）と、特定のスキーマに属さない検査（owner なし）
    private val ruleGroups: List<RuleGroup<ConstraintRule>> =
        schemas.filter { it.kind == SchemaKind.DOCUMENT }.map { RuleGroup(it.id, it, it.constraints) } + sharedRuleGroups
    private val checkerGroups: List<RuleGroup<CheckerRule>> =
        schemas.filter { it.kind == SchemaKind.DOCUMENT }.map { RuleGroup(it.id, it, it.checkers) } +
            listOf(RuleGroup(CheckCatalog.BUILTIN_OWNER, null, builtinCheckers)) +
            schemas.filter { it.kind != SchemaKind.DOCUMENT }.map { RuleGroup(it.id, null, it.checkers) }

    /** 重大度の切り替え（検査の宣言 levelFrom）: 関係レジストリの強制スイッチが宣言されていれば、その重大度。 */
    private fun levelOf(rule: ConstraintRule): CoverageLevel = rule.levelFrom?.let { enforcement.levelOf(it) } ?: rule.level
    private fun levelOf(rule: CheckerRule): CoverageLevel = rule.levelFrom?.let { enforcement.levelOf(it) } ?: rule.level

    /**
     * 検査の報告先（意図的差分 D-21 ④）。スキーマの検査（owner = 検査を宣言したスキーマ）は、定義出現がそのスキーマの文書に
     * あればそこ、なければそのスキーマの文書の先頭の出現（そのスキーマの文書に出現がなければ null＝報告しない）。
     * 特定のスキーマに属さない検査（owner = null）は、定義出現（なければ先頭の出現）。
     */
    fun reportAnchorOf(id: String, ownerSchemaId: String?): ScannedId? {
        val node = graph.entities[id] ?: return null
        if (ownerSchemaId == null) return node.defining ?: node.occurrences.firstOrNull()
        return node.defining?.takeIf { it.schemaId == ownerSchemaId } ?: node.occurrences.firstOrNull { it.schemaId == ownerSchemaId }
    }

    /**
     * 文書1つへ配る。document はこの文書、schema はこの文書のスキーマ、root はいまの内容、localIds / localRefs は、いまの内容を
     * 走査して得た ID と参照（未保存の編集を含む「見たまま」）。
     */
    fun deliver(
        document: DocumentId,
        schema: WorkspaceSchema?,
        root: YNode,
        localIds: List<ScannedId>,
        localRefs: List<ScannedRef>,
    ): CheckDelivery {
        val issues = mutableListOf<CheckIssue>()
        val withheld = mutableListOf<WithheldCheck>()
        val localIdSet = localIds.map { it.id }.toSet()

        // エンティティ型の宣言に関わる点検（ID のパターンからの逸脱・重複所有。コードで実装された検査）
        issues += coded.entityIssues(document, schema, root, localIds)

        // 報告先: 規則はこの文書のスキーマの宣言・activeFrom はこの文書のフェーズで判定する（R29-03・CALC-14）
        if (schema != null) {
            val phaseOrder = schema.tree.phases?.order.orEmpty()
            val phaseIndex = schema.tree.currentPhaseIndex(root)
            fun anchoredFor(ownerSchemaId: String?) = localIds.filter { entry ->
                val anchor = reportAnchorOf(entry.id, ownerSchemaId)
                anchor != null && anchor.document == document && anchor.path == entry.path
            }
            val anchored = anchoredFor(null)
            val anchoredInSchema = anchoredFor(schema.id)
            for ((rule, entries) in schema.constraints.map { it to anchoredInSchema } + sharedRules.map { it to anchored }) {
                if (!phaseActive(rule.activeFrom, phaseOrder, phaseIndex)) continue
                if (rule.reportAt?.ref != null) {
                    // 参照の場所へ出す検査（対象型の不一致など）: 見つかった解ごとに、この文書に書かれた参照の場所へ指摘する
                    issues += refIssues(rule, document, localIds.map { it.id }, localRefs.map { it.targetId })
                    // 保留は、対象の要素の報告先の文書で集める（参照が書かれた文書ごとに重ねて数えない。意図的差分 D-29 ③）
                    for (entry in entries) {
                        result(rule, entry.id).takeIf { it.outcome == CheckOutcome.WITHHELD }?.reason?.let { reason ->
                            withheld += WithheldCheck(rule.origin, rule.id, rule.description, null, entry.id, entry.path, reason)
                        }
                    }
                    continue
                }
                rule.reportAt?.let { reportAt ->
                    // 値の場所へ出す検査（整合検証など）: この文書に書かれた要素ごとに、その値だけを調べて値の場所へ指摘する。
                    // 保留も、その値が書かれた文書で集める（意図的差分 D-29 ③）
                    for (entry in localIds) {
                        val pin = reportAt.source to SemanticExpression.Occurrence(document, entry.path)
                        val pinned = session.pinnedResult(rule, entry.id, pin, rel, tree)
                        issues += valueIssues(rule, entry.id, entry.path, pinned)
                        pinned.takeIf { it.outcome == CheckOutcome.WITHHELD }?.reason?.let { reason ->
                            withheld += WithheldCheck(rule.origin, rule.id, rule.description, null, entry.id, entry.path + reportAt.path, reason)
                        }
                    }
                }
                if (rule.reportAt != null) continue
                for (entry in entries) {
                    constraint(rule, entry.id)?.let { issues += it.reportIssue(entry.path) }
                    result(rule, entry.id).takeIf { it.outcome == CheckOutcome.WITHHELD }?.reason?.let { reason ->
                        withheld += WithheldCheck(rule.origin, rule.id, rule.description, null, entry.id, entry.path, reason)
                    }
                }
            }
            // 専用チェッカーを使う検査（遷移検証など）: 報告先の決め方はクエリの検査と同じ（意図的差分 D-23 ①）
            val ownCheckers = schema.checkers.map { it to anchoredInSchema }
            val sharedCheckers = checkerGroups.filter { it.owner == null }.flatMap { it.rules }.map { it to anchored }
            for ((rule, entries) in ownCheckers + sharedCheckers) {
                if (!phaseActive(rule.activeFrom, phaseOrder, phaseIndex)) continue
                for (entry in entries) {
                    checker(rule, entry.id).forEach { (_, d) -> issues += d.reportIssue(entry.path) }
                    checkerWithheld(rule, entry.id).forEach { (kind, reason) ->
                        withheld += WithheldCheck(rule.origin, rule.id, rule.description, kind, entry.id, entry.path, reason)
                    }
                }
            }
        }
        // 参照が書かれた場所へ報告するチェッカー: この文書に書かれた参照への指摘
        for (group in checkerGroups) {
            for (rule in group.rules) issues += checkerRefIssues(rule, document)
        }
        // 他の出現への案内（R28-05・UX-96）: 指摘は報告先にだけ出るため、他の出現の文書しか開いていないと気づけない。
        // 報告先が他の文書にある要素の出現には、報告先で出ている指摘を案内する
        // （規則と activeFrom は、報告先の文書のスキーマ・フェーズで判定する）
        val noticed = mutableSetOf<Pair<String, String>>()  // (検査のキー, ID) の重複を防ぐ
        for (entry in localIds) {
            for (group in ruleGroups) {
                if (group.rules.isEmpty()) continue
                val anchor = reportAnchorOf(entry.id, group.owner?.id) ?: continue
                if (anchor.document == document) continue  // 報告先がこの文書なら、上で報告済み
                val anchorRel = rel(anchor.document)
                val anchorPhase = group.owner?.tree?.currentPhaseIndex(tree(anchor.document))
                for ((i, rule) in group.rules.withIndex()) {
                    if (rule.reportAt != null) continue
                    if (!phaseActive(rule.activeFrom, group.owner?.tree?.phases?.order.orEmpty(), anchorPhase)) continue
                    val d = constraint(rule, entry.id) ?: continue
                    if (noticed.add("${group.key}/c$i" to entry.id)) issues += d.noticeIssue(entry.path, anchorRel)
                }
            }
            for (group in checkerGroups) {
                if (group.rules.isEmpty()) continue
                val anchor = reportAnchorOf(entry.id, group.owner?.id) ?: continue
                if (anchor.document == document) continue
                val anchorRel = rel(anchor.document)
                val anchorPhase = group.owner?.tree?.currentPhaseIndex(tree(anchor.document))
                for ((i, rule) in group.rules.withIndex()) {
                    if (!phaseActive(rule.activeFrom, group.owner?.tree?.phases?.order.orEmpty(), anchorPhase)) continue
                    for ((kind, d) in checker(rule, entry.id)) {
                        if (noticed.add("${group.key}/k$i:$kind" to entry.id)) issues += d.noticeIssue(entry.path, anchorRel)
                    }
                }
            }
        }
        // テンプレートの健全性（コードで実装された検査）: 直せる場所への案内の前に並べる
        issues += coded.templateIssues(document, schema, root, localIds)

        // 直せる場所への案内（SP-08。検査の宣言 fixSite）: この文書のスキーマが「書き足し先」に指定されている検査について、
        // 指摘が出ている ID の一覧を案内する（INFO）。この文書に既に書かれている ID（未保存の追加を含む）は載せない
        if (schema != null) {
            for (group in ruleGroups) {
                for (rule in group.rules) {
                    val fixSite = rule.fixSite ?: continue
                    if (schema.id !in fixSite.schemas) continue
                    // 違反している要素の一覧は検査ごとに1回だけ求める
                    val missing = session.memo(rule, "fixSiteIds") {
                        graph.entities.keys.sorted().filter { id ->
                            val anchor = reportAnchorOf(id, group.owner?.id) ?: return@filter false
                            val anchorPhase = group.owner?.tree?.currentPhaseIndex(tree(anchor.document))
                            phaseActive(rule.activeFrom, group.owner?.tree?.phases?.order.orEmpty(), anchorPhase) &&
                                constraint(rule, id) != null
                        }
                    }.filter { it !in localIdSet }
                    if (missing.isEmpty()) continue
                    val shown = missing.take(5).joinToString(", ") + (if (missing.size > 5) " 他${missing.size - 5}件" else "")
                    issues += CheckIssue(
                        Severity.INFO,
                        fixSite.message.replace("{count}", missing.size.toString()).replace("{ids}", shown),
                        emptyList(), relatedIds = missing,
                        source = IssueSource(rule.origin, rule.id, null, null, IssueDelivery.FIX_SITE, missing),
                    )
                }
            }
        }
        return CheckDelivery(issues, withheld.distinct())
    }

    /**
     * ID 1件の「検査の状況」（SP-08）: このスキーマの検査のうち `status` を宣言したものについて、満たしているか・不足しているか・
     * 保留か・フェーズ未到達かを、いまの値つきで返す。definingRoot は、この ID がこのスキーマの文書に書かれている、その文書のルート
     * （activeFrom のフェーズ判定に使う。null なら常時判定）。
     */
    fun statuses(id: String, schema: WorkspaceSchema, definingRoot: YNode?): List<RuleStatus> {
        val phaseIndex = definingRoot?.let { schema.tree.currentPhaseIndex(it) }
        return schema.constraints.filter { it.status != null }.mapNotNull { rule ->
            status(rule, id, phaseActive(rule.activeFrom, schema.tree.phases?.order.orEmpty(), phaseIndex))
        }
    }

    // ---------------------------------------------------------------- 診断（違反の根拠を、指摘の文面へ写す）

    /**
     * 検査 × 対象の要素の診断1件（R29-06）。判定は1回だけで、結果を2つの配り先へ写す:
     * 報告先＝重大度は検査の level／他の出現＝エラー級は同格の ERROR、警告級は INFO の案内に留める。
     */
    private inner class Diagnosis(
        val subjectId: String,
        val level: CoverageLevel,
        val report: String,
        val notice: (anchorRel: String, escalated: Boolean) -> String,
        /** 根拠に関与したID（報告先のジャンプ導線。対象の要素自身は含めない）。 */
        val related: List<String>,
        val description: String?,
        val responses: List<CheckResponse> = emptyList(),
        val rule: schema.RuleOrigin? = null,
        val ruleId: String? = null,
        val kind: String? = null,
        val evidence: List<String> = emptyList(),
    ) {
        private val suffix: String get() = description?.let { " — $it" } ?: ""
        private fun source(delivery: IssueDelivery) = IssueSource(rule, ruleId, kind, subjectId, delivery, evidence)

        fun reportIssue(path: YPath): CheckIssue =
            CheckIssue(level.toSeverity(), report + suffix, path, responses, related, source(IssueDelivery.REPORT))

        fun noticeIssue(path: YPath, anchorRel: String): CheckIssue {
            val escalated = level == CoverageLevel.ERROR
            return CheckIssue(
                if (escalated) Severity.ERROR else Severity.INFO,
                notice(anchorRel, escalated) + suffix,
                path,
                // 他の出現では対象の要素の定義へ戻れるよう、対象の ID を先頭に加える
                relatedIds = (listOf(subjectId) + related).distinct().take(10),
                source = source(IssueDelivery.NOTICE),
            )
        }
    }

    private fun result(rule: ConstraintRule, id: String): SemanticRuleResult = session.result(rule, id, rel, tree)

    /** 根拠に関わった要素（解の束縛と、比べた値の要素）の ID。対象の要素を除き、ID 昇順で全件。 */
    private fun evidenceOf(id: String, rows: List<SemanticQueryRow>, violations: List<SemanticCheckViolation> = emptyList()): List<String> =
        (rows.flatMap { row -> row.bindings.values.map { it.id } } + violations.flatMap { listOfNotNull(it.left.id, it.right.id) })
            .distinct().filter { it != id }.sorted()

    /** クエリの検査の診断: 結果が「違反」のときだけ診断を返す（充足・保留・対象外は null）。 */
    private fun constraint(rule: ConstraintRule, id: String): Diagnosis? {
        val outcome = result(rule, id)
        if (outcome.outcome != CheckOutcome.VIOLATED) return null
        fun notice(evidence: String): (String, Boolean) -> String = { anchorRel, escalated ->
            (if (escalated) "ID「$id」への制約違反があります（報告先: 定義出現 $anchorRel）"
            else "ID「$id」には制約の指摘があります（報告先: 定義出現 $anchorRel）") + evidence
        }
        // 文面のひな型（rule.message）を宣言した検査は、報告先にも他の出現にも、その文面を出す
        fun customNotice(text: String): (String, Boolean) -> String = { anchorRel, _ -> "$text（報告先: 定義出現 $anchorRel）" }
        fun diagnosis(
            report: String,
            notice: (String, Boolean) -> String,
            related: List<String>,
            evidence: List<String>,
            v: SemanticCheckViolation? = null,
        ) = Diagnosis(
            id, levelOf(rule), report, notice, related,
            description = rule.description ?: rule.id,
            responses = responsesOf(rule, id, v),
            rule = rule.origin, ruleId = rule.id, evidence = evidence,
        )
        if (rule.check.isNotEmpty()) {
            // check 判定述語（R28-08。CORE-CALCULUS §8）: 条件を満たさない値の組（反例）を列挙する
            val examples = outcome.violations
            val evidence = "。反例: ${describeCheckExamples(examples)}"
            val custom = rule.message?.let { template ->
                renderTemplate(template, id, rule = rule, row = examples.first().row, v = examples.first()) +
                    (if (examples.size > 1) "（ほか${examples.size - 1}組）" else "")
            }
            return diagnosis(
                report = custom ?: "ID「$id」を起点とする解が check を満たしません（${examples.size} 組）$evidence",
                notice = custom?.let(::customNotice) ?: notice(evidence),
                related = examples.take(3).flatMap { listOfNotNull(it.left.id, it.right.id) }.distinct().filter { it != id }.take(10),
                evidence = evidenceOf(id, examples.map { it.row }, examples),
                v = examples.first(),
            )
        }
        outcome.forAll?.let { found ->
            // 「すべての要素について」の判定（SP-04。網羅マトリクスなど）: 解のなかった要素を列挙して報告する
            val listed = found.missing.take(5).joinToString("・") { m -> m.title?.let { "${m.id}（$it）" } ?: m.id } +
                (if (found.missing.size > 5) " ほか${found.missing.size - 5}件" else "")
            val text = rule.message?.let {
                renderTemplate(it, id, rule = rule, count = found.missing.size).replace("{missing}", listed)
            } ?: "ID「$id」について、解のない要素があります（${found.missing.size} 件／全 ${found.total} 件）: $listed"
            return diagnosis(
                report = text,
                notice = customNotice(text),
                // 不足した要素へジャンプできるようにする（R28-05・UX-98）
                related = found.missing.map { it.id }.take(10),
                evidence = found.missing.map { it.id }.sorted(),
            )
        }
        val found = outcome.matches ?: return null
        // 違反時は該当の組（束縛の表）を列挙し、関連IDのジャンプ導線を付ける（R28-05・UX-98）
        val rows = found.rows.take(3).joinToString(" ／ ") { row ->
            row.bindings.entries.joinToString("・") { (b, ref) -> "$b=${ref.id}" }
        } + (if (found.total > 3) " ほか${found.total - 3}組" else "")
        val related = found.rows.take(3).flatMap { row -> row.bindings.values.map { it.id } }
            .distinct().filter { it != id }.take(10)
        val custom = rule.message?.let { renderTemplate(it, id, rule = rule, row = found.rows.firstOrNull(), count = found.total) }
        rule.count?.let { ruleCount ->
            val head = "ID「$id」を起点とするパターンの解が ${found.total} 件です（count: ${ruleCount.describe()}）"
            return diagnosis(
                report = custom ?: (head + (if (found.rows.isNotEmpty()) "。解の組: $rows" else "")),
                notice = custom?.let(::customNotice) ?: notice(if (found.rows.isNotEmpty()) "。該当の組: $rows" else ""),
                related = related,
                evidence = evidenceOf(id, found.rows),
            )
        }
        return when (rule.expect) {
            ConstraintExpect.EXISTS -> diagnosis(
                report = custom ?: "ID「$id」を起点とするパターンが成立していません（expect: exists）",
                notice = custom?.let(::customNotice) ?: notice(""),
                related = emptyList(),
                evidence = emptyList(),
            )
            ConstraintExpect.ABSENT -> {
                val evidence = if (found.rows.isNotEmpty()) "。該当の組: $rows" else ""
                diagnosis(
                    report = custom ?: "ID「$id」を起点とするパターンが成立しています（expect: absent）$evidence",
                    notice = custom?.let(::customNotice) ?: notice(evidence),
                    related = related,
                    evidence = evidenceOf(id, found.rows),
                )
            }
            null -> null
        }
    }

    /**
     * 検査の状況（SP-08。rule.status を宣言した検査）: 起点の母集団に入らない ID は null（一覧に出さない）。
     * 状況の1行（summary）は status のひな型へいまの値を埋めて作る（check の検査は、満たしているときも比べた値を埋める）。
     */
    private fun status(rule: ConstraintRule, id: String, active: Boolean): RuleStatus? {
        val template = rule.status ?: return null
        val outcome = result(rule, id)
        if (outcome.reason == CheckReason.NOT_IN_SCOPE) return null
        val diagnosis = constraint(rule, id)
        val summary = if (rule.check.isNotEmpty()) {
            val evaluations = SemanticQuery.checkEvaluationsFrom(ids, refs, graph, rule.spec, rule.check, id, rel, tree)
            // 不足している項目があればその値、なければ最初の項目の値を、状況の1行に出す
            (evaluations.firstOrNull { !it.satisfied } ?: evaluations.firstOrNull())?.let { e ->
                renderTemplate(template, id, rule = rule, v = SemanticCheckViolation(e.check, e.term, e.left, e.right, e.row))
            }
        } else {
            val found = SemanticQuery.matchesFrom(ids, refs, graph, rule.spec, id, tree)
            renderTemplate(template, id, rule = rule, row = found.rows.firstOrNull(), count = found.total)
        }
        return RuleStatus(
            rule = rule, id = id, active = active, outcome = outcome.outcome, reason = outcome.reason, summary = summary,
            message = diagnosis?.reportIssue(emptyList())?.message,
        )
    }

    /**
     * 値の場所へ指摘を出す検査（SP-08。rule.reportAt を宣言した check）: この文書に書かれた要素（occPath）について、
     * その要素の値だけを調べた結果（pinned）から、条件を満たさない組ごとに指摘を返す。指摘の場所は「要素のパス＋reportAt のパス」。
     */
    private fun valueIssues(rule: ConstraintRule, id: String, occPath: YPath, pinned: SemanticRuleResult): List<CheckIssue> {
        val reportAt = rule.reportAt ?: return emptyList()
        val violations = pinned.violations
        val suffix = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
        return violations.map { v ->
            val text = rule.message?.let { renderTemplate(it, id, rule = rule, v = v) }
                ?: "「${reportAt.path.pretty()}」の値が条件を満たしません: ${describeCheckExamples(listOf(v))}"
            CheckIssue(
                levelOf(rule).toSeverity(), text + suffix, occPath + reportAt.path, responses = responsesOf(rule, id, v),
                source = IssueSource(rule.origin, rule.id, null, id, IssueDelivery.VALUE, evidenceOf(id, listOf(v.row), listOf(v))),
            )
        }
    }

    /**
     * 参照の場所へ指摘を出す検査（SP-08。rule.reportAt.ref を宣言した検査。対象型の不一致など）: 見つかった解
     * （expect: absent）または条件を満たさなかった組（check）ごとに、束縛した参照が**この文書に書かれている**ものを、
     * その参照の場所へ指摘する。起点は全エンティティから探すが、参照が最初の edge で束縛されているときは、
     * この文書に関わる起点だけを調べる（出ていく参照ならこの文書に書かれた要素・入ってくる参照ならこの文書の参照の相手）。
     */
    private fun refIssues(rule: ConstraintRule, document: DocumentId, localIds: List<String>, localTargets: List<String>): List<CheckIssue> {
        val refBind = rule.reportAt?.ref ?: return emptyList()
        val firstEdge = rule.spec.hops.firstOrNull()?.edge
        val starts = when {
            firstEdge?.bind != refBind -> graph.entities.keys.sorted()
            firstEdge.dir == QueryDir.OUT -> localIds.distinct().sorted()
            else -> localTargets.distinct().sorted()
        }
        val suffix = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
        val issues = mutableListOf<CheckIssue>()
        for (id in starts) {
            val outcome = result(rule, id)
            if (outcome.outcome != CheckOutcome.VIOLATED) continue
            if (rule.check.isNotEmpty()) {
                for (v in outcome.violations) {
                    val at = v.row.refs[refBind]?.takeIf { it.document == document } ?: continue
                    val text = rule.message?.let { renderTemplate(it, id, rule = rule, row = v.row, v = v) }
                        ?: "参照「${at.targetId}」が条件を満たしません: ${describeCheckExamples(listOf(v))}"
                    issues += CheckIssue(
                        levelOf(rule).toSeverity(), text + suffix, at.path, responses = responsesOf(rule, id, v),
                        source = IssueSource(rule.origin, rule.id, null, id, IssueDelivery.REF, evidenceOf(id, listOf(v.row), listOf(v))),
                    )
                }
            } else {
                for (row in outcome.matches?.rows.orEmpty()) {
                    val at = row.refs[refBind]?.takeIf { it.document == document } ?: continue
                    val text = rule.message?.let { renderTemplate(it, id, rule = rule, row = row) }
                        ?: "参照「${at.targetId}」は、あってはならないパターンに当たります（expect: absent）"
                    issues += CheckIssue(
                        levelOf(rule).toSeverity(), text + suffix, at.path, responses = responsesOf(rule, id, null),
                        source = IssueSource(rule.origin, rule.id, null, id, IssueDelivery.REF, evidenceOf(id, listOf(row))),
                    )
                }
            }
        }
        return issues
    }

    /**
     * 対応の宣言（rule.responses）を、具体的な対応へ写す。値の出どころへの移動と値の書き込みは、条件を満たさなかった組
     * （v）の値と出どころから作る。参照を書き足せそうな文書への移動（referenceSources）は、起点の要素と関係名から候補を導出する。
     */
    private fun responsesOf(rule: ConstraintRule, id: String, v: SemanticCheckViolation?): List<CheckResponse> =
        rule.responses.flatMap { decl ->
            if (decl.kind == ResponseDecl.Kind.CREATE) {
                // 文書を新しく作る: 起点の ID の詳細文書を、スキーマごとに1件（作るスキーマはラベルの {schema} に埋める）
                return@flatMap decl.schemas.map { sid ->
                    CheckResponse.CreateDetail(renderTemplate(decl.label, id, rule = rule, v = v, schemaId = sid), sid, id, decl.note)
                }
            }
            if (decl.target == ResponseDecl.REFERENCE_SOURCES) {
                return@flatMap referenceSources(decl.relation.orEmpty(), id).take(3).map { d ->
                    CheckResponse.Jump(renderTemplate(decl.label, id, v = v, document = d), d, path = null, note = decl.note)
                }
            }
            if (v == null) return@flatMap emptyList()
            val side = if (decl.target.startsWith("left")) v.left else v.right
            when {
                decl.kind == ResponseDecl.Kind.APPLY ->
                    listOf(CheckResponse.ApplyValue(renderTemplate(decl.label, id, v = v), side.text))
                decl.target.endsWith(".source") -> {
                    val document = side.sourceDocument
                    if (document == null) emptyList()
                    else listOf(CheckResponse.Jump(renderTemplate(decl.label, id, v = v), document, side.sourcePath, decl.note))
                }
                else -> side.inputs.mapNotNull { input ->
                    val document = input.document ?: return@mapNotNull null
                    CheckResponse.Jump(renderTemplate(decl.label, id, v = v, input = input), document, input.path, decl.note)
                }
            }
        }

    /**
     * 「参照の追加先候補」（UX-100）: targetId への relation の参照を書けそうな文書を導出する。
     * ① 関係の source 型を entity 宣言するスキーマ（追加先スキーマ）を特定する
     * ② 対象IDの定義ファイルに出現するエンティティが追加先スキーマの文書にも出現していれば、その文書
     *    （同一IDの詳細化パターン: 画面定義の項目 → 同じ画面IDの画面設計書。先頭3件）
     * ③ 見つからなければ、**台帳型**の追加先スキーマ（対象IDのファイルと同じエンティティ型を宣言しない
     *    ＝文書が対象ごとに分かれない）の文書がワークスペースに1つだけのとき、その文書
     *    （一覧・台帳パターン: 業務型 → 物理型マッピング表）。同じ型を宣言するスキーマ（同一IDの
     *    詳細化文書が対象ごとにある形）は、該当文書の未作成を別の文書で肩代わりさせないため対象外
     * どちらでもなければ黙る（複数テーブルのどれに実装するか等、機械が選べない場合は誤誘導しない）。
     */
    fun referenceSources(relation: String, targetId: String): List<DocumentId> {
        val def = relations[relation] ?: return emptyList()
        if (def.source.isEmpty() || schemas.isEmpty()) return emptyList()
        val sourceSchemaIds = schemas.filter { s -> s.entities.any { it.type in def.source } }.map { it.id }.toSet()
        if (sourceSchemaIds.isEmpty()) return emptyList()
        val target = graph.entities[targetId] ?: return emptyList()
        val targetDocument = (target.defining ?: target.occurrences.firstOrNull())?.document ?: return emptyList()
        val siblingIds = graph.documents.values.firstOrNull { it.document == targetDocument }?.entityIds.orEmpty()
        val cooccurring = siblingIds.asSequence()
            .flatMap { graph.entities[it]?.occurrences.orEmpty() }
            .filter { it.schemaId in sourceSchemaIds && it.document != targetDocument }
            .map { it.document }
            .distinct()
            .sortedBy { it.value }
            .take(3)
            .toList()
        if (cooccurring.isNotEmpty()) return cooccurring
        val siblingTypes = siblingIds.mapNotNull { graph.entities[it]?.entityType }.toSet()
        val registrySchemaIds = schemas
            .filter { s -> s.id in sourceSchemaIds && s.entities.none { it.type in siblingTypes } }
            .map { it.id }
            .toSet()
        if (registrySchemaIds.isEmpty()) return emptyList()
        val schemaDocuments = graph.documents.values.asSequence()
            .filter { doc ->
                doc.document != targetDocument && doc.entityIds.any { id ->
                    graph.entities[id]?.occurrences.orEmpty().any { it.document == doc.document && it.schemaId in registrySchemaIds }
                }
            }
            .map { it.document }
            .distinct()
            .toList()
        return if (schemaDocuments.size == 1) schemaDocuments else emptyList()
    }

    /**
     * 文面・ラベルのひな型の差し込み（1回の走査で置き換える＝差し込んだ値の中の波括弧は再解釈しない）。
     * 書けるのは `{id}`＝起点の ID・`{束縛名.id}` `{束縛名.type}` `{束縛名.title}`＝見つかった解のノード・`{count}`＝解の件数・
     * `{left}` `{right}` `{left.raw}` `{right.raw}`＝比べた値・`{path}`＝見つけた道すじ・`{file}`＝移動先の文書・`{schema}`＝作る文書のスキーマ・
     * `{create.places}`＝対応「文書を作る」の作り先（スキーマと、その置き場所のパターン）、入力ごとの対応では
     * `{input.placeholder}` `{input.value}`。当てはまらない波括弧と、その場面で値が無いものは、そのまま残す。
     */
    private fun renderTemplate(
        template: String,
        id: String,
        rule: ConstraintRule? = null,
        schemaId: String? = null,
        row: SemanticQueryRow? = null,
        v: SemanticCheckViolation? = null,
        count: Int? = null,
        input: SemanticValueInput? = null,
        document: DocumentId? = null,
    ): String = TEMPLATE_VAR.replace(template) { m ->
        val name = m.groupValues[1]
        when (name) {
            "id" -> id
            "count" -> count?.toString()
            "left" -> v?.left?.text
            "right" -> v?.right?.text
            "left.raw" -> v?.let { it.left.raw ?: it.left.text }
            "right.raw" -> v?.let { it.right.raw ?: it.right.text }
            "input.placeholder" -> input?.let { "{${it.placeholder}}" }
            "input.value" -> input?.value
            "file" -> document?.let(rel)
            "schema" -> schemaId
            // 見つけた道すじ（何段でも辿る線が通った要素を含む。例: 循環の経路）
            "path" -> (row ?: v?.row)?.trail?.takeIf { it.isNotEmpty() }?.joinToString(" → ")
            // 対応「文書を作る」の作り先: スキーマの ID と、分かればその置き場所（match のパターン）。R28-05・UX-97
            "create.places" -> rule?.responses?.filter { it.kind == ResponseDecl.Kind.CREATE }?.flatMap { it.schemas }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { sid -> schemas.firstOrNull { it.id == sid }?.match?.let { "$sid（$it に作成）" } ?: sid }
            else -> {
                // {束縛名.id} {束縛名.type} {束縛名.title}。解が無いとき（あるはずの流れが見つからない指摘など）も、
                // 起点の束縛だけは起点の要素から埋める
                val bind = name.substringBeforeLast('.')
                val node = (row ?: v?.row)?.bindings?.get(bind)
                val start = if (node == null && rule != null && bind == rule.spec.start.bind) graph.entities[id] else null
                when (name.substringAfterLast('.', "")) {
                    "id" -> node?.id ?: start?.id
                    "type" -> node?.entityType ?: start?.entityType
                    "title" -> node?.title ?: start?.title
                    else -> null
                }
            }
        } ?: m.value
    }

    // ---------------------------------------------------------------- 専用チェッカーを使う検査

    private fun findingsOf(rule: CheckerRule): List<SemanticCheckerFinding> =
        session.checkerRun(rule.args).findings.filter { it.kind in rule.messages }

    /** 要素を定義した場所へ報告するチェッカー（遷移検証など）: 要素 id の指摘（指摘の種類つき）。 */
    private fun checker(rule: CheckerRule, id: String): List<Pair<String, Diagnosis>> {
        if (rule.kind.refLocated) return emptyList()
        return findingsOf(rule).filter { it.subjectId == id }.map { finding ->
            val text = renderFinding(rule, finding)
            finding.kind to Diagnosis(
                id, levelOf(rule),
                report = text,
                notice = { anchorRel, _ -> "$text（報告先: 定義出現 $anchorRel）" },
                // 関連するノード（開始・宣言にない遷移先など）へジャンプできるようにする（UX-98 の流儀）
                related = finding.related.map { it.id }.take(10),
                description = rule.description ?: rule.id,
                rule = rule.origin, ruleId = rule.id, kind = finding.kind,
                evidence = finding.related.map { it.id }.sorted(),
            )
        }
    }

    /** 専用チェッカーを使う検査で、要素 id について判定を保留した指摘の種類と理由。 */
    private fun checkerWithheld(rule: CheckerRule, id: String): List<Pair<String, CheckReason>> {
        if (rule.kind.refLocated) return emptyList()
        val run = session.checkerRun(rule.args)
        if (id !in run.subjects) return emptyList()
        return rule.messages.keys.mapNotNull { kind ->
            run.skipped[kind]?.takeIf { it.outcome == CheckOutcome.WITHHELD }?.let { kind to it }
        }
    }

    /** 参照が書かれた場所へ報告するチェッカー: 指摘に付いた参照が、この文書に書かれているものを、その参照の場所へ指摘する。 */
    private fun checkerRefIssues(rule: CheckerRule, document: DocumentId): List<CheckIssue> {
        if (!rule.kind.refLocated) return emptyList()
        val suffix = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
        return findingsOf(rule).mapNotNull { finding ->
            val ref = finding.ref?.takeIf { it.document == document } ?: return@mapNotNull null
            CheckIssue(
                levelOf(rule).toSeverity(), renderFinding(rule, finding) + suffix, ref.path,
                source = IssueSource(
                    rule.origin, rule.id, finding.kind, finding.subjectId, IssueDelivery.REF,
                    finding.related.map { it.id }.sorted(),
                ),
            )
        }
    }

    /** 専用チェッカーの指摘の文面: `{id}`・`{related}`（先頭5件と残りの件数）・`{count}`・チェッカーが返す値（`{path}` など）。 */
    private fun renderFinding(rule: CheckerRule, finding: SemanticCheckerFinding): String {
        val template = rule.messages.getValue(finding.kind)
        val listed = finding.related.take(5).joinToString("・") { n -> n.title?.let { "${n.id}（$it）" } ?: n.id } +
            (if (finding.related.size > 5) " ほか${finding.related.size - 5}件" else "")
        return TEMPLATE_VAR.replace(template) { m ->
            when (val name = m.groupValues[1]) {
                "id" -> finding.subjectId
                "related" -> listed
                "count" -> finding.related.size.toString()
                else -> finding.values[name] ?: m.value
            }
        }
    }

    /** check 反例の列挙（R28-08。UX-98 の流儀: 先頭3組＋残数）。記号は違反した関係（lte 違反なら >）。 */
    private fun describeCheckExamples(examples: List<SemanticCheckViolation>): String {
        fun side(s: SemanticCheckSide): String = when {
            // 読み出した値: どの要素のどの項目か（計算を施した値は「name の文字数」のように添える）
            s.bind != null && s.path != null ->
                "${s.bind}=${s.id}（${s.path!!.pretty()}${s.note?.let { " の$it" } ?: ""}: ${s.text}）"
            // 計算した値: 式を添える（「15」（= 3 + 12））
            s.raw != null && s.raw != s.text -> "「${s.text}」（= ${s.raw}）"
            else -> "「${s.text}」"
        }
        val listed = examples.take(3).joinToString(" ／ ") { v ->
            "${side(v.left)} ${v.term.violationSymbol} ${side(v.right)}" +
                (if (v.check.terms.size > 1) "（「または」のどの条件も満たさない）" else "")
        }
        return listed + (if (examples.size > 3) " ほか${examples.size - 3}組" else "")
    }

    private companion object {
        val TEMPLATE_VAR = Regex("""\{([A-Za-z][A-Za-z0-9_-]*(?:\.[a-z]+)?)}""")
    }
}
