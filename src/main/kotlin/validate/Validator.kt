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
import schema.CoverageLevel
import schema.CoverageRule
import schema.EntityRole
import schema.FieldDef
import schema.FieldType
import schema.entityFor
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

enum class Severity { ERROR, WARNING, INFO }

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

    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")

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
    ): List<Issue> {
        if (root == null) {
            return listOf(Issue(Severity.ERROR, "YAML パースエラー: ${parseError ?: "不明"}", emptyList()))
        }

        val issues = mutableListOf<Issue>()

        // 1) スキーマ検証（フェーズ申告があれば requiredFrom の判定に反映する）
        val phaseIndex = schema?.currentPhaseIndex(root)
        schema?.phases?.let { phases ->
            val declared = schema.currentPhaseValue(root)
            if (declared != null && declared !in phases.order) {
                issues += Issue(
                    Severity.WARNING,
                    "フェーズ「$declared」は phases（${phases.order.joinToString(" → ")}）に定義されていません（先頭フェーズとして扱います）",
                    listOf(phases.key),
                )
            }
        }
        schema?.let { checkField(it.root, root, emptyList(), issues, it, phaseIndex) }

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
        //      ワークスペース横断で判定する（宣言のないスキーマは挙動不変＝非破壊。すべて WARNING）
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
                // 出現集合 = 索引の他ファイル分 ＋ この文書の現在の状態（未保存を含む二層判定。§4.5）
                val occs = indexObj.occurrences(entry.id).filter { it.file != file } + localIds.filter { it.id == entry.id }
                val definingCount = occs.count { it.role == EntityRole.DEFINING }
                if (definingCount == 0) {
                    issues += Issue(
                        Severity.WARNING,
                        "エンティティ型「${decl.type}」のID「${entry.id}」に定義出現がありません（浮遊詳細化。defines: true の文書群への出現が必要です）",
                        entry.path,
                    )
                } else if (definingCount >= 2 && decl.defines) {
                    issues += Issue(
                        Severity.WARNING,
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
                    if (rule.idPattern != null && !idMatchesPattern(entry.id, rule.idPattern)) continue
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
            val count = if (rule.isOccurrence) {
                indexObj.occurrences(id).count { it.schemaId in rule.detailSchemaIds }
            } else indexObj.refs.count { ref ->
                ref.targetId == id &&
                    (rule.fromSchemaIds.isEmpty() || ref.sourceSchemaId in rule.fromSchemaIds) &&
                    (rule.relation == null || ref.relation == rule.relation)
            }
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

    /** 数値の表示（整数なら小数点を出さない）。制約メッセージで使う。 */
    private fun numText(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    /** 数値の範囲制約（R16-01: min / max）。値が数値として読めるときだけ検査する。 */
    private fun checkRange(f: FieldDef, label: String, v: Any?, path: YPath, issues: MutableList<Issue>) {
        if (f.min == null && f.max == null) return
        val n = (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull() ?: return
        f.min?.let { limit ->
            if (n < limit) issues += Issue(Severity.ERROR, "「$label」は ${numText(limit)} 以上で入力してください（現在値: $v）", path)
        }
        f.max?.let { limit ->
            if (n > limit) issues += Issue(Severity.ERROR, "「$label」は ${numText(limit)} 以下で入力してください（現在値: $v）", path)
        }
    }

    private fun checkField(field: FieldDef, node: YNode?, path: YPath, issues: MutableList<Issue>, schema: schema.SchemaDef, phaseIndex: Int?) {
        val f = schema.resolve(field) ?: field
        val label = f.displayLabel
        val required = schema.requiredAt(f, phaseIndex)
        if (node == null) {
            if (required) issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
            return
        }
        when (f.type) {
            FieldType.OBJECT -> {
                if (node !is YNode.Map) {
                    issues += Issue(Severity.ERROR, "「$label」はオブジェクトである必要があります", path)
                } else {
                    val effective = schema.effectiveFields(f.fields, node)
                    for (ch in effective.fields) {
                        val key = ch.key ?: continue
                        checkField(ch, node.entries[key], path + key, issues, schema, phaseIndex)
                    }
                    val definedKeys = effective.fields.mapNotNull { it.key }.toSet()
                    val unknownKeys = node.entries.keys.filter {
                        it !in definedKeys && !(path.isEmpty() && it == "_document")
                    }
                    val severity = when (f.unknownFields ?: UnknownFieldsPolicy.WARNING) {
                        UnknownFieldsPolicy.ALLOW -> null
                        UnknownFieldsPolicy.WARNING -> Severity.WARNING
                        UnknownFieldsPolicy.ERROR -> Severity.ERROR
                    }
                    if (severity != null) {
                        unknownKeys.forEach { key ->
                            val inactive = effective.inactive[key]
                            issues += if (inactive != null) {
                                Issue(
                                    severity,
                                    "「$key」は「${inactive.discriminator.displayLabel}」が ${inactive.values.joinToString(" / ")} のときのみ使用できます",
                                    path + key,
                                )
                            } else {
                                Issue(severity, "スキーマに定義されていない項目です: $key", path + key)
                            }
                        }
                    }
                }
            }
            FieldType.ARRAY -> {
                if (node !is YNode.Seq) {
                    issues += Issue(Severity.ERROR, "「$label」は配列である必要があります", path)
                } else {
                    val itemDef = f.item ?: return
                    node.items.forEachIndexed { i, child ->
                        checkField(itemDef.copy(label = "$label[$i]"), child, path + i, issues, schema, phaseIndex)
                    }
                }
            }
            FieldType.MAP -> {
                if (node !is YNode.Map) {
                    issues += Issue(Severity.ERROR, "「$label」はMapである必要があります", path)
                } else {
                    val valueDef = f.value ?: return
                    node.entries.forEach { (key, value) ->
                        checkField(valueDef.copy(label = "$label.$key"), value, path + key, issues, schema, phaseIndex)
                    }
                }
            }
            FieldType.ENUM -> {
                val v = scalarText(node)
                if (v == null) {
                    if (required) issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
                } else if (v !in f.values) {
                    issues += Issue(Severity.ERROR, "「$label」の値「$v」は選択肢 ${f.values} に含まれていません", path)
                }
            }
            FieldType.INT -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Int || v is Long || (v is String && v.toLongOrNull() != null)
                if (!ok) issues += Issue(Severity.ERROR, "「$label」は整数である必要があります（現在値: $v）", path)
                else checkRange(f, label, v, path, issues)
            }
            FieldType.NUMBER -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Number || (v is String && v.toDoubleOrNull() != null)
                if (!ok) issues += Issue(Severity.ERROR, "「$label」は数値である必要があります（現在値: $v）", path)
                else checkRange(f, label, v, path, issues)
            }
            FieldType.BOOLEAN -> {
                val v = (node as? YNode.Scalar)?.value
                if (v != null && v !is Boolean) {
                    issues += Issue(Severity.ERROR, "「$label」は真偽値である必要があります（現在値: $v）", path)
                }
            }
            FieldType.DATE -> {
                val v = scalarText(node)
                if (v != null && !DATE_RE.matches(v)) {
                    issues += Issue(Severity.WARNING, "「$label」は YYYY-MM-DD 形式を推奨します（現在値: $v）", path)
                }
            }
            FieldType.ID -> {
                val v = scalarText(node)
                if (v == null && required) {
                    issues += Issue(Severity.ERROR, "必須項目「$label」（ID）が未設定です", path)
                }
            }
            FieldType.REF -> {
                if (f.fields.isNotEmpty() && node is YNode.Map) {
                    // 属性付き参照（R20-03。ARCHITECTURE §4.30）: {to, 属性...} を object の流儀で検証する。
                    // to の解決検証は参照走査（DocScanner の RefEntry）が従来の ref と同一に担う
                    if (scalarText(node.entries["to"]) == null) {
                        issues += Issue(Severity.ERROR, "「$label」の to（参照値）が未設定です", path + "to")
                    }
                    val effective = schema.effectiveFields(f.fields, node)
                    for (ch in effective.fields) {
                        val key = ch.key ?: continue
                        checkField(ch, node.entries[key], path + key, issues, schema, phaseIndex)
                    }
                    val definedKeys = effective.fields.mapNotNull { it.key }.toSet() + "to"
                    val severity = when (f.unknownFields ?: UnknownFieldsPolicy.WARNING) {
                        UnknownFieldsPolicy.ALLOW -> null
                        UnknownFieldsPolicy.WARNING -> Severity.WARNING
                        UnknownFieldsPolicy.ERROR -> Severity.ERROR
                    }
                    if (severity != null) {
                        node.entries.keys.filter { it !in definedKeys }.forEach { key ->
                            issues += Issue(severity, "スキーマに定義されていない項目です: $key", path + key)
                        }
                    }
                } else if (node !is YNode.Scalar) {
                    issues += Issue(
                        Severity.WARNING,
                        if (f.fields.isNotEmpty()) "「$label」は参照ID（スカラー）または {to, 属性} のオブジェクトを想定しています"
                        else "「$label」はスカラー値を想定しています",
                        path,
                    )
                } else if (required && scalarText(node) == null) {
                    issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
                }
            }
            FieldType.STRING, FieldType.TEXT -> {
                if (node !is YNode.Scalar) {
                    issues += Issue(Severity.WARNING, "「$label」はスカラー値を想定しています", path)
                } else if (required && scalarText(node) == null) {
                    issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
                } else {
                    // 制約語彙（R16-01）: 文字数・正規表現（値が入っているときだけ検査する）
                    val text = scalarText(node)
                    if (text != null) {
                        f.minLength?.let { limit ->
                            if (text.length < limit) {
                                issues += Issue(Severity.ERROR, "「$label」は $limit 文字以上で入力してください（現在 ${text.length} 文字）", path)
                            }
                        }
                        f.maxLength?.let { limit ->
                            if (text.length > limit) {
                                issues += Issue(Severity.ERROR, "「$label」は $limit 文字以内で入力してください（現在 ${text.length} 文字）", path)
                            }
                        }
                        f.pattern?.let { p ->
                            // 不正な正規表現はメタスキーマ検証（validateDefinition）が指摘するため、ここでは黙って飛ばす
                            runCatching { Regex(p) }.getOrNull()?.let { re ->
                                if (!re.matches(text)) {
                                    issues += Issue(Severity.ERROR, "「$label」は形式（$p）に一致していません（現在値: $text）", path)
                                }
                            }
                        }
                    }
                }
            }
            // any は「検証しない」の明示（R11-02）。必須チェック（node == null）だけが手前で効く
            FieldType.ANY -> Unit
        }
    }
}
