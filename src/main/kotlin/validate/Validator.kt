package validate

import index.DocScanner
import index.IdEntry
import index.IdIndex
import index.idMatchesPattern
import index.isAllowedBy
import index.toKernelView
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

// 指摘の配り方（IssueDelivery）と指摘の出どころ（IssueSource）は、カーネル（workspace-core の validate/WorkspaceChecks.kt）にある

data class Issue(
    val severity: Severity,
    val message: String,
    val path: YPath,
    /** エラー時の対応（SP-09）。検査の定義が宣言したものを、宣言順に持つ。 */
    val responses: List<IssueResponse> = emptyList(),
    /** 指摘に関連するID（UX-15 の不足案内など）。UIはクリックで各IDの定義へジャンプできる。 */
    val relatedIds: List<String> = emptyList(),
    /**
     * 指摘の出どころ。定義体の検査から出た指摘も、コードで実装された検査（構造の検証・参照の解決など）の指摘も持つ
     * （後者はカーネルの CodedChecks が作る。P27 段B の残り・意図的差分 D-29）。
     */
    val source: IssueSource? = null,
)

// 判定を保留した検査（WithheldCheck）と検査の状況（RuleStatus）も、カーネル（workspace-core の validate/WorkspaceChecks.kt）にある

/** 文書1つの検証の結果: 指摘と、判定を保留した検査。 */
data class ValidationResult(val issues: List<Issue>, val withheld: List<WithheldCheck>)

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
        shareEvaluations: Boolean = false,
    ): List<Issue> = validateDetailed(
        root, parseError, file, schema, indexObj, indexed, workspace, loader, allSchemas, relations, enforcement, shareEvaluations,
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
        /**
         * 検査の評価を、同じ索引の他の文書の検証と共有する（P27 段B。全体を回す経路用）。true にしてよいのは、root と loader の中身が
         * indexObj と同じ内容のときだけ（ディスクの索引とディスクの内容・未保存の編集を重ねた索引と未保存を優先する loader・
         * ステージングの索引とステージングの内容）。索引に重ねていない内容（提案のプレビューなど）を検証するときは false のままにする。
         */
        shareEvaluations: Boolean = false,
    ): ValidationResult {
        if (root == null) {
            val source = CodedChecks.source(CodedChecks.YAML, "parse-error", IssueDelivery.REPORT, emptyList(), emptyList())
            return ValidationResult(
                listOf(Issue(Severity.ERROR, "YAML パースエラー: ${parseError ?: "不明"}", emptyList(), source = source)), emptyList(),
            )
        }

        val issues = mutableListOf<Issue>()
        // 判定を保留した検査（集める文書は、検査ごとに1つに決まる。WithheldCheck）
        val withheld = mutableListOf<WithheldCheck>()
        // 他文書のツリー（検証1回の中でキャッシュする。この文書自身は未保存の現在状態＝root を読む）
        val treeCache = mutableMapOf<Path, YNode?>()
        fun treeOf(f: Path): YNode? = if (f == file) root else treeCache.getOrPut(f) { loader(f) }
        fun relOf(f: Path): String =
            workspace?.let { IdIndex.relPathOf(it, f) } ?: f.toString().replace('\\', '/')

        // 0) この文書の現在の状態の走査（ID と参照）。指摘の出どころの「対象の要素」（指摘の場所を含む最も内側の ID を持つ要素）も
        //    ここから決める（コードで実装された検査の出どころ＝カーネルの CodedChecks。意図的差分 D-29）
        val documentId = indexObj.documentIdFor(file, root)
        val (localIds, localRefs) = DocScanner.scan(root, file, schema, documentId)
        val docScan = semantic.DocumentScan(
            document = model.DocumentId(file.toString()),
            documentId = documentId,
            ids = localIds.map { it.toScanned() },
            refs = localRefs.map { it.toScanned() },
        )
        fun coded(at: String, kind: String, delivery: IssueDelivery, path: YPath) =
            CodedChecks.source(at, kind, delivery, docScan.ids, path)

        // 1) スキーマ検証（構造検証。yaml-core の StructuralValidator へ委譲＝YM-005。
        //    型・必須・enum・制約語彙・cases・未定義キー・フェーズ申告を StructuralIssue として受け取り、Issue へ写して返す）
        schema?.let { s ->
            issues += StructuralValidator.validate(root, s.structural).map {
                Issue(it.severity, it.message, it.path, source = CodedChecks.structural(it, docScan.ids))
            }
        }

        // 2) ID / 参照の検証 —— 判定コアはカーネル（SemanticEngine.duplicateIdIssues / referenceIssues。
        //    B-3・RB-06 で単一所有化）。出現の供給は「索引の他文書分＋この文書の現在の状態」の二層（§4.5）で、
        //    台帳外（indexed=false）の INFO 降格・未保存文書の自己文書参照（ownDocumentId）もカーネルが判定する
        issues += semantic.SemanticEngine.duplicateIdIssues(docScan).map {
            Issue(it.severity, it.message, it.path, source = CodedChecks.semantic(it, docScan.ids))
        }
        issues += semantic.SemanticEngine.referenceIssues(
            refs = docScan.refs,
            ownDocumentId = documentId,
            occurrences = { tid ->
                localIds.filter { it.id == tid }.map { it.toScanned() } + indexObj.occurrences(tid).map { it.toScanned() }
            },
            hasDocumentId = { indexObj.graph.documents.containsKey(it) },
            indexed = indexed,
        ).map { Issue(it.severity, it.message, it.path, source = CodedChecks.semantic(it, docScan.ids)) }
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
                        source = coded(CodedChecks.ENTITY, "id-pattern", IssueDelivery.REPORT, entry.path),
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
                                source = coded(CodedChecks.ENTITY, "duplicate-ownership", IssueDelivery.REPORT, entry.path),
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
            issues += GraphIntegrity.issues(file, indexObj, relations, enforcement, docScan.ids)
        }
        // 3.7) 定義体の検査（パターン制約・詳細文書の存在・整合・多重度・対象型・非循環・網羅マトリクス・遷移検証・利用者の検査）:
        //      評価と、指摘を文書へ配る規則（報告先・他の出現への案内・値と参照の場所・直せる場所への案内）は、
        //      カーネルの validate.WorkspaceChecks が持つ（P27 段B-3。ARCHITECTURE §4.41）。ここは、この文書の分を受け取って
        //      ファイルの座標（Path）の Issue へ写すだけ。母集団に入らないID・型未宣言・未解決参照には黙る（§4.26・CALC-17）
        var fixSiteIssues = emptyList<Issue>()
        if (indexed) {
            val view = indexObj.toKernelView()
            // 検査の評価の覚え書き: 共有するなら索引のもの、しないならこの検証1回の中だけのもの
            val session = if (shareEvaluations) view.checkSession else semantic.SemanticCheckSession(view.entries, view.refs, view.graph)
            val checkSchemas = allSchemas.map { it.toCheckSchema() }
            val self = schema?.let { s -> allSchemas.indexOfFirst { it === s }.takeIf { it >= 0 }?.let(checkSchemas::get) ?: s.toCheckSchema() }
            val checks = WorkspaceChecks(
                checkSchemas, relations, enforcement, view.entries, view.refs, view.graph, session,
                rel = { d -> relOf(Path.of(d.value)) },
                tree = { d -> treeOf(Path.of(d.value)) },
            )
            val delivery = checks.deliver(view.documentIdOf(file), self, root, docScan.ids, docScan.refs)
            val delivered = delivery.issues.map { it.toIssue() }
            // 直せる場所への案内は、今までどおり、テンプレート健全性の指摘の後ろに並べる
            issues += delivered.filter { it.source?.delivery != IssueDelivery.FIX_SITE }
            fixSiteIssues = delivered.filter { it.source?.delivery == IssueDelivery.FIX_SITE }
            withheld += delivery.withheld
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
                            source = coded(CodedChecks.TEMPLATE, "unresolved-placeholder", IssueDelivery.VALUE, entry.path + read.path),
                        )
                    }
                }
            }

            // 6) 直せる場所への案内（SP-08。検査の宣言 fixSite）: カーネルから受け取ったものを、ここへ並べる
            issues += fixSiteIssues
        }
        // 同じ指摘の重複を除く（出どころは見ない——文面・場所・対応が同じなら、利用者には同じ指摘）
        return ValidationResult(
            issues.distinctBy { listOf(it.severity, it.message, it.path, it.responses, it.relatedIds) },
            withheld.distinct(),
        )
    }

    /**
     * 検査の報告先（意図的差分 D-21 ④）。決め方の実体はカーネルの WorkspaceChecks.reportAnchorOf（同じ規則）。
     * スキーマの検査（owner = 検査を宣言したスキーマ）は、定義出現がそのスキーマの文書にあればそこ、なければそのスキーマの文書の
     * 先頭の出現（無ければ null＝報告しない）。特定のスキーマに属さない検査（owner = null）は、定義出現（なければ先頭の出現）。
     */
    fun reportAnchorOf(indexObj: IdIndex, id: String, owner: SchemaDef?): index.IdEntry? {
        val node = indexObj.graph.entities[id] ?: return null
        if (owner == null) return node.defining ?: node.occurrences.firstOrNull()
        return node.defining?.takeIf { it.schemaId == owner.id } ?: node.occurrences.firstOrNull { it.schemaId == owner.id }
    }

    /**
     * ID 1件の「検査の状況」（SP-08）: このスキーマの検査のうち `status` を宣言したもの（詳細文書の存在＝coverage など）について、
     * 満たしているか・不足しているか・保留か・フェーズ未到達かを、いまの値つきで返す。判定は検証と同じ経路（カーネルの WorkspaceChecks）。
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
        if (schema.constraints.none { it.status != null }) return emptyList()
        val trees = mutableMapOf<Path, YNode?>()
        val view = indexObj.toKernelView()
        val checks = WorkspaceChecks(
            allSchemas.map { it.toCheckSchema() }, emptyMap(), EnforcementDef(), view.entries, view.refs, view.graph,
            semantic.SemanticCheckSession(view.entries, view.refs, view.graph),
            rel = { d -> Path.of(d.value).let { f -> workspace?.let { IdIndex.relPathOf(it, f) } ?: f.toString().replace('\\', '/') } },
            tree = { d -> Path.of(d.value).let { f -> trees.getOrPut(f) { loader(f) } } },
        )
        return checks.statuses(id, schema.toCheckSchema(), definingRoot)
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

/** 検査を配るのに要る情報だけを、カーネルの形へ写す（検査は同じものをそのまま渡す——評価の覚え書きは、同じものかどうかで見分ける）。 */
internal fun SchemaDef.toCheckSchema(): CheckSchema =
    CheckSchema(id, kind, match, structural, entities, constraints, checkers)

/** カーネルの指摘を、ファイルの座標（Path）の Issue へ写す。 */
internal fun CheckIssue.toIssue(): Issue = Issue(
    severity, message, path,
    responses = responses.map { r ->
        when (r) {
            is CheckResponse.Jump -> IssueResponse.Jump(r.label, Path.of(r.document.value), r.path, r.note)
            is CheckResponse.ApplyValue -> IssueResponse.ApplyValue(r.label, r.value)
            is CheckResponse.CreateDetail -> IssueResponse.CreateDetail(r.label, r.schemaId, r.id, r.note)
        }
    },
    relatedIds = relatedIds,
    source = source,
)
