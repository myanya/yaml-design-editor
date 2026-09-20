package validate

import index.DocScanner
import index.IdEntry
import index.IdIndex
import index.idMatchesPattern
import index.isAllowedBy
import index.toScanned
import model.YNode
import model.YPath
import model.getAt
import model.scalarText
import schema.EnforcementDef
import schema.EntityRole
import schema.RefTarget
import schema.RelationDef
import schema.FieldDef
import schema.FieldType
import schema.entityFor
import schema.ownedFieldKeys
import schema.Projection
import schema.SchemaDef
import schema.UnknownFieldsPolicy
import schema.currentPhaseIndex
import schema.currentPhaseValue
import schema.effectiveFields
import schema.phaseProgress
import schema.phaseSummaryText
import schema.CoverageLevel
import schema.SchemaKind
import schema.SchemaLoader
import schema.requiredAt
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * エラー時の対応（SP-09。P29 Phase 6 第1段）: 対応の種類は閉じた一覧で、どれを・どのラベルで示すかは
 * 検査の定義（YAML）が宣言する。UIは指摘のメニューに、宣言された順で並べる。
 */
sealed interface IssueResponse {
    val label: String

    /**
     * その場所へ移動する（値の出どころ——テンプレートや、差し込んだ値が書かれた場所、参照を書き足せそうな文書など）。
     * path が無ければ文書を開く。note はメニューでラベルの下に添える補足。
     */
    data class Jump(override val label: String, val file: Path, val path: YPath?, val note: String? = null) : IssueResponse

    /** 値を、指摘の場所へ書き込む（未保存の編集として。Undo 可）。 */
    data class ApplyValue(override val label: String, val value: String) : IssueResponse

    /** この ID の詳細文書を、指定のスキーマの置き場所に新しく作って開く（R28-05a・UX-97 の導線）。 */
    data class CreateDetail(override val label: String, val schemaId: String, val id: String, val note: String? = null) : IssueResponse
}

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
 * 構造の検証・参照の解決など、定義体を通らない指摘は出どころを持たない（source = null）。
 */
data class IssueSource(
    val rule: schema.RuleOrigin?,
    val ruleId: String?,
    val kind: String?,
    val subjectId: String?,
    val delivery: IssueDelivery,
    val evidence: List<String> = emptyList(),
) {
    /**
     * 「同じ検査の・同じ要素についての・同じ配り方の指摘」を見分けるキー。場所と文面は含めない——要素の挿入で場所の番号が
     * ずれても、文面の件数が変わっても、同じ指摘として扱える（提出ゲート no-new-errors が使う）。出どころの分からない検査は null。
     */
    val identity: String? get() = rule?.let { "${it.key}|${kind.orEmpty()}|${subjectId.orEmpty()}|$delivery" }
}

data class Issue(
    val severity: Severity,
    val message: String,
    val path: YPath,
    /** エラー時の対応（SP-09）。検査の定義が宣言したものを、宣言順に持つ。 */
    val responses: List<IssueResponse> = emptyList(),
    /** 指摘に関連するID（UX-15 の不足案内など）。UIはクリックで各IDの定義へジャンプできる。 */
    val relatedIds: List<String> = emptyList(),
    /** 指摘の出どころ（定義体の検査から出た指摘だけが持つ）。 */
    val source: IssueSource? = null,
)

/**
 * 判定を保留した検査1件（P27 段A。BACKLOG UX-113）: データの不備（相手の見つからない参照など）のために、
 * 充足とも違反とも決められなかった「検査 × 要素」。指摘ではない——検証パネルには出さず、準備完了レポートと CLI が件数と内訳を示す。
 * 報告先（対象の要素を定義した場所）の文書の検証でだけ集める（値・参照の場所へ出す検査は対象外）。
 */
