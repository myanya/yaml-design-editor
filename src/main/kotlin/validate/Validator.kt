package validate

import index.Derivation
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
import schema.ConsistencyAssert
import schema.CoverageRule
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
import schema.requiredAt
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 整合検証の指摘に添付する導出の来歴（R16-03c / UX-13）。
 * UIは指摘クリックで「エラー箇所へ／導出規則へ／入力値へ／導出値を適用」の選択を提示する。
 */
data class IssueDerivation(
    val expected: String,        // 導出値（「導出値を適用」で書き込む値）
    val raw: String?,            // 元値（テンプレート）
    val sourceFile: Path?,       // 導出規則の座標
    val sourcePath: YPath?,
    val inputs: List<Derivation.Input>,  // 入力値の座標（プレースホルダごと）
)

/** detailIn カバレッジ不足の作成導線（R28-05a・UX-97）: このIDの詳細文書をどのスキーマで作れるか。 */
data class IssueScaffold(val id: String, val schemaIds: List<String>)

data class Issue(
    val severity: Severity,
    val message: String,
    val path: YPath,
    /** 整合検証の指摘のみ非null（R16-03c）。導出の来歴と適用可能な修正。 */
    val derivation: IssueDerivation? = null,
    /** 指摘に関連するID（UX-15 の不足案内など）。UIはクリックで各IDの定義へジャンプできる。 */
    val relatedIds: List<String> = emptyList(),
    /** detailIn 不足のみ非null（R28-05a）。UIは「詳細文書を作成」メニューを出す。 */
    val scaffold: IssueScaffold? = null,
    /** 被参照不足（多重度 inMin）の「参照の追加先候補」の文書（UX-100）。UIは開くメニューを出す。 */
    val openFiles: List<java.nio.file.Path> = emptyList(),
)

