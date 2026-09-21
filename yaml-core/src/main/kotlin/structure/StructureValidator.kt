package structure

import model.YNode
import model.YPath
import model.scalarText
import schema.UnknownFieldsPolicy
import validate.Severity
import validate.StructuralIssue
import validate.StructuralIssueKind

/**
 * 文書1つを、最小の構造のスキーマで検証する（P27 段C の C-1）。
 * 見るのは、値の形・必須・値の形式的なチェック・選択肢の値ごとの項目・未定義の項目だけ——その値1つと、その項目の決まりだけを見て決まるもの。
 * ID・参照・フェーズを知らない（フェーズに達したら必須は、導出のときに素の必須へ畳まれている）。
 * 指摘の文面・種類・重大度は、以前の検証（利用者が書いたスキーマの木をそのまま読む StructuralValidator）と同じ（違いは意図的差分 D-30）。
 */
object StructureValidator {

    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    fun validate(root: YNode, schema: StructureSchema): List<StructuralIssue> {
        val issues = mutableListOf<StructuralIssue>()
        checkField(schema.root, root, emptyList(), issues, schema)
        return issues
    }

    private fun numText(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    /** 数値の範囲。値が数値として読めるときだけ検査する。 */
    private fun checkRange(f: StructureField, label: String, v: Any?, path: YPath, issues: MutableList<StructuralIssue>) {
        if (f.min == null && f.max == null) return
        val n = (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull() ?: return
        f.min?.let { limit ->
            if (n < limit) issues += StructuralIssue(Severity.ERROR, "「$label」は ${numText(limit)} 以上で入力してください（現在値: $v）", path, StructuralIssueKind.RANGE)
        }
        f.max?.let { limit ->
            if (n > limit) issues += StructuralIssue(Severity.ERROR, "「$label」は ${numText(limit)} 以下で入力してください（現在値: $v）", path, StructuralIssueKind.RANGE)
        }
    }

    private fun unknownSeverity(f: StructureField): Severity? = when (f.unknownFields ?: UnknownFieldsPolicy.WARNING) {
        UnknownFieldsPolicy.ALLOW -> null
        UnknownFieldsPolicy.WARNING -> Severity.WARNING
        UnknownFieldsPolicy.ERROR -> Severity.ERROR
    }

    private fun checkField(field: StructureField, node: YNode?, path: YPath, issues: MutableList<StructuralIssue>, schema: StructureSchema) {
        val f = schema.resolve(field) ?: field
        val label = f.displayLabel
        if (node == null) {
            if (f.required) issues += StructuralIssue(Severity.ERROR, "必須項目「$label」が未設定です", path, StructuralIssueKind.REQUIRED)
            return
        }
        // 値の形ごとの決まり: 値の形（スカラー・オブジェクト・配列）を見て、決まりを1つ選ぶ
        f.shapes?.let { shapes ->
            val chosen = when (node) {
                is YNode.Scalar -> shapes.scalar
                is YNode.Map -> shapes.obj
                is YNode.Seq -> shapes.array
            }
            if (chosen == null) {
                val forms = listOfNotNull(
                    "スカラー".takeIf { shapes.scalar != null }, "オブジェクト".takeIf { shapes.obj != null }, "配列".takeIf { shapes.array != null },
                )
                val text = if (forms.size == 2) "${forms[0]}か${forms[1]}のどちらかで書きます" else "${forms.joinToString("・")}で書きます"
                issues += StructuralIssue(Severity.WARNING, "「$label」は、$text", path, StructuralIssueKind.TYPE)
            } else {
                // 必須と表示名は、外側の項目のものを引き継ぐ
                checkField(chosen.copy(key = f.key, label = f.label ?: chosen.label, required = f.required), node, path, issues, schema)
            }
            return
        }
        when (f.type) {
            ValueType.OBJECT -> {
                if (node !is YNode.Map) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」はオブジェクトである必要があります", path, StructuralIssueKind.TYPE)
                } else {
                    val effective = schema.effectiveFields(f.fields, node)
                    for (ch in effective.fields) {
                        val key = ch.key ?: continue
                        checkField(ch, node.entries[key], path + key, issues, schema)
                    }
                    val definedKeys = effective.fields.mapNotNull { it.key }.toSet()
                    val unknownKeys = node.entries.keys.filter { it !in definedKeys && !(path.isEmpty() && it == "_document") }
                    val severity = unknownSeverity(f)
                    if (severity != null) {
                        unknownKeys.forEach { key ->
                            val inactive = effective.inactive[key]
                            issues += if (inactive != null) {
                                StructuralIssue(
                                    severity,
                                    "「$key」は「${inactive.discriminator.displayLabel}」が ${inactive.values.joinToString(" / ")} のときのみ使用できます",
                                    path + key,
                                    StructuralIssueKind.INACTIVE_KEY,
                                )
                            } else {
                                StructuralIssue(severity, "スキーマに定義されていない項目です: $key", path + key, StructuralIssueKind.UNKNOWN_KEY)
                            }
                        }
                    }
                }
            }
            ValueType.ARRAY -> {
                if (node !is YNode.Seq) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」は配列である必要があります", path, StructuralIssueKind.TYPE)
                } else {
                    val itemDef = f.item ?: return
                    node.items.forEachIndexed { i, child ->
                        checkField(itemDef.copy(label = "$label[$i]"), child, path + i, issues, schema)
                    }
                }
            }
            ValueType.MAP -> {
                if (node !is YNode.Map) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」はMapである必要があります", path, StructuralIssueKind.TYPE)
                } else {
                    val valueDef = f.value ?: return
                    node.entries.forEach { (key, value) ->
                        checkField(valueDef.copy(label = "$label.$key"), value, path + key, issues, schema)
                    }
                }
            }
            ValueType.ENUM -> {
                val v = scalarText(node)
                if (v == null) {
                    if (f.required) issues += StructuralIssue(Severity.ERROR, "必須項目「$label」が未設定です", path, StructuralIssueKind.REQUIRED)
                } else if (v !in f.values) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」の値「$v」は選択肢 ${f.values} に含まれていません", path, StructuralIssueKind.ENUM)
                }
            }
            ValueType.INT -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Int || v is Long || (v is String && v.toLongOrNull() != null)
                if (!ok) issues += StructuralIssue(Severity.ERROR, "「$label」は整数である必要があります（現在値: $v）", path, StructuralIssueKind.TYPE)
                else checkRange(f, label, v, path, issues)
            }
            ValueType.NUMBER -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Number || (v is String && v.toDoubleOrNull() != null)
                if (!ok) issues += StructuralIssue(Severity.ERROR, "「$label」は数値である必要があります（現在値: $v）", path, StructuralIssueKind.TYPE)
                else checkRange(f, label, v, path, issues)
            }
            ValueType.BOOLEAN -> {
                val v = (node as? YNode.Scalar)?.value
                if (v != null && v !is Boolean) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」は真偽値である必要があります（現在値: $v）", path, StructuralIssueKind.TYPE)
                }
            }
            ValueType.DATE -> {
                val v = scalarText(node)
                if (v != null && !DATE_RE.matches(v)) {
                    issues += StructuralIssue(Severity.WARNING, "「$label」は YYYY-MM-DD 形式を推奨します（現在値: $v）", path, StructuralIssueKind.FORMAT)
                }
            }
            ValueType.STRING, ValueType.TEXT -> {
                if (node !is YNode.Scalar) {
                    issues += StructuralIssue(Severity.WARNING, "「$label」はスカラー値を想定しています", path, StructuralIssueKind.TYPE)
                } else if (f.required && scalarText(node) == null) {
                    issues += StructuralIssue(Severity.ERROR, "必須項目「$label」が未設定です", path, StructuralIssueKind.REQUIRED)
                } else {
                    // 文字数・正規表現（値が入っているときだけ検査する）
                    val text = scalarText(node)
                    if (text != null) {
                        f.minLength?.let { limit ->
                            if (text.length < limit) {
                                issues += StructuralIssue(Severity.ERROR, "「$label」は $limit 文字以上で入力してください（現在 ${text.length} 文字）", path, StructuralIssueKind.LENGTH)
                            }
                        }
                        f.maxLength?.let { limit ->
                            if (text.length > limit) {
                                issues += StructuralIssue(Severity.ERROR, "「$label」は $limit 文字以内で入力してください（現在 ${text.length} 文字）", path, StructuralIssueKind.LENGTH)
                            }
                        }
                        f.pattern?.let { p ->
                            // 正規表現として読めない形式は、スキーマの定義の検証が指摘するため、ここでは黙って飛ばす
                            runCatching { Regex(p) }.getOrNull()?.let { re ->
                                if (!re.matches(text)) {
                                    issues += StructuralIssue(Severity.ERROR, "「$label」は形式（$p）に一致していません（現在値: $text）", path, StructuralIssueKind.FORMAT)
                                }
                            }
                        }
                    }
                }
            }
            // any は「検証しない」の明示。必須（書かれていない）だけが手前で効く
            ValueType.ANY -> Unit
        }
    }
}
