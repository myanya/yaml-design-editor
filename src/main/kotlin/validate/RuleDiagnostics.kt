package validate

import index.IdIndex
import model.YNode
import model.YPath
import model.pretty
import schema.ConstraintExpect
import schema.ConstraintRule
import schema.CoverageLevel
import schema.FlowRule
import schema.MatrixRule
import java.nio.file.Path

/**
 * 規則×主語ID の診断1件（R29-06。P29 Phase 3・OC-07 の残件解消）。
 * 判定（違反か・根拠は何か）を**配送から独立に1回だけ**行い、結果を2つの配送先へ写す:
 * - 報告先＝主語の定義出現（なければ先頭出現）: `reportIssue`。重大度は規則の level
 * - 出現先＝同じIDの他文書の出現: `noticeIssue`。エラー級は同格の ERROR、警告級は INFO の案内に留める
 *   （R28-05a の配送規則。詳細化文書を開くたびのノイズを避けつつ、直せる違反には気づけるようにする）
 * 本文の違いは見出しの2形（report / notice）だけで、根拠の列挙・関連ID・規則の説明は共有する。
 * 修正先（主語の出現を持たない文書）への配送と対処語彙の統一は指摘の三面モデル（BACKLOG UX-101）の領分。
 */
internal class RuleDiagnosis(
    private val subjectId: String,
    private val level: CoverageLevel,
    /** 報告先での本文（規則の説明の手前まで）。 */
    private val report: String,
    /** 出現先での本文（規則の説明の手前まで）。引数は報告先の相対パスと、エラー級として同格で出すか。 */
    private val notice: (anchorRel: String, escalated: Boolean) -> String,
    /** 根拠に関与したID（報告先のジャンプ導線。主語自身は含めない）。 */
    private val related: List<String>,
    /** 規則の説明（description ?: id）。 */
    private val description: String?,
) {
    private val suffix: String get() = description?.let { " — $it" } ?: ""

    fun reportIssue(path: YPath): Issue =
        Issue(level.toSeverity(), report + suffix, path, relatedIds = related)

    fun noticeIssue(path: YPath, anchorRel: String): Issue {
        val escalated = level == CoverageLevel.ERROR
        return Issue(
            if (escalated) Severity.ERROR else Severity.INFO,
            notice(anchorRel, escalated) + suffix,
            path,
            // 出現先では主語の定義へ戻れるよう、主語IDを先頭に加える
            relatedIds = (listOf(subjectId) + related).distinct().take(10),
        )
    }
}

/**
 * 診断の生成（パターン制約・網羅マトリクス・遷移検証）。判定コアはカーネル（index.Query 経由の
 * SemanticQuery / SemanticMatrix / SemanticFlow）で、ここは結果を指摘の本文へ写すだけ。
 * 検証1回（Validator.validate の1呼び出し）につき1インスタンス——遷移検証の全指摘は規則単位でキャッシュする。
 */
