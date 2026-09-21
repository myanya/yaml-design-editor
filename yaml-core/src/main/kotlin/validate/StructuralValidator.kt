package validate

import model.YNode
import model.YPath
import model.scalarText
import schema.FieldDef
import schema.FieldType
import schema.StructuralSchema
import schema.UnknownFieldsPolicy

/**
 * 単一文書×構造スキーマの検証（3層コア移行 Phase 3・YM-005で Validator から分離）。
 * IdIndex・ワークスペース・ファイルI/Oに依存しない（ADR-0002）。
 * 参照の存在確認・カバレッジ・グラフ整合・整合ルールは意味検証（ルート側 `Validator`）が担う。
 *
 * **本番の経路からは呼ばれない**（P27 段C の C-1・意図的差分 D-30）: 構造の検証は、利用者が書いたスキーマから導出した最小の構造のスキーマ
 * （structure.StructureValidator。入口は workspace-core の DerivedStructures）で行う。ここは、導出した経路の結果を比べる相手として
 * テスト（StructureParityTest・StructuralDerivationTransformTest）が使うだけで、段C の C-2 で利用者の木（FieldDef）と一緒に yaml-core から無くなる。
 * 検証の決まりを変えるときは、こちらではなく structure.StructureValidator を直す。
 */
object StructuralValidator {

    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** スキーマ適合（必須／型／enum／制約語彙／cases／未定義キー／フェーズ申告）を検証する。 */
    fun validate(root: YNode, schema: StructuralSchema): List<StructuralIssue> {
        val issues = mutableListOf<StructuralIssue>()
        val phaseIndex = schema.currentPhaseIndex(root)
        schema.phases?.let { phases ->
            val declared = schema.currentPhaseValue(root)
            if (declared != null && declared !in phases.order) {
                issues += StructuralIssue(
                    Severity.WARNING,
                    "フェーズ「$declared」は phases（${phases.order.joinToString(" → ")}）に定義されていません（先頭フェーズとして扱います）",
                    listOf(phases.key),
                    StructuralIssueKind.PHASE,
                )
            }
        }
        checkField(schema.root, root, emptyList(), issues, schema, phaseIndex)
        return issues
    }

