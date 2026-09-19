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
    /** エラー時の対応（SP-09。規則の responses の宣言から作ったもの。報告先にだけ付ける）。 */
    private val responses: List<IssueResponse> = emptyList(),
) {
    private val suffix: String get() = description?.let { " — $it" } ?: ""

    fun reportIssue(path: YPath): Issue =
        Issue(level.toSeverity(), report + suffix, path, responses = responses, relatedIds = related)

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
    /** 関係レジストリと全スキーマ（エラー時の対応「参照を書き足せそうな文書へ移動」の候補の導出に使う）。 */
    private val relations: Map<String, schema.RelationDef> = emptyMap(),
    private val allSchemas: List<schema.SchemaDef> = emptyList(),
    /** 検査の重大度（検査の宣言 levelFrom＝関係レジストリの強制スイッチによる切り替えを、呼び出し側が解決して渡す）。 */
    private val levelOf: (ConstraintRule) -> CoverageLevel = { it.level },
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
        // 文面のひな型（rule.message）を宣言した検査は、報告先にも出現先にも、その文面を出す
        fun customNotice(text: String): (String, Boolean) -> String = { anchorRel, _ -> "$text（報告先: 定義出現 $anchorRel）" }
        val description = rule.description ?: rule.id
        val ruleCount = rule.count
        if (rule.check.isNotEmpty()) {
            // check 判定述語（R28-08。CORE-CALCULUS §8）: 解ごとに出現割当て σ を反例量化する。
            // 反例（違反した値の組）の発見＝違反・評価可能な組がなければ判定保留（黙る）
            val examples = index.Query.checkFrom(indexObj, rule.spec, rule.check, id, tree, rel)
            if (examples.isEmpty()) return null
            val evidence = "。反例: ${describeCheckExamples(examples)}"
            val custom = rule.message?.let { template ->
                renderTemplate(template, id, rule = rule, row = examples.first().row.let { with(index.Query) { it.toRow() } }, v = examples.first()) +
                    (if (examples.size > 1) "（ほか${examples.size - 1}組）" else "")
            }
            return RuleDiagnosis(
                id, levelOf(rule),
                report = custom ?: "ID「$id」を起点とする解が check を満たしません（${examples.size} 組）$evidence",
                notice = custom?.let(::customNotice) ?: notice(evidence),
                related = examples.take(3).flatMap { listOfNotNull(it.left.id, it.right.id) }.distinct().filter { it != id }.take(10),
                description = description,
                responses = responsesOf(rule, id, examples.first()),
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
            val custom = rule.message?.let { renderTemplate(it, id, rule = rule, row = result.rows.firstOrNull(), count = result.total) }
            return RuleDiagnosis(
                id, levelOf(rule),
                report = custom ?: (head + (if (result.rows.isNotEmpty()) "。解の組: $rows" else "")),
                notice = custom?.let(::customNotice) ?: notice(if (result.rows.isNotEmpty()) "。該当の組: $rows" else ""),
                related = related,
                description = description,
                responses = responsesOf(rule, id, null),
            )
        }
        val custom = rule.message?.let { renderTemplate(it, id, rule = rule, row = result.rows.firstOrNull(), count = result.total) }
        return when (rule.expect) {
            // CALC-17（Unknown 降格）: exists の「解なし」も候補喪失時は判定保留＝沈黙
            // （absent は解の実在が根拠のため降格しない）。未解決参照自体は参照検証が別途指摘する
            ConstraintExpect.EXISTS ->
                if (result.total > 0 || result.unknownDropped) null
                else RuleDiagnosis(
                    id, levelOf(rule),
                    report = custom ?: "ID「$id」を起点とするパターンが成立していません（expect: exists）",
                    notice = custom?.let(::customNotice) ?: notice(""),
                    related = emptyList(),
                    description = description,
                    responses = responsesOf(rule, id, null),
                )
            ConstraintExpect.ABSENT ->
                if (result.total == 0) null
                else {
                    val evidence = if (result.rows.isNotEmpty()) "。該当の組: $rows" else ""
                    RuleDiagnosis(
                        id, levelOf(rule),
                        report = custom ?: "ID「$id」を起点とするパターンが成立しています（expect: absent）$evidence",
                        notice = custom?.let(::customNotice) ?: notice(evidence),
                        related = related,
                        description = description,
                        responses = responsesOf(rule, id, null),
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
        if (!index.Query.startMatches(indexObj, rule.spec, id, tree)) return null
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
            rule = rule, id = id, active = active, satisfied = diagnosis == null, summary = summary,
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
        if (!index.Query.startMatches(indexObj, rule.spec, id, tree)) return emptyList()
        val violations = index.Query.checkFrom(
            indexObj, rule.spec, rule.check, id, tree, rel,
            pin = reportAt.source to (file to occPath),
        )
        val suffix = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
        return violations.map { v ->
            val text = rule.message?.let { renderTemplate(it, id, rule = rule, v = v) }
                ?: "「${reportAt.path.pretty()}」の値が条件を満たしません: ${describeCheckExamples(listOf(v))}"
            Issue(levelOf(rule).toSeverity(), text + suffix, occPath + reportAt.path, responses = responsesOf(rule, id, v))
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
            if (!index.Query.startMatches(indexObj, rule.spec, id, tree)) continue
            if (rule.check.isNotEmpty()) {
                for (v in index.Query.checkFrom(indexObj, rule.spec, rule.check, id, tree, rel)) {
                    val row = with(index.Query) { v.row.toRow() }
                    val at = row.refs[refBind]?.takeIf { it.file == file } ?: continue
                    val text = rule.message?.let { renderTemplate(it, id, rule = rule, row = row, v = v) }
                        ?: "参照「${at.targetId}」が条件を満たしません: ${describeCheckExamples(listOf(v))}"
                    issues += Issue(levelOf(rule).toSeverity(), text + suffix, at.path, responses = responsesOf(rule, id, v))
                }
            } else {
                for (row in index.Query.matchesFrom(indexObj, rule.spec, id, tree).rows) {
                    val at = row.refs[refBind]?.takeIf { it.file == file } ?: continue
                    val text = rule.message?.let { renderTemplate(it, id, rule = rule, row = row) }
                        ?: "参照「${at.targetId}」は、あってはならないパターンに当たります（expect: absent）"
                    issues += Issue(levelOf(rule).toSeverity(), text + suffix, at.path, responses = responsesOf(rule, id, null))
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
     * `{left}` `{right}` `{left.raw}` `{right.raw}`＝比べた値・`{file}`＝移動先の文書・`{schema}`＝作る文書のスキーマ・
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
            // 対応「文書を作る」の作り先: スキーマの ID と、分かればその置き場所（match のパターン）。R28-05・UX-97
            "create.places" -> rule?.responses?.filter { it.kind == schema.ResponseDecl.Kind.CREATE }?.flatMap { it.schemas }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { sid -> allSchemas.firstOrNull { it.id == sid }?.match?.let { "$sid（$it に作成）" } ?: sid }
            else -> {
                // {束縛名.id} {束縛名.type} {束縛名.title}
                val node = (row ?: v?.row?.let { with(index.Query) { it.toRow() } })?.bindings?.get(name.substringBeforeLast('.'))
                when (name.substringAfterLast('.', "")) {
                    "id" -> node?.id
                    "type" -> node?.entityType
                    "title" -> node?.title
                    else -> null
                }
            }
        } ?: m.value
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
        val TEMPLATE_VAR = Regex("""\{([A-Za-z][A-Za-z0-9_-]*(?:\.[a-z]+)?)}""")
    }
}