data class WithheldCheck(
    val rule: schema.RuleOrigin?,
    val ruleId: String?,
    val description: String?,
    /** 専用チェッカーの指摘の種類（クエリの検査は null）。 */
    val kind: String?,
    val subjectId: String,
    val path: YPath,
    val reason: semantic.CheckReason,
) {
    /** 表示用の1行。 */
    val text: String
        get() = "ID「$subjectId」の検査を保留しました: " + (description ?: ruleId ?: rule?.at ?: "（名前のない検査）") +
            (kind?.let { "（$it）" } ?: "") + " — ${reason.text}"
}

/** 文書1つの検証の結果: 指摘と、判定を保留した検査。 */
data class ValidationResult(val issues: List<Issue>, val withheld: List<WithheldCheck>)

/**
 * 検査の状況1件（SP-08。`status` を宣言した検査×ID。右パネルのカバレッジ・ID 横ぐし・ビューの状態列・エージェントの related が共用）。
 * - active: activeFrom のフェーズに達しているか（false は指摘にせず「フェーズ未到達」として表示する）
 * - outcome / reason: 検査の結果（違反・充足・保留・対象外）と、保留・対象外の理由（P27 段A）
 * - satisfied: 検査を満たしているか（結果が「充足」。保留は充足に数えない＝意図的差分 D-26 ③）
 * - summary: 状況の1行（検査の status のひな型に、いまの値を埋めたもの。例「出現 1 件（必要 1 件）」。評価できなければ null）
 * - message: 満たしていないときの指摘の文面（検証パネルに出るものと同じ）
 */
data class RuleStatus(
    val rule: schema.ConstraintRule,
    val id: String,
    val active: Boolean,
    val outcome: semantic.CheckOutcome,
    val reason: semantic.CheckReason?,
    val summary: String?,
    val message: String?,
) {
    val satisfied: Boolean get() = outcome == semantic.CheckOutcome.SATISFIED

    /** 確かめられなかった（保留）か。 */
    val withheld: Boolean get() = outcome == semantic.CheckOutcome.WITHHELD

    /** 充足とも不足とも言えない（保留・対象外）か。UI は充足・不足と別の見せ方にする。 */
    val undetermined: Boolean get() = outcome == semantic.CheckOutcome.WITHHELD || outcome == semantic.CheckOutcome.NOT_APPLICABLE

    /** 表示用の1行（UI とエージェントで共用）。 */
    val text: String
        get() {
            val note = (rule.description ?: rule.id)?.let { " — $it" } ?: ""
            return when {
                !active -> "フェーズ未到達（${rule.activeFrom} から判定" + (summary?.let { "。現在 $it" } ?: "") + "）" + note
                satisfied -> (summary ?: "充足") + note
                withheld -> "判定保留（${reason?.text ?: "確かめられませんでした"}）" + note
                outcome == semantic.CheckOutcome.NOT_APPLICABLE -> "対象外（${reason?.text ?: "確かめるものがありません"}）" + note
                else -> message ?: ((summary ?: "不足") + note)
            }
        }
}

/** フェーズ1つ分の記入進捗。実体は yaml-core の schema.PhaseProgress（B-3・RB-04 で移設。FQN 互換の別名）。 */
typealias PhaseProgress = schema.PhaseProgress

/**
 * ドキュメントの検証。UI・アプリ状態に依存しない（core / CLI からも使う）。
 * - スキーマ適合（必須／型／enum／日付形式）
 * - 参照解決（索引 + 同一ドキュメント内の未保存 ID を考慮）
 * - ID 重複（同一ドキュメント内）
 * - カバレッジ（スキーマの coverage ルールによる被参照数の充足。索引＝保存済み状態に基づく）
 */
object Validator {

    /** 文書1つの検証（指摘だけ）。判定を保留した検査も要るときは validateDetailed。 */
    fun validate(
        root: YNode?,
        parseError: String?,
        file: Path,
        schema: SchemaDef?,
        indexObj: IdIndex,
        indexed: Boolean = true,
        workspace: Path? = null,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
        allSchemas: List<SchemaDef> = emptyList(),
        relations: Map<String, RelationDef> = emptyMap(),
        enforcement: EnforcementDef = EnforcementDef(),
    ): List<Issue> = validateDetailed(
        root, parseError, file, schema, indexObj, indexed, workspace, loader, allSchemas, relations, enforcement,
    ).issues

