package validate

import index.DocScanner
import index.IdIndex
import index.idMatchesPattern
import index.isAllowedBy
import model.YNode
import model.YPath
import model.pretty
import model.scalarText
import schema.CoverageLevel
import schema.CoverageRule
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import schema.UnknownFieldsPolicy
import schema.effectiveFields
import java.nio.file.Path

enum class Severity { ERROR, WARNING }

data class Issue(
    val severity: Severity,
    val message: String,
    val path: YPath,
)

/** カバレッジルール1件の判定結果（検証と横ぐしビューで共用）。 */
data class CoverageStatus(
    val rule: CoverageRule,
    val id: String,
    val count: Int,
) {
    val satisfied: Boolean get() = count >= rule.minCount
}

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
    ): List<Issue> {
        if (root == null) {
            return listOf(Issue(Severity.ERROR, "YAML パースエラー: ${parseError ?: "不明"}", emptyList()))
        }

        val issues = mutableListOf<Issue>()

        // 1) スキーマ検証
        schema?.let { checkField(it.root, root, emptyList(), issues, it) }

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
        // 未解決参照
        for (r in localRefs) {
            val localMatches = localIds.any { it.id == r.targetId && it.isAllowedBy(r) }
            val resolved = localMatches || indexObj.resolve(r).isNotEmpty()
            if (!resolved) {
                val existsOutsideScope = r.targetId in localIdSet || indexObj.entries.containsKey(r.targetId)
                issues += Issue(
                    Severity.ERROR,
                    if (existsOutsideScope) "参照「${r.targetId}」はパターンまたはスコープの対象外です"
                    else "参照「${r.targetId}」（${r.path.pretty()}）を解決できません",
                    r.path,
                )
            }
        }
        // 3) カバレッジ（このスキーマが要求する被参照数）
        if (schema != null && schema.coverage.isNotEmpty()) {
            for (entry in localIds) {
                for (status in coverageStatuses(entry.id, schema, indexObj)) {
                    if (status.satisfied) continue
                    val severity = if (status.rule.level == CoverageLevel.ERROR) Severity.ERROR else Severity.WARNING
                    issues += Issue(severity, coverageMessage(status), entry.path)
                }
            }
        }
        return issues
    }

    /** ID 1件に適用されるカバレッジルールの判定。索引（保存済み状態）の参照を数える。 */
    fun coverageStatuses(id: String, schema: SchemaDef, indexObj: IdIndex): List<CoverageStatus> =
        schema.coverage.mapNotNull { rule ->
            if (rule.idPattern != null && !idMatchesPattern(id, rule.idPattern)) return@mapNotNull null
            val count = indexObj.refs.count { ref ->
                ref.targetId == id &&
                    (rule.fromSchemaIds.isEmpty() || ref.sourceSchemaId in rule.fromSchemaIds) &&
                    (rule.relation == null || ref.relation == rule.relation)
            }
            CoverageStatus(rule, id, count)
        }

    fun coverageMessage(status: CoverageStatus): String = buildString {
        append("ID「${status.id}」への参照が不足しています（現在 ${status.count} 件／必要 ${status.rule.minCount} 件")
        if (status.rule.fromSchemaIds.isNotEmpty()) append("、参照元スキーマ: ${status.rule.fromSchemaIds.joinToString(", ")}")
        status.rule.relation?.let { append("、関係: $it") }
        append("）")
        status.rule.description?.let { append(" — $it") }
    }

    private fun checkField(field: FieldDef, node: YNode?, path: YPath, issues: MutableList<Issue>, schema: schema.SchemaDef) {
        val f = schema.resolve(field) ?: field
        val label = f.displayLabel
        if (node == null) {
            if (f.required) issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
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
                        checkField(ch, node.entries[key], path + key, issues, schema)
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
                        checkField(itemDef.copy(label = "$label[$i]"), child, path + i, issues, schema)
                    }
                }
            }
            FieldType.MAP -> {
                if (node !is YNode.Map) {
                    issues += Issue(Severity.ERROR, "「$label」はMapである必要があります", path)
                } else {
                    val valueDef = f.value ?: return
                    node.entries.forEach { (key, value) ->
                        checkField(valueDef.copy(label = "$label.$key"), value, path + key, issues, schema)
                    }
                }
            }
            FieldType.ENUM -> {
                val v = scalarText(node)
                if (v == null) {
                    if (f.required) issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
                } else if (v !in f.values) {
                    issues += Issue(Severity.ERROR, "「$label」の値「$v」は選択肢 ${f.values} に含まれていません", path)
                }
            }
            FieldType.INT -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Int || v is Long || (v is String && v.toLongOrNull() != null)
                if (!ok) issues += Issue(Severity.ERROR, "「$label」は整数である必要があります（現在値: $v）", path)
            }
            FieldType.NUMBER -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Number || (v is String && v.toDoubleOrNull() != null)
                if (!ok) issues += Issue(Severity.ERROR, "「$label」は数値である必要があります（現在値: $v）", path)
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
                if (v == null && f.required) {
                    issues += Issue(Severity.ERROR, "必須項目「$label」（ID）が未設定です", path)
                }
            }
            FieldType.REF, FieldType.STRING, FieldType.TEXT -> {
                if (node !is YNode.Scalar) {
                    issues += Issue(Severity.WARNING, "「$label」はスカラー値を想定しています", path)
                } else if (f.required && scalarText(node) == null) {
                    issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
                }
            }
        }
    }
}
