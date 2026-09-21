package validate

import index.IdIndex
import index.toKernelView
import model.YNode
import model.YPath
import model.pretty
import schema.ConstraintExpect
import schema.ConstraintRule
import schema.CoverageLevel
import schema.CheckerRule
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
    /** エラー時の対応（SP-09。規則の responses の宣言から作ったもの。報告先にだけ付ける）。 */
    private val responses: List<IssueResponse> = emptyList(),
    /** 指摘の出どころ（P27 段A）: 検査の出どころ・呼び名・専用チェッカーの指摘の種類・根拠に関わった要素（主語を除く全件）。 */
    private val rule: schema.RuleOrigin? = null,
    private val ruleId: String? = null,
    private val kind: String? = null,
    private val evidence: List<String> = emptyList(),
) {
    private val suffix: String get() = description?.let { " — $it" } ?: ""

    private fun source(delivery: IssueDelivery) = IssueSource(rule, ruleId, kind, subjectId, delivery, evidence)

    fun reportIssue(path: YPath): Issue =
        Issue(level.toSeverity(), report + suffix, path, responses = responses, relatedIds = related, source = source(IssueDelivery.REPORT))

    fun noticeIssue(path: YPath, anchorRel: String): Issue {
        val escalated = level == CoverageLevel.ERROR
        return Issue(
            if (escalated) Severity.ERROR else Severity.INFO,
            notice(anchorRel, escalated) + suffix,
            path,
            // 出現先では主語の定義へ戻れるよう、主語IDを先頭に加える
            relatedIds = (listOf(subjectId) + related).distinct().take(10),
            source = source(IssueDelivery.NOTICE),
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
    /** 関係レジストリと全スキーマ（エラー時の対応「参照を書き足せそうな文書へ移動」の候補の導出に使う）。 */
    private val relations: Map<String, schema.RelationDef> = emptyMap(),
    private val allSchemas: List<schema.SchemaDef> = emptyList(),
    /** 検査の重大度（検査の宣言 levelFrom＝関係レジストリの強制スイッチによる切り替えを、呼び出し側が解決して渡す）。 */
    private val levelOf: (ConstraintRule) -> CoverageLevel = { it.level },
    /**
     * 検査の評価の覚え書き（P27 段B）。索引と共有のもの（KernelIndexView.checkSession）を渡すと、他の文書の検証で評価済みの
     * 「検査 × 要素」を評価し直さない。渡さなければ、この検証1回の中だけの覚え書きを使う（tree の中身が索引と食い違いうる呼び出し用）。
     */
    session: semantic.SemanticCheckSession? = null,
) {
    private val checks: semantic.SemanticCheckSession = session
        ?: indexObj.toKernelView().let { semantic.SemanticCheckSession(it.entries, it.refs, it.graph) }
    private val kernelRel: (model.DocumentId) -> String = { d -> rel(Path.of(d.value)) }
    private val kernelTree: (model.DocumentId) -> YNode? = { d -> tree(Path.of(d.value)) }

    /** 専用チェッカーの1回分（渡す値ごとに1回だけ実行する）。 */
    private fun runOf(rule: CheckerRule): semantic.SemanticCheckerRun = checks.checkerRun(rule.args)

    private fun findingsOf(rule: CheckerRule): List<semantic.SemanticCheckerFinding> =
        runOf(rule).findings.filter { it.kind in rule.messages }

    /** クエリの検査 × 要素の判定（カーネルの SemanticRules。覚え書きにあれば評価しない）。 */
    fun result(rule: ConstraintRule, id: String): semantic.SemanticRuleResult = checks.result(rule, id, kernelRel, kernelTree)

    /**
     * その検査に違反していて、直せる場所へ案内する要素の一覧（ID 昇順。報告先があり、フェーズに達しているものだけ）。
     * 案内先の文書ごとに全要素を調べ直さないよう、検査ごとに1回だけ求める。
     */
    fun fixSiteIds(rule: ConstraintRule, eligible: (String) -> Boolean): List<String> = checks.memo(rule, "fixSiteIds") {
        indexObj.entries.keys.sorted().filter { id -> eligible(id) && constraint(rule, id) != null }
    }

    /** 判定を保留した理由（保留でなければ null）。対象外（検査の対象でない・比べる値が無い など）は含めない。 */
    fun withheldReason(rule: ConstraintRule, id: String): semantic.CheckReason? =
        result(rule, id).takeIf { it.outcome == semantic.CheckOutcome.WITHHELD }?.reason

    /** 専用チェッカーを使う検査で、要素 id について判定を保留した指摘の種類と理由。 */
    fun checkerWithheld(rule: CheckerRule, id: String): List<Pair<String, semantic.CheckReason>> {
        if (rule.kind.refLocated) return emptyList()
        val run = runOf(rule)
        if (id !in run.subjects) return emptyList()
        return rule.messages.keys.mapNotNull { kind ->
            run.skipped[kind]?.takeIf { it.outcome == semantic.CheckOutcome.WITHHELD }?.let { kind to it }
        }
    }

    /** 根拠に関わった要素（解の束縛と、比べた値の要素）の ID。主語を除き、ID 昇順で全件。 */
    private fun evidenceOf(id: String, rows: List<index.QueryRow>, violations: List<semantic.SemanticCheckViolation> = emptyList()): List<String> =
        (rows.flatMap { row -> row.bindings.values.map { it.id } } + violations.flatMap { listOfNotNull(it.left.id, it.right.id) })
            .distinct().filter { it != id }.sorted()

    /**
     * クエリの検査の診断: 結果が「違反」のときだけ診断を返す（充足・保留・対象外は null）。どの結果になるかの判定は
     * カーネル（semantic.SemanticRules。判定の保留の規則 CALC-17 もそこ）で、ここは根拠を指摘の文面へ写すだけ。
     */
    fun constraint(rule: ConstraintRule, id: String): RuleDiagnosis? {
        val outcome = result(rule, id)
        if (outcome.outcome != semantic.CheckOutcome.VIOLATED) return null
        fun notice(evidence: String): (String, Boolean) -> String = { anchorRel, escalated ->
            (if (escalated) "ID「$id」への制約違反があります（報告先: 定義出現 $anchorRel）"
            else "ID「$id」には制約の指摘があります（報告先: 定義出現 $anchorRel）") + evidence
        }
        // 文面のひな型（rule.message）を宣言した検査は、報告先にも出現先にも、その文面を出す
        fun customNotice(text: String): (String, Boolean) -> String = { anchorRel, _ -> "$text（報告先: 定義出現 $anchorRel）" }
        fun diagnosis(
            report: String,
            notice: (String, Boolean) -> String,
            related: List<String>,
            evidence: List<String>,
            v: semantic.SemanticCheckViolation? = null,
        ) = RuleDiagnosis(
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
                renderTemplate(template, id, rule = rule, row = examples.first().row.let { with(index.Query) { it.toRow() } }, v = examples.first()) +
                    (if (examples.size > 1) "（ほか${examples.size - 1}組）" else "")
            }
            return diagnosis(
                report = custom ?: "ID「$id」を起点とする解が check を満たしません（${examples.size} 組）$evidence",
                notice = custom?.let(::customNotice) ?: notice(evidence),
                related = examples.take(3).flatMap { listOfNotNull(it.left.id, it.right.id) }.distinct().filter { it != id }.take(10),
                evidence = evidenceOf(id, examples.map { with(index.Query) { it.row.toRow() } }, examples),
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
        val result = with(index.Query) { (outcome.matches ?: return null).toResult() }
        // 違反時は該当の組（束縛の表）を列挙し、関連IDのジャンプ導線を付ける（R28-05・UX-98）
        val rows = result.rows.take(3).joinToString(" ／ ") { row ->
            row.bindings.entries.joinToString("・") { (b, ref) -> "$b=${ref.id}" }
        } + (if (result.total > 3) " ほか${result.total - 3}組" else "")
        val related = result.rows.take(3).flatMap { row -> row.bindings.values.map { it.id } }
            .distinct().filter { it != id }.take(10)
        val custom = rule.message?.let { renderTemplate(it, id, rule = rule, row = result.rows.firstOrNull(), count = result.total) }
        rule.count?.let { ruleCount ->
            val head = "ID「$id」を起点とするパターンの解が ${result.total} 件です（count: ${ruleCount.describe()}）"
            return diagnosis(
                report = custom ?: (head + (if (result.rows.isNotEmpty()) "。解の組: $rows" else "")),
                notice = custom?.let(::customNotice) ?: notice(if (result.rows.isNotEmpty()) "。該当の組: $rows" else ""),
                related = related,
                evidence = evidenceOf(id, result.rows),
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
                val evidence = if (result.rows.isNotEmpty()) "。該当の組: $rows" else ""
                diagnosis(
                    report = custom ?: "ID「$id」を起点とするパターンが成立しています（expect: absent）$evidence",
                    notice = custom?.let(::customNotice) ?: notice(evidence),
                    related = related,
                    evidence = evidenceOf(id, result.rows),
                )
            }
            null -> null
        }
    }

    /**
     * 検査の状況（SP-08。rule.status を宣言した検査）: 起点の母集団に入らない ID は null（一覧に出さない）。
     * 満たしているか・不足しているかは検証と同じ診断（constraint）で決め、状況の1行（summary）は status のひな型へ
     * いまの値を埋めて作る（check の検査は、満たしているときも比べた値を埋める）。active は呼び出し側が
     * フェーズの現在位置から決めて渡す（フェーズ未到達でも、いまの値は summary に出す）。
     */
    fun status(rule: ConstraintRule, id: String, active: Boolean): RuleStatus? {
        val template = rule.status ?: return null
        val outcome = result(rule, id)
        if (outcome.reason == semantic.CheckReason.NOT_IN_SCOPE) return null
        val diagnosis = constraint(rule, id)
        val summary = if (rule.check.isNotEmpty()) {
            val evaluations = index.Query.checkEvaluationsFrom(indexObj, rule.spec, rule.check, id, tree, rel)
            // 不足している項目があればその値、なければ最初の項目の値を、状況の1行に出す
            (evaluations.firstOrNull { !it.satisfied } ?: evaluations.firstOrNull())?.let { e ->
                val v = semantic.SemanticCheckViolation(e.check, e.term, e.left, e.right, e.row)
                renderTemplate(template, id, rule = rule, v = v)
            }
        } else {
            val result = index.Query.matchesFrom(indexObj, rule.spec, id, tree)
            renderTemplate(template, id, rule = rule, row = result.rows.firstOrNull(), count = result.total)
        }
        return RuleStatus(
            rule = rule, id = id, active = active, outcome = outcome.outcome, reason = outcome.reason, summary = summary,
            message = diagnosis?.reportIssue(emptyList())?.message,
        )
    }

    /** 直せる場所への案内の文面（rule.fixSite）: `{count}`＝ID の件数・`{ids}`＝ID の並び（先頭5件と残りの件数）。 */
    fun fixSiteMessage(fixSite: schema.FixSite, ids: List<String>): String {
        val shown = ids.take(5).joinToString(", ") + (if (ids.size > 5) " 他${ids.size - 5}件" else "")
        return fixSite.message.replace("{count}", ids.size.toString()).replace("{ids}", shown)
    }

    /**
     * 値の場所へ指摘を出す検査（SP-08。rule.reportAt を宣言した check）: この文書に書かれた要素（file の occPath）について、
     * その要素の値だけを調べ、条件を満たさない組ごとに指摘を返す。指摘の場所は「要素のパス＋reportAt のパス」。
     * 文面は rule.message のひな型（無ければ既定の文面）、エラー時の対応は rule.responses の宣言から作る（SP-09）。
     */
    fun valueIssues(rule: ConstraintRule, id: String, file: Path, occPath: YPath): List<Issue> {
        val reportAt = rule.reportAt ?: return emptyList()
        val pin = reportAt.source to semantic.SemanticExpression.Occurrence(model.DocumentId(file.toString()), occPath)
        val violations = checks.pinnedResult(rule, id, pin, kernelRel, kernelTree).violations
        val suffix = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
        return violations.map { v ->
            val text = rule.message?.let { renderTemplate(it, id, rule = rule, v = v) }
                ?: "「${reportAt.path.pretty()}」の値が条件を満たしません: ${describeCheckExamples(listOf(v))}"
            Issue(
                levelOf(rule).toSeverity(), text + suffix, occPath + reportAt.path, responses = responsesOf(rule, id, v),
                source = IssueSource(
                    rule.origin, rule.id, null, id, IssueDelivery.VALUE,
                    evidenceOf(id, listOf(with(index.Query) { v.row.toRow() }), listOf(v)),
                ),
            )
        }
    }

    /**
     * 参照の場所へ指摘を出す検査（SP-08。rule.reportAt.ref を宣言した検査。対象型の不一致など）: 見つかった解
     * （expect: absent）または条件を満たさなかった組（check）ごとに、束縛した参照が**この文書に書かれている**ものを、
     * その参照の場所へ指摘する。起点は全エンティティから探すが、参照が最初の edge で束縛されているときは、
     * この文書に関わる起点だけを調べる（出ていく参照ならこの文書に書かれた要素・入ってくる参照ならこの文書の参照の相手）。
     */
    fun refIssues(rule: ConstraintRule, file: Path, localIds: List<String>, localTargets: List<String>): List<Issue> {
        val refBind = rule.reportAt?.ref ?: return emptyList()
        val firstEdge = rule.spec.hops.firstOrNull()?.edge
        val starts = when {
            firstEdge?.bind != refBind -> indexObj.graph.entities.keys.sorted()
            firstEdge.dir == schema.QueryDir.OUT -> localIds.distinct().sorted()
            else -> localTargets.distinct().sorted()
        }
        val suffix = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
        val issues = mutableListOf<Issue>()
        for (id in starts) {
            val outcome = result(rule, id)
            if (outcome.outcome != semantic.CheckOutcome.VIOLATED) continue
            if (rule.check.isNotEmpty()) {
                for (v in outcome.violations) {
                    val row = with(index.Query) { v.row.toRow() }
                    val at = row.refs[refBind]?.takeIf { it.file == file } ?: continue
                    val text = rule.message?.let { renderTemplate(it, id, rule = rule, row = row, v = v) }
                        ?: "参照「${at.targetId}」が条件を満たしません: ${describeCheckExamples(listOf(v))}"
                    issues += Issue(
                        levelOf(rule).toSeverity(), text + suffix, at.path, responses = responsesOf(rule, id, v),
                        source = IssueSource(rule.origin, rule.id, null, id, IssueDelivery.REF, evidenceOf(id, listOf(row), listOf(v))),
                    )
                }
            } else {
                for (row in with(index.Query) { outcome.matches?.toResult() }?.rows.orEmpty()) {
                    val at = row.refs[refBind]?.takeIf { it.file == file } ?: continue
                    val text = rule.message?.let { renderTemplate(it, id, rule = rule, row = row) }
                        ?: "参照「${at.targetId}」は、あってはならないパターンに当たります（expect: absent）"
                    issues += Issue(
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
    private fun responsesOf(rule: ConstraintRule, id: String, v: semantic.SemanticCheckViolation?): List<IssueResponse> =
        rule.responses.flatMap { decl ->
            if (decl.kind == schema.ResponseDecl.Kind.CREATE) {
                // 文書を新しく作る: 起点の ID の詳細文書を、スキーマごとに1件（作るスキーマはラベルの {schema} に埋める）
                return@flatMap decl.schemas.map { sid ->
                    IssueResponse.CreateDetail(renderTemplate(decl.label, id, rule = rule, v = v, schemaId = sid), sid, id, decl.note)
                }
            }
            if (decl.target == schema.ResponseDecl.REFERENCE_SOURCES) {
                return@flatMap ReferenceSources.candidates(indexObj, relations, allSchemas, decl.relation.orEmpty(), id).take(3).map { f ->
                    IssueResponse.Jump(renderTemplate(decl.label, id, v = v, file = f), f, path = null, note = decl.note)
                }
            }
            if (v == null) return@flatMap emptyList()
            val side = if (decl.target.startsWith("left")) v.left else v.right
            when {
                decl.kind == schema.ResponseDecl.Kind.APPLY ->
                    listOf(IssueResponse.ApplyValue(renderTemplate(decl.label, id, v = v), side.text))
                decl.target.endsWith(".source") -> {
                    val document = side.sourceDocument
                    if (document == null) emptyList()
                    else listOf(IssueResponse.Jump(renderTemplate(decl.label, id, v = v), Path.of(document.value), side.sourcePath, decl.note))
                }
                else -> side.inputs.mapNotNull { input ->
                    val document = input.document ?: return@mapNotNull null
                    IssueResponse.Jump(renderTemplate(decl.label, id, v = v, input = input), Path.of(document.value), input.path, decl.note)
                }
            }
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
        row: index.QueryRow? = null,
        v: semantic.SemanticCheckViolation? = null,
        count: Int? = null,
        input: semantic.SemanticValueInput? = null,
        file: Path? = null,
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
            "file" -> file?.let(rel)
            "schema" -> schemaId
            // 見つけた道すじ（何段でも辿る線が通った要素を含む。例: 循環の経路）
            "path" -> (row ?: v?.row?.let { with(index.Query) { it.toRow() } })?.trail?.takeIf { it.isNotEmpty() }?.joinToString(" → ")
            // 対応「文書を作る」の作り先: スキーマの ID と、分かればその置き場所（match のパターン）。R28-05・UX-97
            "create.places" -> rule?.responses?.filter { it.kind == schema.ResponseDecl.Kind.CREATE }?.flatMap { it.schemas }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { sid -> allSchemas.firstOrNull { it.id == sid }?.match?.let { "$sid（$it に作成）" } ?: sid }
            else -> {
                // {束縛名.id} {束縛名.type} {束縛名.title}。解が無いとき（あるはずの流れが見つからない指摘など）も、
                // 起点の束縛だけは起点の要素から埋める
                val bind = name.substringBeforeLast('.')
                val node = (row ?: v?.row?.let { with(index.Query) { it.toRow() } })?.bindings?.get(bind)
                val start = if (node == null && rule != null && bind == rule.spec.start.bind) indexObj.graph.entities[id] else null
                when (name.substringAfterLast('.', "")) {
                    "id" -> node?.id ?: start?.id
                    "type" -> node?.entityType ?: start?.entityType
                    "title" -> node?.title ?: start?.title
                    else -> null
                }
            }
        } ?: m.value
    }

    /**
     * 専用チェッカーを使う検査（SP-13。要素を定義した場所へ報告するもの＝遷移検証など）: 要素 id の指摘（指摘の種類つき）。
     * 文面は検査の messages のひな型から作る（書かれていない種類は指摘しない）。
     */
    fun checker(rule: CheckerRule, id: String, level: CoverageLevel = rule.level): List<Pair<String, RuleDiagnosis>> {
        if (rule.kind.refLocated) return emptyList()
        return findingsOf(rule).filter { it.subjectId == id }.map { finding ->
            val text = renderFinding(rule, finding)
            finding.kind to RuleDiagnosis(
                id, level,
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

    /**
     * 専用チェッカーを使う検査のうち、参照が書かれた場所へ報告するもの（循環の検出など）: 指摘に付いた参照が
     * **この文書に書かれている**ものを、その参照の場所へ指摘する。
     */
    fun checkerRefIssues(rule: CheckerRule, file: Path, level: CoverageLevel = rule.level): List<Issue> {
        if (!rule.kind.refLocated) return emptyList()
        val suffix = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
        val target = model.DocumentId(file.toString())
        return findingsOf(rule).mapNotNull { finding ->
            val ref = finding.ref?.takeIf { it.document == target } ?: return@mapNotNull null
            Issue(
                level.toSeverity(), renderFinding(rule, finding) + suffix, ref.path,
                source = IssueSource(
                    rule.origin, rule.id, finding.kind, finding.subjectId, IssueDelivery.REF,
                    finding.related.map { it.id }.sorted(),
                ),
            )
        }
    }

    /** 専用チェッカーの指摘の文面: `{id}`・`{related}`（先頭5件と残りの件数）・`{count}`・チェッカーが返す値（`{path}` など）。 */
    private fun renderFinding(rule: CheckerRule, finding: semantic.SemanticCheckerFinding): String {
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
    private fun describeCheckExamples(examples: List<semantic.SemanticCheckViolation>): String {
        fun side(s: semantic.SemanticCheckSide): String = when {
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