    fun validateDetailed(
        root: YNode?,
        parseError: String?,
        file: Path,
        schema: SchemaDef?,
        indexObj: IdIndex,
        /**
         * この文書が索引（台帳）に載っているか（R13-03）。台帳外の文書（ledger 宣言の対象外）では
         * 索引前提の判定（未解決参照・カバレッジ）が成立しないため、未解決参照を情報レベルへ降格し、
         * カバレッジ判定を行わない。単一ファイルで完結する検証（型・必須・enum・フェーズ・ファイル内重複）は
         * そのまま行う（ARCHITECTURE §4.25 のエディタ層）。
         */
        indexed: Boolean = true,
        /** ワークスペースのルート（整合検証 R16-03a で selector の fileGlob 判定に使う相対パス化。null ならパス文字列をそのまま使う）。 */
        workspace: Path? = null,
        /** 整合検証で他文書のツリーを読む loader。既定はディスク（索引と同じ基準）。変更セット適用・ステージングはオーバーレイを渡す。 */
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
        /** 全スキーマ（テンプレート健全性検査 UX-12 が他スキーマの整合ルールを参照するため。空なら自スキーマのみ）。 */
        allSchemas: List<SchemaDef> = emptyList(),
        /** 関係レジストリ（R21-01）: グラフ整合検証（対象型・多重度・非循環）の制約宣言。空なら判定しない。 */
        relations: Map<String, RelationDef> = emptyMap(),
        /** 強制スイッチ（R21-01。ARCHITECTURE §4.31）: 未宣言キーは P20 の挙動のまま。 */
        enforcement: EnforcementDef = EnforcementDef(),
    ): ValidationResult {
        if (root == null) {
            return ValidationResult(listOf(Issue(Severity.ERROR, "YAML パースエラー: ${parseError ?: "不明"}", emptyList())), emptyList())
        }

        val issues = mutableListOf<Issue>()
        // 判定を保留した検査（報告先の文書でだけ集める）
        val withheld = mutableListOf<WithheldCheck>()
        // 他文書のツリー（検証1回の中でキャッシュする。この文書自身は未保存の現在状態＝root を読む）
        val treeCache = mutableMapOf<Path, YNode?>()
        fun treeOf(f: Path): YNode? = if (f == file) root else treeCache.getOrPut(f) { loader(f) }
        fun relOf(f: Path): String =
            workspace?.let { IdIndex.relPathOf(it, f) } ?: f.toString().replace('\\', '/')

        // 1) スキーマ検証（構造検証。yaml-core の StructuralValidator へ委譲＝YM-005。
        //    型・必須・enum・制約語彙・cases・未定義キー・フェーズ申告を StructuralIssue として受け取り、
        //    従来どおり Issue へ写して返す。phaseIndex は後段（整合・パターン制約の activeFrom）でも使う）
        val phaseIndex = schema?.currentPhaseIndex(root)
        schema?.let { s ->
            issues += StructuralValidator.validate(root, s.structural).map { Issue(it.severity, it.message, it.path) }
        }

        // 2) ID / 参照の検証 —— 判定コアはカーネル（SemanticEngine.duplicateIdIssues / referenceIssues。
        //    B-3・RB-06 で単一所有化）。出現の供給は「索引の他文書分＋この文書の現在の状態」の二層（§4.5）で、
        //    台帳外（indexed=false）の INFO 降格・未保存文書の自己文書参照（ownDocumentId）もカーネルが判定する
        val documentId = indexObj.documentIdFor(file, root)
        val (localIds, localRefs) = DocScanner.scan(root, file, schema, documentId)
        val localIdSet = localIds.map { it.id }.toSet()
        val docScan = semantic.DocumentScan(
            document = model.DocumentId(file.toString()),
            documentId = documentId,
            ids = localIds.map { it.toScanned() },
            refs = localRefs.map { it.toScanned() },
        )
        issues += semantic.SemanticEngine.duplicateIdIssues(docScan).map { Issue(it.severity, it.message, it.path) }
        issues += semantic.SemanticEngine.referenceIssues(
            refs = docScan.refs,
            ownDocumentId = documentId,
            occurrences = { tid ->
                localIds.filter { it.id == tid }.map { it.toScanned() } + indexObj.occurrences(tid).map { it.toScanned() }
            },
            hasDocumentId = { indexObj.graph.documents.containsKey(it) },
            indexed = indexed,
        ).map { Issue(it.severity, it.message, it.path) }
        // 3) 詳細文書の存在（スキーマの coverage）は、組込み定義体（builtin-definitions/coverage.yaml）が作る検査になった
        //    （P29 Phase 6 第3段）。不足の指摘・「詳細文書を作成」の対応・詳細文書の側への案内は、下の 3.7 と 6 で
        //    他の検査と同じ規則で評価する
        // 3.5) エンティティ整合 lint（R20-01。ARCHITECTURE §4.30）のうち、スキーマの構造に依存するもの:
        //      idPattern 逸脱（このスキーマのエンティティ型の宣言の一式）と重複所有（所有するフィールド）。
        //      entity 宣言のあるスキーマのIDだけを判定する（宣言のないスキーマは挙動不変＝非破壊）。
        //      浮遊詳細化・定義競合・型競合は「書かれた場所の数」の検査として、組込み定義体
        //      （builtin-definitions/entities.yaml）になった（P29 Phase 6 第3段・意図的差分 D-22。下の 3.7 で評価する）
        if (indexed && schema != null && schema.entities.isNotEmpty()) {
            for (entry in localIds) {
                val decl = schema.entityFor(entry.id)
                if (decl == null) {
                    val patterns = schema.entities.mapNotNull { it.idPattern }
                    issues += Issue(
                        Severity.WARNING,
                        "ID「${entry.id}」はエンティティ型の idPattern（${patterns.joinToString(", ")}）のいずれにも一致しません",
                        entry.path,
                    )
                    continue
                }
                // 重複所有（R23-01。ARCHITECTURE §4.33）: 同一エンティティの2出現が同じフィールドを
                // 所有していたら WARNING（「重複記載しない」運用規約の機械化）。誤検知回避のため、
                // 双方の出現に entity 宣言があり、少なくとも一方が明示的な owns を持つ組だけを判定する
                val myKeys = ownedFieldKeys(schema, root, entry.path, decl)
                if (myKeys.isNotEmpty()) {
                    val others = indexObj.occurrences(entry.id).filter { it.file != file } +
                        localIds.filter { it.id == entry.id && it.path != entry.path }
                    for (other in others) {
                        val otherSchema = (allSchemas.ifEmpty { listOf(schema) }).firstOrNull { it.id == other.schemaId }
                            ?: continue
                        val otherDecl = otherSchema.entityFor(entry.id) ?: continue
                        if (decl.owns == null && otherDecl.owns == null) continue
                        val otherRoot = treeOf(other.file) ?: continue
                        val overlap = myKeys.filter { it in ownedFieldKeys(otherSchema, otherRoot, other.path, otherDecl) }
                        if (overlap.isNotEmpty()) {
                            val where = workspace?.let { IdIndex.relPathOf(it, other.file) } ?: other.file.toString()
                            issues += Issue(
                                Severity.WARNING,
                                "エンティティ「${entry.id}」のフィールド（${overlap.joinToString(", ")}）が" +
                                    " $where の出現でも所有されています（重複所有。片側の owns を絞ってください）",
                                entry.path,
                            )
                        }
                    }
                }
            }
        }
        // 3.6) グラフ整合検証（R21-01。ARCHITECTURE §4.31）: 関係レジストリの非循環（acyclic）と、宣言のない関係名
        //      （enforcement）を、このファイルに座標を持つ指摘として判定する。多重度と対象型は組込み定義体
        //      （builtin-definitions/relations.yaml）が作る検査になり、下の 3.7 で他の検査と同じ規則で評価する（P29 Phase 6 第2段）
        if (indexed) {
            issues += GraphIntegrity.issues(file, indexObj, relations, enforcement)
        }
        // 3.7) 規則の診断と配送（パターン制約 R22-03・網羅マトリクス R28-03・遷移検証 R28-04。ARCHITECTURE §4.32）:
        //      判定は規則×主語IDごとに1回だけ診断（RuleDiagnostics）へ写し、2つの配送先へ届ける（R29-06）。
        //      母集団に入らないID・型未宣言・未解決参照には黙る（判定保留 §4.26・CALC-17）
        // 関係レジストリ（kind: relations の文書）に書かれた語彙から展開された検査は、特定のスキーマに属さない
        // ——ワークスペースのどの文書に定義された要素にも適用する（多重度・対象型。利用者の語彙も同じ）
        // 同じく、共有する定義の置き場（kind: library）に直接書かれた検査と、組込み定義体に直接書かれた検査
        // （エンティティの整合など）も、特定のスキーマに属さない検査として扱う
        val workspaceGroups: List<Pair<String, List<schema.ConstraintRule>>> =
            listOf("(builtin)" to SchemaLoader.builtinRules) +
                allSchemas.filter { it.kind != SchemaKind.DOCUMENT }.map { it.id to it.constraints }
        val registryRules = workspaceGroups.flatMap { it.second }
        // 専用チェッカーを使う検査（SP-13。遷移検証・非循環など）も、同じ単位で扱う
        val checkerGroups: List<Triple<String, SchemaDef?, List<schema.CheckerRule>>> =
            allSchemas.filter { it.kind == SchemaKind.DOCUMENT }.map { Triple(it.id, it, it.checkers) } +
                listOf(Triple("(builtin)", null, SchemaLoader.builtinCheckers)) +
                allSchemas.filter { it.kind != SchemaKind.DOCUMENT }.map { Triple(it.id, null, it.checkers) }
        val checkerLevelOf: (schema.CheckerRule) -> CoverageLevel =
            { rule -> rule.levelFrom?.let { enforcement.levelOf(it) } ?: rule.level }
        // 検査の報告先と活性を決める単位: スキーマの検査（owner あり）と、特定のスキーマに属さない検査（owner なし）
        val ruleGroups: List<Triple<String, SchemaDef?, List<schema.ConstraintRule>>> =
            allSchemas.filter { it.kind == SchemaKind.DOCUMENT }.map { Triple(it.id, it, it.constraints) } +
                workspaceGroups.map { Triple(it.first, null, it.second) }
        // 重大度の切り替え（検査の宣言 levelFrom）: 関係レジストリの強制スイッチが宣言されていれば、その重大度
        val levelOf: (schema.ConstraintRule) -> CoverageLevel =
            { rule -> rule.levelFrom?.let { enforcement.levelOf(it) } ?: rule.level }
        if (indexed) {
            val diagnostics = RuleDiagnostics(indexObj, ::treeOf, ::relOf, relations, allSchemas, levelOf)
            // 報告先: 主語の定義出現（なければ先頭出現）を持つファイルの検証でのみ報告する（多重報告しない）。
            // 規則はこの文書のスキーマの宣言・activeFrom はこの文書のフェーズで判定する（R29-03・CALC-14）
            if (schema != null) {
                val phaseOrder = schema.phases?.order.orEmpty()
                val anchored = localIds.filter { entry ->
                    val anchor = reportAnchorOf(indexObj, entry.id, owner = null)
                    anchor != null && anchor.file == file && anchor.path == entry.path
                }
                // スキーマの検査の報告先は、その検査を宣言したスキーマの文書の中で決める（意図的差分 D-21 ④）
                val anchoredInSchema = localIds.filter { entry ->
                    val anchor = reportAnchorOf(indexObj, entry.id, owner = schema)
                    anchor != null && anchor.file == file && anchor.path == entry.path
                }
                for ((rule, anchored) in schema.constraints.map { it to anchoredInSchema } + registryRules.map { it to anchored }) {
                    if (!semantic.phaseActive(rule.activeFrom, phaseOrder, phaseIndex)) continue
                    if (rule.reportAt?.ref != null) {
                        // 参照の場所へ出す検査（対象型の不一致など）: 見つかった解ごとに、この文書に書かれた参照の場所へ指摘する
                        issues += diagnostics.refIssues(rule, file, localIds.map { it.id }, localRefs.map { it.targetId })
                        continue
                    }
                    if (rule.reportAt != null) {
                        // 値の場所へ出す検査（SP-08。整合検証など）: 定義した場所ではなく、この文書に書かれた要素ごとに、
                        // その値だけを調べて値の場所へ指摘する（他の文書への案内は要らない＝値のある文書が報告先）
                        for (entry in localIds) issues += diagnostics.valueIssues(rule, entry.id, file, entry.path)
                        continue
                    }
                    for (entry in anchored) {
                        diagnostics.constraint(rule, entry.id)?.let { issues += it.reportIssue(entry.path) }
                        diagnostics.withheldReason(rule, entry.id)?.let { reason ->
                            withheld += WithheldCheck(rule.origin, rule.id, rule.description, null, entry.id, entry.path, reason)
                        }
                    }
                }
                // 網羅マトリクス（スキーマの matrix）は、組込み定義体が「すべての要素について」の検査（forAll）へ展開する
                // 語彙になった（P29 Phase 6 第5段）——上のパターン制約と同じ規則で評価・配送される
                // 専用チェッカーを使う検査（遷移検証など）: 報告先の決め方はパターン制約と同じ（意図的差分 D-23 ①）
                val ownCheckers = schema.checkers.map { it to anchoredInSchema }
                val sharedCheckers = checkerGroups.filter { it.second == null }.flatMap { it.third }.map { it to anchored }
                for ((rule, anchoredEntries) in ownCheckers + sharedCheckers) {
                    if (!semantic.phaseActive(rule.activeFrom, phaseOrder, phaseIndex)) continue
                    for (entry in anchoredEntries) {
                        diagnostics.checker(rule, entry.id, checkerLevelOf(rule)).forEach { (_, d) -> issues += d.reportIssue(entry.path) }
                        diagnostics.checkerWithheld(rule, entry.id).forEach { (kind, reason) ->
                            withheld += WithheldCheck(rule.origin, rule.id, rule.description, kind, entry.id, entry.path, reason)
                        }
                    }
                }
            }
            // 参照が書かれた場所へ報告するチェッカー（循環の検出など）: この文書に書かれた参照への指摘
            for ((_, _, rules) in checkerGroups) {
                for (rule in rules) issues += diagnostics.checkerRefIssues(rule, file, checkerLevelOf(rule))
            }
            // 出現先（R28-05・UX-96）: 指摘は定義出現にのみ報告されるため、詳細化出現の文書しか開いていないと
            // 気づけない。定義出現が他文書にあるIDの出現には、定義側で報告されている指摘を案内する
            // （警告級は INFO・エラー級は同格の ERROR。規則と activeFrom は定義出現の文書のスキーマ・フェーズで判定）
            run {
                val noticed = mutableSetOf<Pair<String, String>>()  // (規則キー, ID) の重複案内を防ぐ
                for (entry in localIds) {
                    // パターン制約: 報告先は検査を宣言したスキーマごとに決まる（特定のスキーマに属さない検査は、定義出現か先頭の出現）
                    for ((key, owner, rules) in ruleGroups) {
                        if (rules.isEmpty()) continue
                        val anchor = reportAnchorOf(indexObj, entry.id, owner) ?: continue
                        if (anchor.file == file) continue  // 報告先がこの文書なら処理済み
                        val anchorRel = relOf(anchor.file)
                        val anchorPhase = owner?.currentPhaseIndex(treeOf(anchor.file))
                        for ((i, rule) in rules.withIndex()) {
                            if (rule.reportAt != null) continue
                            if (!semantic.phaseActive(rule.activeFrom, owner?.phases?.order.orEmpty(), anchorPhase)) continue
                            val d = diagnostics.constraint(rule, entry.id) ?: continue
                            if (noticed.add("$key/c$i" to entry.id)) issues += d.noticeIssue(entry.path, anchorRel)
                        }
                    }
                    // 専用チェッカーを使う検査: 同じ規則で、他の出現へ案内する
                    for ((key, owner, rules) in checkerGroups) {
                        if (rules.isEmpty()) continue
                        val anchor = reportAnchorOf(indexObj, entry.id, owner) ?: continue
                        if (anchor.file == file) continue
                        val anchorRel = relOf(anchor.file)
                        val anchorPhase = owner?.currentPhaseIndex(treeOf(anchor.file))
                        for ((i, rule) in rules.withIndex()) {
                            if (!semantic.phaseActive(rule.activeFrom, owner?.phases?.order.orEmpty(), anchorPhase)) continue
                            for ((kind, d) in diagnostics.checker(rule, entry.id, checkerLevelOf(rule))) {
                                if (noticed.add("$key/k$i:$kind" to entry.id)) issues += d.noticeIssue(entry.path, anchorRel)
                            }
                        }
                    }
                }
            }
        }
        // 4) テンプレート健全性（R16-03a / UX-12）: いずれかのスキーマの検査が、この文書に書かれた値を
        //    テンプレートとして展開する（値の式 format。整合検証の expected など）とき、テンプレートのプレースホルダが
        //    同じ要素の値（この文書の要素と、同じ ID が書かれた他の文書の要素）から解決できるかを、書いたその場で点検する。
        //    参照元の要素（lookup の起点）から来る値は、この文書だけからは分からないため対象にしない（§4.26）
        if (indexed) {
            val relSelf = relOf(file)
            val templateReads = (allSchemas.ifEmpty { listOfNotNull(schema) })
                .flatMap { it.constraints }
                .flatMap { rule ->
                    rule.check.flatMap { c -> c.operands.flatMap { it.templateReads() } }
                        .filter { read -> read.inDoc?.matches(schema?.id, relSelf) == true }
                        .map { read -> rule to read }
                }
                .distinct()
            for ((rule, read) in templateReads) {
                for (entry in localIds) {
                    val element = getAt(root, entry.path) ?: continue
                    val raw = scalarText(getAt(element, read.path)) ?: continue
                    if ('{' !in raw) continue
                    val others = indexObj.entries[entry.id].orEmpty()
                        .filter { !(it.file == file && it.path == entry.path) }
                        .mapNotNull { e -> treeOf(e.file)?.let { getAt(it, e.path) } }
                    val eval = Projection.evaluate(raw, listOf(element) + others)
                    eval.resolutions.filter { it.value == null }.forEach { r ->
                        issues += Issue(
                            Severity.WARNING,
                            "プレースホルダ {${r.placeholder}} を導出の入力（値型定義等）から解決できません" +
                                (rule.description?.let { " — $it" } ?: ""),
                            entry.path + read.path,
                        )
                    }
                }
            }

            // 6) 直せる場所への案内（SP-08。検査の宣言 fixSite。旧・出現先への不足案内 UX-15）: この文書のスキーマが
            //    「書き足し先」に指定されている検査について、指摘が出ている ID の一覧を、この文書にも案内する（INFO）。
            //    指摘の本体は報告先の文書に出る。この文書に既に書かれている ID（未保存の追加を含む）は載せない
            val selfSchemaId = schema?.id
            if (selfSchemaId != null) {
                val diagnostics = RuleDiagnostics(indexObj, ::treeOf, ::relOf, relations, allSchemas, levelOf)
                for ((_, owner, rules) in ruleGroups) {
                    for (rule in rules) {
                        val fixSite = rule.fixSite ?: continue
                        if (selfSchemaId !in fixSite.schemas) continue
                        val missing = indexObj.entries.keys.sorted().filter { id ->
                            if (id in localIdSet) return@filter false
                            val anchor = reportAnchorOf(indexObj, id, owner) ?: return@filter false
                            val anchorPhase = owner?.currentPhaseIndex(treeOf(anchor.file))
                            semantic.phaseActive(rule.activeFrom, owner?.phases?.order.orEmpty(), anchorPhase) &&
                                diagnostics.constraint(rule, id) != null
                        }
                        if (missing.isEmpty()) continue
                        issues += Issue(
                            Severity.INFO, diagnostics.fixSiteMessage(fixSite, missing), emptyList(), relatedIds = missing,
                            source = IssueSource(rule.origin, rule.id, null, null, IssueDelivery.FIX_SITE, missing),
                        )
                    }
                }
            }
        }
        // 同じ指摘の重複を除く（出どころは見ない——文面・場所・対応が同じなら、利用者には同じ指摘）
        return ValidationResult(
            issues.distinctBy { listOf(it.severity, it.message, it.path, it.responses, it.relatedIds) },
            withheld.distinct(),
        )
    }