    private fun numText(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    /** 数値の範囲制約（R16-01: min / max）。値が数値として読めるときだけ検査する。 */
    private fun checkRange(f: FieldDef, label: String, v: Any?, path: YPath, issues: MutableList<StructuralIssue>) {
        if (f.min == null && f.max == null) return
        val n = (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull() ?: return
        f.min?.let { limit ->
            if (n < limit) issues += StructuralIssue(Severity.ERROR, "「$label」は ${numText(limit)} 以上で入力してください（現在値: $v）", path, StructuralIssueKind.RANGE)
        }
        f.max?.let { limit ->
            if (n > limit) issues += StructuralIssue(Severity.ERROR, "「$label」は ${numText(limit)} 以下で入力してください（現在値: $v）", path, StructuralIssueKind.RANGE)
        }
    }

    private fun checkField(field: FieldDef, node: YNode?, path: YPath, issues: MutableList<StructuralIssue>, schema: StructuralSchema, phaseIndex: Int?) {
        val f = schema.resolve(field) ?: field
        val label = f.displayLabel
        val required = schema.requiredAt(f, phaseIndex)
        if (node == null) {
            if (required) issues += StructuralIssue(Severity.ERROR, "必須項目「$label」が未設定です", path, StructuralIssueKind.REQUIRED)
            return
        }
        when (f.type) {
            FieldType.OBJECT -> {
                if (node !is YNode.Map) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」はオブジェクトである必要があります", path, StructuralIssueKind.TYPE)
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
            FieldType.ARRAY -> {
                if (node !is YNode.Seq) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」は配列である必要があります", path, StructuralIssueKind.TYPE)
                } else {
                    val itemDef = f.item ?: return
                    node.items.forEachIndexed { i, child ->
                        checkField(itemDef.copy(label = "$label[$i]"), child, path + i, issues, schema, phaseIndex)
                    }
                }
            }
            FieldType.MAP -> {
                if (node !is YNode.Map) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」はMapである必要があります", path, StructuralIssueKind.TYPE)
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
                    if (required) issues += StructuralIssue(Severity.ERROR, "必須項目「$label」が未設定です", path, StructuralIssueKind.REQUIRED)
                } else if (v !in f.values) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」の値「$v」は選択肢 ${f.values} に含まれていません", path, StructuralIssueKind.ENUM)
                }
            }
            FieldType.INT -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Int || v is Long || (v is String && v.toLongOrNull() != null)
                if (!ok) issues += StructuralIssue(Severity.ERROR, "「$label」は整数である必要があります（現在値: $v）", path, StructuralIssueKind.TYPE)
                else checkRange(f, label, v, path, issues)
            }
            FieldType.NUMBER -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Number || (v is String && v.toDoubleOrNull() != null)
                if (!ok) issues += StructuralIssue(Severity.ERROR, "「$label」は数値である必要があります（現在値: $v）", path, StructuralIssueKind.TYPE)
                else checkRange(f, label, v, path, issues)
            }
            FieldType.BOOLEAN -> {
                val v = (node as? YNode.Scalar)?.value
                if (v != null && v !is Boolean) {
                    issues += StructuralIssue(Severity.ERROR, "「$label」は真偽値である必要があります（現在値: $v）", path, StructuralIssueKind.TYPE)
                }
            }
            FieldType.DATE -> {
                val v = scalarText(node)
                if (v != null && !DATE_RE.matches(v)) {
                    issues += StructuralIssue(Severity.WARNING, "「$label」は YYYY-MM-DD 形式を推奨します（現在値: $v）", path, StructuralIssueKind.FORMAT)
                }
            }
            FieldType.ID -> {
                val v = scalarText(node)
                if (v == null && required) {
                    issues += StructuralIssue(Severity.ERROR, "必須項目「$label」（ID）が未設定です", path, StructuralIssueKind.REQUIRED)
                }
                // IDフォーマットの強制（UX-79）: pattern 制約（R16-01）を ID 型にも適用する
                if (v != null) {
                    f.pattern?.let { p ->
                        runCatching { Regex(p) }.getOrNull()?.let { re ->
                            if (!re.matches(v)) {
                                issues += StructuralIssue(Severity.ERROR, "「$label」は形式（$p）に一致していません（現在値: $v）", path, StructuralIssueKind.FORMAT)
                            }
                        }
                    }
                }
            }
            FieldType.REF -> {
                if (f.fields.isNotEmpty() && node is YNode.Map) {
                    // 属性付き参照（R20-03。ARCHITECTURE §4.30）: {to, 属性...} を object の流儀で検証する。
                    // to の解決検証は参照走査（DocScanner の RefEntry）が従来の ref と同一に担う
                    if (scalarText(node.entries["to"]) == null) {
                        issues += StructuralIssue(Severity.ERROR, "「$label」の to（参照値）が未設定です", path + "to", StructuralIssueKind.REQUIRED)
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
                            issues += StructuralIssue(severity, "スキーマに定義されていない項目です: $key", path + key, StructuralIssueKind.UNKNOWN_KEY)
                        }
                    }
                } else if (node !is YNode.Scalar) {
                    issues += StructuralIssue(
                        Severity.WARNING,
                        if (f.fields.isNotEmpty()) "「$label」は参照ID（スカラー）または {to, 属性} のオブジェクトを想定しています"
                        else "「$label」はスカラー値を想定しています",
                        path,
                        StructuralIssueKind.TYPE,
                    )
                } else if (required && scalarText(node) == null) {
                    issues += StructuralIssue(Severity.ERROR, "必須項目「$label」が未設定です", path, StructuralIssueKind.REQUIRED)
                }
            }
            FieldType.STRING, FieldType.TEXT -> {
                if (node !is YNode.Scalar) {
                    issues += StructuralIssue(Severity.WARNING, "「$label」はスカラー値を想定しています", path, StructuralIssueKind.TYPE)
                } else if (required && scalarText(node) == null) {
                    issues += StructuralIssue(Severity.ERROR, "必須項目「$label」が未設定です", path, StructuralIssueKind.REQUIRED)
                } else {
                    // 制約語彙（R16-01）: 文字数・正規表現（値が入っているときだけ検査する）
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
                            // 不正な正規表現はメタスキーマ検証（validateDefinition）が指摘するため、ここでは黙って飛ばす
                            runCatching { Regex(p) }.getOrNull()?.let { re ->
                                if (!re.matches(text)) {
                                    issues += StructuralIssue(Severity.ERROR, "「$label」は形式（$p）に一致していません（現在値: $text）", path, StructuralIssueKind.FORMAT)
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