internal class RuleDiagnostics(
    private val indexObj: IdIndex,
    private val tree: (Path) -> YNode?,
    /** ワークスペース相対パス（値を読む文書の指定の fileGlob 判定に使う）。 */
    private val rel: (Path) -> String = { it.toString().replace('\\', '/') },
) {

    private val flowCache = HashMap<FlowRule, Map<String, List<semantic.SemanticFlowFinding>>>()

    /**
     * パターン制約（R22-03）: 主語が母集団（起点の node フィルタ＋where）に入らない・違反していないなら null。
     * 判定は expect（存在／不在）・count（件数範囲）・check（判定述語）のいずれか一つ（パースで保証）。
     */
    fun constraint(rule: ConstraintRule, id: String): RuleDiagnosis? {
        if (!index.Query.startMatches(indexObj, rule.spec, id, tree)) return null
        fun notice(evidence: String): (String, Boolean) -> String = { anchorRel, escalated ->
            (if (escalated) "ID「$id」への制約違反があります（報告先: 定義出現 $anchorRel）"
            else "ID「$id」には制約の指摘があります（報告先: 定義出現 $anchorRel）") + evidence
        }
        val description = rule.description ?: rule.id
        val ruleCount = rule.count
        if (rule.check.isNotEmpty()) {
            // check 判定述語（R28-08。CORE-CALCULUS §8）: 解ごとに出現割当て σ を反例量化する。
            // 反例（違反した値の組）の発見＝違反・評価可能な組がなければ判定保留（黙る）
            val examples = index.Query.checkFrom(indexObj, rule.spec, rule.check, id, tree, rel)
            if (examples.isEmpty()) return null
            val evidence = "。反例: ${describeCheckExamples(examples)}"
            return RuleDiagnosis(
                id, rule.level,
                report = "ID「$id」を起点とする解が check を満たしません（${examples.size} 組）$evidence",
                notice = notice(evidence),
                related = examples.take(3).flatMap { listOfNotNull(it.left.id, it.right.id) }.distinct().filter { it != id }.take(10),
                description = description,
            )
        }
        val result = index.Query.matchesFrom(indexObj, rule.spec, id, tree)
        // 違反時は該当の組（束縛の表）を列挙し、関連IDのジャンプ導線を付ける（R28-05・UX-98）
        val rows = result.rows.take(3).joinToString(" ／ ") { row ->
            row.bindings.entries.joinToString("・") { (b, ref) -> "$b=${ref.id}" }
        } + (if (result.total > 3) " ほか${result.total - 3}組" else "")
        val related = result.rows.take(3).flatMap { row -> row.bindings.values.map { it.id } }
            .distinct().filter { it != id }.take(10)
        if (ruleCount != null) {
            // CALC-17（Unknown 降格）: 解探索で未解決参照により候補が失われていたら、
            // 下限未達は判定保留＝沈黙（上限超過は見つかった解が実在する証拠のため降格しない）
            if (!ruleCount.violated(result.total, result.unknownDropped)) return null
            val head = "ID「$id」を起点とするパターンの解が ${result.total} 件です（count: ${ruleCount.describe()}）"
            return RuleDiagnosis(
                id, rule.level,
                report = head + (if (result.rows.isNotEmpty()) "。解の組: $rows" else ""),
                notice = notice(if (result.rows.isNotEmpty()) "。該当の組: $rows" else ""),
                related = related,
                description = description,
            )
        }
        return when (rule.expect) {
            // CALC-17（Unknown 降格）: exists の「解なし」も候補喪失時は判定保留＝沈黙
            // （absent は解の実在が根拠のため降格しない）。未解決参照自体は参照検証が別途指摘する
            ConstraintExpect.EXISTS ->
                if (result.total > 0 || result.unknownDropped) null
                else RuleDiagnosis(
                    id, rule.level,
                    report = "ID「$id」を起点とするパターンが成立していません（expect: exists）",
                    notice = notice(""),
                    related = emptyList(),
                    description = description,
                )
            ConstraintExpect.ABSENT ->
                if (result.total == 0) null
                else {
                    val evidence = if (result.rows.isNotEmpty()) "。該当の組: $rows" else ""
                    RuleDiagnosis(
                        id, rule.level,
                        report = "ID「$id」を起点とするパターンが成立しています（expect: absent）$evidence",
                        notice = notice(evidence),
                        related = related,
                        description = description,
                    )
                }
            null -> null
        }
    }

    /**
     * 値の場所へ指摘を出す検査（SP-08。rule.reportAt を宣言した check）: この文書に書かれた要素（file の occPath）について、
     * その要素の値だけを調べ、条件を満たさない組ごとに指摘を返す。指摘の場所は「要素のパス＋reportAt のパス」。
     * 文面は rule.message のひな型（無ければ既定の文面）、エラー時の対応は rule.responses の宣言から作る（SP-09）。
     */
    fun valueIssues(rule: ConstraintRule, id: String, file: Path, occPath: YPath): List<Issue> {
        val reportAt = rule.reportAt ?: return emptyList()
        if (!index.Query.startMatches(indexObj, rule.spec, id, tree)) return emptyList()
        val violations = index.Query.checkFrom(
            indexObj, rule.spec, rule.check, id, tree, rel,
            pin = reportAt.source to (file to occPath),
        )
        val suffix = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
        return violations.map { v ->
            val text = rule.message?.let { renderTemplate(it, v, input = null) }
                ?: "「${reportAt.path.pretty()}」の値が条件を満たしません: ${describeCheckExamples(listOf(v))}"
            Issue(rule.level.toSeverity(), text + suffix, occPath + reportAt.path, responses = responsesOf(rule, v))
        }
    }

    /** 対応の宣言（rule.responses）を、条件を満たさなかった組の値と出どころから具体的な対応へ写す。 */
    private fun responsesOf(rule: ConstraintRule, v: semantic.SemanticCheckViolation): List<IssueResponse> =
        rule.responses.flatMap { decl ->
            val side = if (decl.target.startsWith("left")) v.left else v.right
            when {
                decl.kind == schema.ResponseDecl.Kind.APPLY ->
                    listOf(IssueResponse.ApplyValue(renderTemplate(decl.label, v, input = null), side.text))
                decl.target.endsWith(".source") -> {
                    val document = side.sourceDocument
                    if (document == null) emptyList()
                    else listOf(IssueResponse.Jump(renderTemplate(decl.label, v, input = null), Path.of(document.value), side.sourcePath))
                }
                else -> side.inputs.mapNotNull { input ->
                    val document = input.document ?: return@mapNotNull null
                    IssueResponse.Jump(renderTemplate(decl.label, v, input), Path.of(document.value), input.path)
                }
            }
        }

    /**
     * 文面・ラベルのひな型の差し込み（1回の走査で置き換える＝差し込んだ値の中の波括弧は再解釈しない）。
     * 書けるのは `{left}` `{right}` `{left.raw}` `{right.raw}`、入力ごとの対応では `{input.placeholder}` `{input.value}`。
     * それ以外の波括弧はそのまま残す。
     */
    private fun renderTemplate(
        template: String,
        v: semantic.SemanticCheckViolation,
        input: semantic.SemanticValueInput?,
    ): String = TEMPLATE_VAR.replace(template) { m ->
        when (m.groupValues[1]) {
            "left" -> v.left.text
            "right" -> v.right.text
            "left.raw" -> v.left.raw ?: v.left.text
            "right.raw" -> v.right.raw ?: v.right.text
            "input.placeholder" -> input?.let { "{${it.placeholder}}" } ?: m.value
            "input.value" -> input?.value ?: m.value
            else -> m.value
        }
    }

    /** 網羅マトリクス（R28-03）: 行 id の不足セル（証人のない列）。不足がなければ null。 */
    fun matrix(rule: MatrixRule, id: String): RuleDiagnosis? {
        val missing = index.Query.matrixMissingColumns(indexObj, rule, id)
        if (missing.isEmpty()) return null
        val listed = missing.take(5).joinToString("・") { m -> m.title?.let { "${m.id}（$it）" } ?: m.id } +
            (if (missing.size > 5) " ほか${missing.size - 5}件" else "")
        return RuleDiagnosis(
            id, rule.level,
            report = "ID「$id」の網羅マトリクスに不足セルがあります（${missing.size} 件）: $listed",
            notice = { anchorRel, _ ->
                "ID「$id」には網羅マトリクスの不足があります（${missing.size} 件。報告先: 定義出現 $anchorRel）: $listed"
            },
            // 不足した列のIDへジャンプできるようにする（R28-05・UX-98）
            related = missing.map { it.id }.take(10),
            description = rule.description ?: rule.id,
        )
    }

    /** 遷移検証（R28-04）: ノード id の指摘（到達不能・行き止まり・宣言外遷移。種別つき）。 */
    fun flow(rule: FlowRule, id: String): List<Pair<semantic.SemanticFlowFindingKind, RuleDiagnosis>> {
        val byNode = flowCache.getOrPut(rule) { index.Query.flowFindings(indexObj, rule).groupBy { it.nodeId } }
        return byNode[id].orEmpty().map { finding ->
            val text = describeFlowFinding(id, finding)
            finding.kind to RuleDiagnosis(
                id, rule.level,
                report = text,
                notice = { anchorRel, _ -> "$text（報告先: 定義出現 $anchorRel）" },
                // 開始・宣言外の相手先へジャンプできるようにする（UX-98 の流儀）
                related = finding.related.map { it.id }.take(10),
                description = rule.description ?: rule.id,
            )
        }
    }

    /** check 反例の列挙（R28-08。UX-98 の流儀: 先頭3組＋残数）。記号は違反した関係（lte 違反なら >）。 */
    private fun describeCheckExamples(examples: List<semantic.SemanticCheckViolation>): String {
        fun side(s: semantic.SemanticCheckSide): String =
            if (s.bind != null && s.path != null) "${s.bind}=${s.id}（${s.path!!.pretty()}: ${s.text}）" else "「${s.text}」"
        val listed = examples.take(3).joinToString(" ／ ") { v ->
            "${side(v.left)} ${v.check.op.violationSymbol} ${side(v.right)}"
        }
        return listed + (if (examples.size > 3) " ほか${examples.size - 3}組" else "")
    }

    /** 遷移検証の指摘本文（R28-04）: 種別ごとの説明＋関連ノードの列挙（先頭5件＋残数）。 */
    private fun describeFlowFinding(id: String, finding: semantic.SemanticFlowFinding): String {
        fun listed(nodes: List<semantic.SemanticFlowNode>): String =
            nodes.take(5).joinToString("・") { n -> n.title?.let { "${n.id}（$it）" } ?: n.id } +
                (if (nodes.size > 5) " ほか${nodes.size - 5}件" else "")
        return when (finding.kind) {
            semantic.SemanticFlowFindingKind.UNREACHABLE ->
                "ID「$id」は開始ノードから到達できません（開始: ${listed(finding.related)}）"
            semantic.SemanticFlowFindingKind.DEAD_END ->
                "ID「$id」から出る遷移がなく、終端としても宣言されていません"
            semantic.SemanticFlowFindingKind.UNDECLARED ->
                "ID「$id」から宣言にない遷移があります（${finding.related.size} 件）: ${listed(finding.related)}"
        }
    }

    private companion object {
        val TEMPLATE_VAR = Regex("""\{([a-z]+(?:\.[a-z]+)?)}""")
    }
}
