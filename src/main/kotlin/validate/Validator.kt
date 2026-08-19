package validate

import index.Derivation
import index.DocScanner
import index.IdEntry
import index.IdIndex
import index.idMatchesPattern
import index.isAllowedBy
import model.YNode
import model.YPath
import model.getAt
import model.pretty
import model.scalarText
import schema.ConsistencyAssert
import schema.ConstraintExpect
import schema.CoverageLevel
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

data class Issue(
    val severity: Severity,
    val message: String,
    val path: YPath,
    /** 整合検証の指摘のみ非null（R16-03c）。導出の来歴と適用可能な修正。 */
    val derivation: IssueDerivation? = null,
    /** 指摘に関連するID（UX-15 の不足案内など）。UIはクリックで各IDの定義へジャンプできる。 */
    val relatedIds: List<String> = emptyList(),
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

/** フェーズ1つ分の記入進捗（requiredFrom を持つフィールドの記入済み／総数）。 */
data class PhaseProgress(val phase: String, val filled: Int, val total: Int)

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

        // 1) スキーマ検証（構造検証。yaml-core の StructuralValidator へ委譲＝YM-005。
        //    型・必須・enum・制約語彙・cases・未定義キー・フェーズ申告を StructuralIssue として受け取り、
        //    従来どおり Issue へ写して返す。phaseIndex は後段（整合・パターン制約の activeFrom）でも使う）
        val phaseIndex = schema?.currentPhaseIndex(root)
        schema?.let { s ->
            issues += StructuralValidator.validate(root, s.structural).map { Issue(it.severity, it.message, it.path) }
        }

        // 2) ID / 参照の検証
        val documentId = indexObj.documentIdFor(file, root)
        val (localIds, localRefs) = DocScanner.scan(root, file, schema, documentId)
        val localIdSet = localIds.map { it.id }.toSet()

        // ドキュメント内の ID 重複
        localIds.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, list) ->
            list.forEach { e ->
                issues += Issue(Severity.ERROR, "ID「$id」がこのファイル内で重複しています", e.path)
            }
        }
        // 未解決参照（台帳外の文書では索引に載らないため、情報レベルへ降格する。R13-03）
        for (r in localRefs) {
            // 文書参照（R21-03。§4.31）: 文書ノード表（documentId ＝ _document.id ＞ 相対パス）に対して解決する。
            // エンティティの名前空間には混ぜない
            if (r.targetKind == RefTarget.DOCUMENT) {
                val resolvedDoc = r.targetId == documentId || indexObj.graph.documents.containsKey(r.targetId)
                if (!resolvedDoc) {
                    issues += if (!indexed) {
                        Issue(Severity.INFO, "文書参照「${r.targetId}」は台帳外の文書のため解決されません", r.path)
                    } else {
                        Issue(Severity.ERROR, "文書参照「${r.targetId}」を解決できません（_document.id または相対パス）", r.path)
                    }
                }
                continue
            }
            val localMatches = localIds.any { it.id == r.targetId && it.isAllowedBy(r) }
            val resolved = localMatches || indexObj.resolve(r).isNotEmpty()
            if (!resolved) {
                if (!indexed) {
                    issues += Issue(Severity.INFO, "参照「${r.targetId}」は台帳外の文書のため解決されません", r.path)
                    continue
                }
                val existsOutsideScope = r.targetId in localIdSet || indexObj.entries.containsKey(r.targetId)
                issues += Issue(
                    Severity.ERROR,
                    if (existsOutsideScope) "参照「${r.targetId}」はパターンまたはスコープの対象外です"
                    else "参照「${r.targetId}」（${r.path.pretty()}）を解決できません",
                    r.path,
                )
            }
        }
        // 3) カバレッジ（このスキーマが要求する被参照数。activeFrom はこの文書のフェーズ到達まで判定を保留する）
        if (indexed && schema != null && schema.coverage.isNotEmpty()) {
            for (entry in localIds) {
                for (status in coverageStatuses(entry.id, schema, indexObj, root)) {
                    if (!status.active || status.satisfied) continue
                    val severity = if (status.rule.level == CoverageLevel.ERROR) Severity.ERROR else Severity.WARNING
                    issues += Issue(severity, coverageMessage(status), entry.path)
                }
            }
        }
        // 3.5) エンティティ整合 lint（R20-01。ARCHITECTURE §4.30）: entity 宣言のあるスキーマのIDだけを
        //      ワークスペース横断で判定する（宣言のないスキーマは挙動不変＝非破壊）。
        //      定義出現の一意性は enforcement.definingUniqueness=error で ERROR へ昇格する
        //      （「最初の出現＝定義」ヒューリスティクスの廃止スイッチ。R21-01。§4.31）
        if (indexed && schema != null && schema.entities.isNotEmpty()) {
            val definingSeverity =
                if (enforcement.definingUniqueness == CoverageLevel.ERROR) Severity.ERROR else Severity.WARNING
            val ownershipTrees = mutableMapOf<Path, YNode?>()
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
                        val otherRoot = (if (other.file == file) root
                        else ownershipTrees.getOrPut(other.file) { loader(other.file) }) ?: continue
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
            issues += GraphIntegrity.issues(file, indexObj, relations, enforcement)
        }
        // 3.7) パターン制約（R22-03。ARCHITECTURE §4.32）: 起点束縛に一致する各エンティティについて
        //      パターンの存在（exists）／不在（absent）を判定する。座標は起点の定義出現（なければ先頭出現）で、
        //      その出現を持つファイルの検証でのみ報告する（多重報告しない）。母集団に入らないID・型未宣言・
        //      未解決参照には黙る（判定保留 §4.26）
        if (indexed && schema != null && schema.constraints.isNotEmpty()) {
            fun constraintTree(f: Path): YNode? = if (f == file) root else loader(f)
            for (rule in schema.constraints) {
                val active = rule.activeFrom?.let { from ->
                    val fromIdx = schema.phases?.order?.indexOf(from) ?: -1
                    fromIdx < 0 || phaseIndex == null || phaseIndex >= fromIdx
                } ?: true
                if (!active) continue
                for (entry in localIds) {
                    val node = indexObj.graph.entities[entry.id] ?: continue
                    val anchor = node.defining ?: node.occurrences.firstOrNull() ?: continue
                    if (anchor.file != file || anchor.path != entry.path) continue
                    if (!index.Query.startMatches(indexObj, rule.spec, entry.id, ::constraintTree)) continue
                    val hasSolution = index.Query.anyMatchFrom(indexObj, rule.spec, entry.id, ::constraintTree)
                    val violated = when (rule.expect) {
                        ConstraintExpect.EXISTS -> !hasSolution
                        ConstraintExpect.ABSENT -> hasSolution
                    }
                    if (!violated) continue
                    val severity = if (rule.level == CoverageLevel.ERROR) Severity.ERROR else Severity.WARNING
                    val message = when (rule.expect) {
                        ConstraintExpect.EXISTS -> "ID「${entry.id}」を起点とするパターンが成立していません（expect: exists）"
                        ConstraintExpect.ABSENT -> "ID「${entry.id}」を起点とするパターンが成立しています（expect: absent）"
                    } + ((rule.description ?: rule.id)?.let { " — $it" } ?: "")
                    issues += Issue(severity, message, entry.path)
                }
            }
        }
        // 4) 整合検証（R16-03a）: ID要素の宣言値が導出値と一致すること（等値判定。ARCHITECTURE §4.26）
        if (indexed) {
            val trees = mutableMapOf<Path, YNode?>()
            fun tree(f: Path): YNode? = if (f == file) root else trees.getOrPut(f) { loader(f) }
            fun relOf(f: Path): String =
                workspace?.let { IdIndex.relPathOf(it, f) } ?: f.toString().replace('\\', '/')

            schema?.consistency?.forEach { rule ->
                // activeFrom: カバレッジと同じく自文書のフェーズ到達まで判定を保留する
                val active = rule.activeFrom?.let { from ->
                    val fromIdx = schema.phases?.order?.indexOf(from) ?: -1
                    fromIdx < 0 || phaseIndex == null || phaseIndex >= fromIdx
                } ?: true
                if (!active) return@forEach
                for (entry in localIds) {
                    val rulePattern = rule.idPattern
                    if (rulePattern != null && !idMatchesPattern(entry.id, rulePattern)) continue
                    val element = getAt(root, entry.path) ?: continue
                    // 検査する値が未記入なら判定しない（未記入は required の領分。二重計上を避ける）
                    val actual = scalarText(getAt(element, rule.path)) ?: continue
                    val result = Derivation.resolve(rule.expected, entry.id, indexObj, ::relOf, ::tree)
                    val expected = result.value
                    // 期待値が評価できない（写像行なし・プレースホルダ未解決）ときは判定保留（§4.26）
                    if (expected == null || !result.complete) continue
                    // 判定（R16-03b）: equals は等値、lte / gte は数値としての包含（数値でなければ判定保留）
                    val satisfied: Boolean? = when (rule.assert) {
                        ConsistencyAssert.EQUALS -> actual == expected
                        ConsistencyAssert.LTE, ConsistencyAssert.GTE -> {
                            val a = actual.toDoubleOrNull()
                            val e = expected.toDoubleOrNull()
                            if (a == null || e == null) null
                            else if (rule.assert == ConsistencyAssert.LTE) a <= e else a >= e
                        }
                    }
                    if (satisfied == false) {
                        val severity = if (rule.level == CoverageLevel.ERROR) Severity.ERROR else Severity.WARNING
                        val relationText = when (rule.assert) {
                            ConsistencyAssert.EQUALS -> "導出値「$expected」と一致しません"
                            ConsistencyAssert.LTE -> "導出値「$expected」以下である必要があります"
                            ConsistencyAssert.GTE -> "導出値「$expected」以上である必要があります"
                        }
                        issues += Issue(
                            severity,
                            "「${rule.path.pretty()}」の値「$actual」は$relationText" +
                                (rule.description?.let { " — $it" } ?: ""),
                            entry.path + rule.path,
                            // 来歴（R16-03c）: UIの「導出規則へ／入力値へ／導出値を適用」に使う
                            derivation = IssueDerivation(
                                expected = expected,
                                raw = result.raw,
                                sourceFile = result.sourceFile,
                                sourcePath = result.sourcePath,
                                inputs = result.inputs,
                            ),
                        )
                    }
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
                fun elementOf(e: IdEntry): YNode? = tree(e.file)?.let { getAt(it, e.path) }
                for (rule in sourceRules) {
                    for (entry in localIds) {
                        val element = getAt(root, entry.path) ?: continue
                        val raw = scalarText(getAt(element, rule.expected.path)) ?: continue
                        if ('{' !in raw) continue
                        val occs = indexObj.entries[entry.id].orEmpty()
                        val valueScopes = rule.expected.values.mapNotNull { sel ->
                            occs.firstOrNull { sel.matches(it.schemaId, relOf(it.file)) }?.let(::elementOf)
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
                        val missing = indexObj.entries.asSequence()
                            .filter { (id, occs) ->
                                occs.any { it.schemaId == owner.id } &&
                                    (rule.idPattern == null || idMatchesPattern(id, rule.idPattern)) &&
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
     * ID 1件に適用されるカバレッジルールの判定。索引（保存済み状態）の参照または出現を数える。
     * - 参照ベース（requiredBy）: このIDへの参照を数える
     * - 出現ベース（detailIn）: このIDの、指定スキーマの文書への出現＝詳細文書の存在を数える（R7-02）
     * definingRoot は ID を定義している文書のルート（activeFrom のフェーズ判定に使う。null なら常時判定）。
     */
    fun coverageStatuses(id: String, schema: SchemaDef, indexObj: IdIndex, definingRoot: YNode? = null): List<CoverageStatus> =
        schema.coverage.mapNotNull { rule ->
            if (rule.idPattern != null && !idMatchesPattern(id, rule.idPattern)) return@mapNotNull null
            // 数え上げはグラフ整合（R21-01）と共有する: requiredBy = 入次数・detailIn = 詳細化出現（§4.31）
            val count = if (rule.isOccurrence) {
                GraphIntegrity.occurrenceCount(indexObj, id, rule.detailSchemaIds)
            } else GraphIntegrity.inboundRefs(indexObj, id, rule.relation, rule.fromSchemaIds).size
            val active = rule.activeFrom?.let { from ->
                val fromIdx = schema.phases?.order?.indexOf(from) ?: -1
                val current = definingRoot?.let { schema.currentPhaseIndex(it) }
                fromIdx < 0 || current == null || current >= fromIdx
            } ?: true
            CoverageStatus(rule, id, count, active)
        }

    /**
     * フェーズ別の記入進捗（requiredFrom を持つフィールドの記入済み／総数）。phases 未宣言のスキーマでは空。
     * 未来フェーズの未記入はエラーではなくこの進捗として可視化する（ARCHITECTURE §4.15）。
     * 存在しないオブジェクト配下は総数に数えるが、存在しない配列要素・Map項目は数えない（構造が確定してから数える）。
     */
    fun phaseProgress(root: YNode?, schema: SchemaDef): List<PhaseProgress> {
        val phases = schema.phases ?: return emptyList()
        val filled = IntArray(phases.order.size)
        val total = IntArray(phases.order.size)
        fun present(node: YNode?): Boolean = when (node) {
            null -> false
            is YNode.Scalar -> scalarText(node) != null
            else -> true
        }
        fun walk(field: FieldDef, node: YNode?) {
            val f = schema.resolve(field) ?: field
            f.requiredFrom?.let { from ->
                val idx = phases.order.indexOf(from)
                if (idx >= 0) {
                    total[idx]++
                    if (present(node)) filled[idx]++
                }
            }
            when (f.type) {
                FieldType.OBJECT -> {
                    val map = node as? YNode.Map
                    if (node != null && map == null) return
                    for (ch in schema.effectiveFields(f.fields, map).fields) {
                        val key = ch.key ?: continue
                        walk(ch, map?.entries?.get(key))
                    }
                }
                FieldType.ARRAY -> (node as? YNode.Seq)?.items?.forEach { child -> f.item?.let { walk(it, child) } }
                FieldType.MAP -> (node as? YNode.Map)?.entries?.values?.forEach { v -> f.value?.let { walk(it, v) } }
                else -> Unit
            }
        }
        walk(schema.root, root)
        return phases.order.mapIndexed { i, p -> PhaseProgress(p, filled[i], total[i]) }
    }

    /**
     * 現在フェーズと記入進捗の1行サマリ（UI・CLI・エージェントの表示で共用）。
     * `phases` 未宣言のスキーマでは null（呼び出し側は表示自体を省略する）。
     */
    fun phaseSummaryText(root: YNode?, schema: SchemaDef): String? {
        val phases = schema.phases ?: return null
        val current = schema.currentPhaseValue(root)?.takeIf { it in phases.order } ?: phases.order.firstOrNull()
        val parts = phaseProgress(root, schema).filter { it.total > 0 }
            .joinToString(", ") { "${it.phase} ${it.filled}/${it.total}" }
        return "phase=$current" + (if (parts.isNotBlank()) "（$parts）" else "")
    }

    fun coverageMessage(status: CoverageStatus): String = buildString {
        if (status.rule.isOccurrence) {
            append("ID「${status.id}」の詳細文書が不足しています（現在 ${status.count} 件／必要 ${status.rule.minCount} 件")
            append("、詳細スキーマ: ${status.rule.detailSchemaIds.joinToString(", ")}")
        } else {
            append("ID「${status.id}」への参照が不足しています（現在 ${status.count} 件／必要 ${status.rule.minCount} 件")
            if (status.rule.fromSchemaIds.isNotEmpty()) append("、参照元スキーマ: ${status.rule.fromSchemaIds.joinToString(", ")}")
            status.rule.relation?.let { append("、関係: $it") }
        }
        append("）")
        status.rule.description?.let { append(" — $it") }
    }

    /** カバレッジの数え上げ対象の表示名（参照ベース=「参照」、出現ベース=「出現」）。UI表示で共用する。 */
    fun coverageCountLabel(rule: CoverageRule): String = if (rule.isOccurrence) "出現" else "参照"
}