/** カバレッジルール1件の判定結果（検証と横ぐしビューで共用）。 */
data class CoverageStatus(
    val rule: CoverageRule,
    val id: String,
    val count: Int,
    /** activeFrom のフェーズに達しているか。false のルールは検証エラーにせず「フェーズ未到達」として表示する。 */
    val active: Boolean = true,
) {
    val satisfied: Boolean get() = count >= rule.minCount
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

    fun validate(
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
    ): List<Issue> {
        if (root == null) {
            return listOf(Issue(Severity.ERROR, "YAML パースエラー: ${parseError ?: "不明"}", emptyList()))
        }

        val issues = mutableListOf<Issue>()
        // 他文書のツリー（検証1回の中でキャッシュする。この文書自身は未保存の現在状態＝root を読む）
        val treeCache = mutableMapOf<Path, YNode?>()
        fun treeOf(f: Path): YNode? = if (f == file) root else treeCache.getOrPut(f) { loader(f) }

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
        // 3) カバレッジ（このスキーマが要求する詳細文書の存在＝出現ベース。R29-01 で参照ベースは廃止。
        //    activeFrom はこの文書のフェーズ到達まで判定を保留する）
        if (indexed && schema != null && schema.coverage.isNotEmpty()) {
            for (entry in localIds) {
                for (status in coverageStatuses(entry.id, schema, indexObj, root)) {
                    if (!status.active || status.satisfied) continue
                    val severity = status.rule.level.toSeverity()
                    // detailIn 不足は「詳細文書を作成」導線を付ける（R28-05a・UX-97）
                    val scaffold = status.rule.detailSchemaIds
                        .takeIf { it.isNotEmpty() }
                        ?.let { IssueScaffold(entry.id, it) }
                    issues += Issue(severity, coverageMessage(status, allSchemas), entry.path, scaffold = scaffold)
                }
            }
        }
        // 3.5) エンティティ整合 lint（R20-01。ARCHITECTURE §4.30）: entity 宣言のあるスキーマのIDだけを
        //      ワークスペース横断で判定する（宣言のないスキーマは挙動不変＝非破壊）。
        //      定義出現の一意性は enforcement.definingUniqueness=error で ERROR へ昇格する
        //      （「最初の出現＝定義」ヒューリスティクスの廃止スイッチ。R21-01。§4.31）
        if (indexed && schema != null && schema.entities.isNotEmpty()) {
            val definingSeverity = enforcement.definingUniqueness?.toSeverity() ?: Severity.WARNING
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
                // 出現集合 = 索引の他ファイル分 ＋ この文書の現在の状態（未保存を含む二層判定。§4.5）
                val occs = indexObj.occurrences(entry.id).filter { it.file != file } + localIds.filter { it.id == entry.id }
                val definingCount = occs.count { it.role == EntityRole.DEFINING }
                if (definingCount == 0) {
                    issues += Issue(
                        definingSeverity,
                        "エンティティ型「${decl.type}」のID「${entry.id}」に定義出現がありません（浮遊詳細化。defines: true の文書群への出現が必要です）",
                        entry.path,
                    )
                } else if (definingCount >= 2 && decl.defines) {
                    issues += Issue(
                        definingSeverity,
                        "ID「${entry.id}」の定義出現が $definingCount 件あります（定義競合）",
                        entry.path,
                    )
                }
                val types = occs.mapNotNull { it.entityType }.distinct()
                if (types.size > 1) {
                    issues += Issue(
                        Severity.WARNING,
                        "ID「${entry.id}」が複数のエンティティ型（${types.joinToString(", ")}）で出現しています（型競合）",
                        entry.path,
                    )
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
        // 3.6) グラフ整合検証（R21-01。ARCHITECTURE §4.31）: 関係レジストリの制約（対象型・多重度・非循環）と
        //      未宣言 relation の enforcement をこのファイルに座標を持つ指摘として判定する
        if (indexed) {
            issues += GraphIntegrity.issues(file, indexObj, relations, enforcement, allSchemas)
        }
        // 3.7) 規則の診断と配送（パターン制約 R22-03・網羅マトリクス R28-03・遷移検証 R28-04。ARCHITECTURE §4.32）:
        //      判定は規則×主語IDごとに1回だけ診断（RuleDiagnostics）へ写し、2つの配送先へ届ける（R29-06）。
        //      母集団に入らないID・型未宣言・未解決参照には黙る（判定保留 §4.26・CALC-17）
        if (indexed) {
            val diagnostics = RuleDiagnostics(indexObj, ::treeOf)
            // 報告先: 主語の定義出現（なければ先頭出現）を持つファイルの検証でのみ報告する（多重報告しない）。
            // 規則はこの文書のスキーマの宣言・activeFrom はこの文書のフェーズで判定する（R29-03・CALC-14）
            if (schema != null) {
                val phaseOrder = schema.phases?.order.orEmpty()
                val anchored = localIds.filter { entry ->
                    val node = indexObj.graph.entities[entry.id]
                    val anchor = node?.defining ?: node?.occurrences?.firstOrNull()
                    anchor != null && anchor.file == file && anchor.path == entry.path
                }
                for (rule in schema.constraints) {
                    if (!semantic.phaseActive(rule.activeFrom, phaseOrder, phaseIndex)) continue
                    for (entry in anchored) diagnostics.constraint(rule, entry.id)?.let { issues += it.reportIssue(entry.path) }
                }
                // 網羅マトリクス: 不足セルは行の定義出現に列挙で報告する。あえて対象外のセルは witness 側に
                // excluded: true の行として残す（存在＝充足。UX-16 の流儀）
                for (rule in schema.matrix) {
                    if (!semantic.phaseActive(rule.activeFrom, phaseOrder, phaseIndex)) continue
                    for (entry in anchored) diagnostics.matrix(rule, entry.id)?.let { issues += it.reportIssue(entry.path) }
                }
                for (rule in schema.flow) {
                    if (!semantic.phaseActive(rule.activeFrom, phaseOrder, phaseIndex)) continue
                    for (entry in anchored) diagnostics.flow(rule, entry.id).forEach { (_, d) -> issues += d.reportIssue(entry.path) }
                }
            }
            // 出現先（R28-05・UX-96）: 指摘は定義出現にのみ報告されるため、詳細化出現の文書しか開いていないと
            // 気づけない。定義出現が他文書にあるIDの出現には、定義側で報告されている指摘を案内する
            // （警告級は INFO・エラー級は同格の ERROR。規則と activeFrom は定義出現の文書のスキーマ・フェーズで判定）
            if (allSchemas.isNotEmpty()) {
                val noticed = mutableSetOf<Pair<String, String>>()  // (規則キー, ID) の重複案内を防ぐ
                for (entry in localIds) {
                    val node = indexObj.graph.entities[entry.id] ?: continue
                    val anchor = node.defining ?: node.occurrences.firstOrNull() ?: continue
                    if (anchor.file == file) continue  // 定義がこの文書なら報告先として処理済み
                    val anchorRel = workspace?.let { IdIndex.relPathOf(it, anchor.file) }
                        ?: anchor.file.toString().replace('\\', '/')
                    for (s in allSchemas) {
                        if (s.constraints.isEmpty() && s.matrix.isEmpty() && s.flow.isEmpty()) continue
                        if (!s.matches(anchorRel)) continue  // 定義出現の文書に適用される規則だけを見る
                        val anchorPhase = s.currentPhaseIndex(treeOf(anchor.file))
                        fun activeAt(from: String?): Boolean = semantic.phaseActive(from, s.phases?.order.orEmpty(), anchorPhase)
                        for ((i, rule) in s.constraints.withIndex()) {
                            if (!activeAt(rule.activeFrom)) continue
                            val d = diagnostics.constraint(rule, entry.id) ?: continue
                            if (noticed.add("${s.id}/c$i" to entry.id)) issues += d.noticeIssue(entry.path, anchorRel)
                        }
                        for ((i, rule) in s.matrix.withIndex()) {
                            if (!activeAt(rule.activeFrom)) continue
                            val d = diagnostics.matrix(rule, entry.id) ?: continue
                            if (noticed.add("${s.id}/m$i" to entry.id)) issues += d.noticeIssue(entry.path, anchorRel)
                        }
                        for ((i, rule) in s.flow.withIndex()) {
                            if (!activeAt(rule.activeFrom)) continue
                            for ((kind, d) in diagnostics.flow(rule, entry.id)) {
                                if (noticed.add("${s.id}/f$i:$kind" to entry.id)) issues += d.noticeIssue(entry.path, anchorRel)
                            }
                        }
                    }
                }
            }
        }
        // 4) 整合検証（R16-03a）: ID要素の宣言値が導出値と一致すること（等値判定。ARCHITECTURE §4.26）
        if (indexed) {
            fun relOf(f: Path): String =
                workspace?.let { IdIndex.relPathOf(it, f) } ?: f.toString().replace('\\', '/')

            // 判定コアはカーネル（semantic.SemanticConsistency。B-3・RB-05）——ここは座標・ローダの変換シェル。
            // 来歴（R16-03c）は Path 座標へ写し戻し、UIの「導出規則へ／入力値へ／導出値を適用」に使う
            schema?.takeIf { it.consistency.isNotEmpty() }?.let { sc ->
                issues += semantic.SemanticConsistency.check(
                    rules = sc.consistency,
                    localIds = localIds.map { it.toScanned() },
                    root = root,
                    phaseOrder = sc.phases?.order.orEmpty(),
                    currentPhaseIndex = phaseIndex,
                    occurrences = { id -> indexObj.entries[id].orEmpty().map { it.toScanned() } },
                    rel = { d -> relOf(Path.of(d.value)) },
                    tree = { d -> treeOf(Path.of(d.value)) },
                ).map { f ->
                    Issue(
                        f.severity, f.message, f.path,
                        derivation = IssueDerivation(
                            expected = f.expected,
                            raw = f.raw,
                            sourceFile = f.sourceDocument?.let { Path.of(it.value) },
                            sourcePath = f.sourcePath,
                            inputs = f.inputs.map { i ->
                                Derivation.Input(i.placeholder, i.value, i.document?.let { d -> Path.of(d.value) }, i.path)
                            },
                        ),
                    )
                }
            }

            // 5) テンプレート健全性（R16-03a / UX-12）: いずれかのスキーマの整合ルールがこの文書を
            //    導出元（expected.source）に選んでいるとき、テンプレートのプレースホルダが導出の入力
            //    （自要素・values で選んだ同一IDの出現・同一IDの他出現）から解決できるかを書いたその場で検査する。
            //    lookup 起点（対象要素）由来のプレースホルダは静的に判定できない（§4.26）
            val relSelf = relOf(file)
            val sourceRules = (allSchemas.ifEmpty { listOfNotNull(schema) })
                .flatMap { it.consistency }
                .filter { it.expected.source.matches(schema?.id, relSelf) }
            if (sourceRules.isNotEmpty()) {
                fun elementOf(e: IdEntry): YNode? = treeOf(e.file)?.let { getAt(it, e.path) }
                for (rule in sourceRules) {
                    for (entry in localIds) {
                        val element = getAt(root, entry.path) ?: continue
                        val raw = scalarText(getAt(element, rule.expected.path)) ?: continue
                        if ('{' !in raw) continue
                        val occs = indexObj.entries[entry.id].orEmpty()
                        // values の出現選択は実際の導出と同じ σ 選択方針（定義出現優先。CALC-19・D-17）を共有する
                        val scanned = occs.map { it.toScanned() }
                        val valueScopes = rule.expected.values.mapNotNull { sel ->
                            semantic.SemanticExpression.select(scanned, sel) { d -> relOf(Path.of(d.value)) }
                                ?.let { s -> treeOf(Path.of(s.document.value))?.let { getAt(it, s.path) } }
                        }
                        val otherScopes = occs
                            .filter { !(it.file == file && it.path == entry.path) }
                            .mapNotNull(::elementOf)
                        val eval = Projection.evaluate(raw, listOf(element) + valueScopes + otherScopes)
                        eval.resolutions.filter { it.value == null }.forEach { r ->
                            issues += Issue(
                                Severity.WARNING,
                                "プレースホルダ {${r.placeholder}} を導出の入力（値型定義等）から解決できません" +
                                    (rule.description?.let { " — $it" } ?: ""),
                                entry.path + rule.expected.path,
                            )
                        }
                    }
                }
            }

            // 6) 出現先への不足案内（UX-15）: 他スキーマのカバレッジ（出現ベース detailIn）がこの文書の
            //    スキーマを出現先に指定しているとき、不足している対象IDをこの文書側にも案内する
            //    （指摘の本体は定義側文書に出る。ここは「この文書に行を追加できる」という向きの反転の案内＝INFO）。
            //    この文書への未保存追加（localIds）は充足とみなすため、行を書いたその場で案内が消える
            val selfSchemaId = schema?.id
            if (selfSchemaId != null) {
                for (owner in allSchemas) {
                    for (rule in owner.coverage) {
                        if (selfSchemaId !in rule.detailSchemaIds) continue
                        val rulePattern = rule.idPattern
                        val missing = indexObj.entries.asSequence()
                            .filter { (id, occs) ->
                                occs.any { it.schemaId == owner.id } &&
                                    (rulePattern == null || idMatchesPattern(id, rulePattern)) &&
                                    occs.count { it.schemaId in rule.detailSchemaIds } < rule.minCount &&
                                    id !in localIdSet
                            }
                            .map { it.key }.sorted().toList()
                        if (missing.isEmpty()) continue
                        val shown = missing.take(5).joinToString(", ") +
                            (if (missing.size > 5) " 他${missing.size - 5}件" else "")
                        issues += Issue(
                            Severity.INFO,
                            "この文書のスキーマを出現先とするカバレッジで不足しているIDが ${missing.size} 件あります: $shown" +
                                (rule.description?.let { "（$it）" } ?: ""),
                            emptyList(),
                            relatedIds = missing,
                        )
                    }
                }
            }
        }
        return issues.distinct()
    }

    /**
     * ID 1件に適用されるカバレッジルールの判定（出現ベース detailIn のみ。R29-01 で参照ベースは廃止）:
     * このIDの、指定スキーマの文書への出現＝詳細文書の存在を数える（R7-02）。
     * definingRoot は ID を定義している文書のルート（activeFrom のフェーズ判定に使う。null なら常時判定）。
     * 判定コアはカーネル（semantic.SemanticCoverage。B-3・RB-03a）——ここはこの ID の出現を
     * カーネル座標へ写し、フェーズの現在位置を解決して渡す変換シェル。
     */
    fun coverageStatuses(id: String, schema: SchemaDef, indexObj: IdIndex, definingRoot: YNode? = null): List<CoverageStatus> =
        semantic.SemanticCoverage.statuses(
            id = id,
            rules = schema.coverage,
            occurrences = indexObj.occurrences(id).map { it.toScanned() },
            phaseOrder = schema.phases?.order.orEmpty(),
            currentPhaseIndex = definingRoot?.let { schema.currentPhaseIndex(it) },
        ).map { CoverageStatus(it.rule, it.id, it.count, it.active) }

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

    /**
     * カバレッジ指摘の1行メッセージ。allSchemas を渡すと、詳細文書の**作成場所**（詳細スキーマの
     * match パターン）を併記する（「どこに作ればよいか」の案内。R28-05・UX-97）。
     */
    fun coverageMessage(status: CoverageStatus, allSchemas: List<SchemaDef> = emptyList()): String = buildString {
        append("ID「${status.id}」の詳細文書が不足しています（現在 ${status.count} 件／必要 ${status.rule.minCount} 件")
        val detail = status.rule.detailSchemaIds.joinToString(", ") { sid ->
            allSchemas.firstOrNull { it.id == sid }?.match?.let { "$sid（$it に作成）" } ?: sid
        }
        append("、詳細スキーマ: $detail")
        append("）")
        status.rule.description?.let { append(" — $it") }
    }

    /** カバレッジの数え上げ対象の表示名（参照ベース=「参照」、出現ベース=「出現」）。UI表示で共用する。 */
}