    /**
     * 検査の報告先（意図的差分 D-21 ④）。スキーマの検査（owner = 検査を宣言したスキーマ）は、定義出現がそのスキーマの文書に
     * あればそこ、なければそのスキーマの文書の先頭の出現（そのスキーマの文書に出現がなければ null＝報告しない）。
     * 特定のスキーマに属さない検査（owner = null。関係レジストリの検査）は、定義出現（なければ先頭の出現）。
     */
    fun reportAnchorOf(indexObj: IdIndex, id: String, owner: SchemaDef?): index.IdEntry? {
        val node = indexObj.graph.entities[id] ?: return null
        if (owner == null) return node.defining ?: node.occurrences.firstOrNull()
        return node.defining?.takeIf { it.schemaId == owner.id } ?: node.occurrences.firstOrNull { it.schemaId == owner.id }
    }

    /**
     * ID 1件の「検査の状況」（SP-08）: このスキーマの検査のうち `status` を宣言したもの（詳細文書の存在＝coverage など）について、
     * 満たしているか・不足しているか・フェーズ未到達かを、いまの値つきで返す。判定は検証と同じ経路（RuleDiagnostics）。
     * definingRoot は、この ID がこのスキーマの文書に書かれている、その文書のルート（activeFrom のフェーズ判定に使う。null なら常時判定）。
     */
    fun ruleStatuses(
        id: String,
        schema: SchemaDef,
        indexObj: IdIndex,
        definingRoot: YNode? = null,
        allSchemas: List<SchemaDef> = emptyList(),
        workspace: Path? = null,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
    ): List<RuleStatus> {
        val rules = schema.constraints.filter { it.status != null }
        if (rules.isEmpty()) return emptyList()
        val trees = mutableMapOf<Path, YNode?>()
        val diagnostics = RuleDiagnostics(
            indexObj,
            tree = { f -> trees.getOrPut(f) { loader(f) } },
            rel = { f -> workspace?.let { IdIndex.relPathOf(it, f) } ?: f.toString().replace('\\', '/') },
            allSchemas = allSchemas,
        )
        val phaseIndex = definingRoot?.let { schema.currentPhaseIndex(it) }
        return rules.mapNotNull { rule ->
            diagnostics.status(rule, id, semantic.phaseActive(rule.activeFrom, schema.phases?.order.orEmpty(), phaseIndex))
        }
    }

    /**
     * フェーズ別の記入進捗（requiredFrom を持つフィールドの記入済み／総数）。phases 未宣言のスキーマでは空。
     * 走査の実体は yaml-core の `StructuralSchema.phaseProgress`（B-3・RB-04 で移設。§4.15）。
     */
    fun phaseProgress(root: YNode?, schema: SchemaDef): List<PhaseProgress> =
        schema.structural.phaseProgress(root)

    /**
     * 現在フェーズと記入進捗の1行サマリ（UI・CLI・エージェントの表示で共用）。
     * `phases` 未宣言のスキーマでは null（呼び出し側は表示自体を省略する）。
     */
    fun phaseSummaryText(root: YNode?, schema: SchemaDef): String? =
        schema.structural.phaseSummaryText(root)

    /** カバレッジの数え上げ対象の表示名（参照ベース=「参照」、出現ベース=「出現」）。UI表示で共用する。 */
}
