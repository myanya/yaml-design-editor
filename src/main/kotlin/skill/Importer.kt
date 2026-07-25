package skill

import core.ChangeSet
import core.ChangeSets
import core.FileChange
import core.PatchOp
import core.Workspace
import model.YNode
import model.getAt
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * インポート（R5-02）: 表形式データを skill 定義のマッピングで変更セットへ変換する。
 * - 新規行は文書の作成、既存ファイルは**マップした項目だけの set 操作**（値が変わる場合のみ）
 *   → 人間がYAML側に追記した項目を再インポートが消さない（取り込んだ時点からYAMLが原本）
 * - 適用は P1 の `Workspace.apply` を再利用（baseハッシュ競合検出・オール・オア・ナッシング・検証ゲート）
 * - エクスポート→再インポートは空の変更セットになる（往復の無損失を機械確認できる）
 */
object Importer {

    data class Preview(
        val changeSet: ChangeSet,
        val created: List<String>,     // 新規作成されるファイル
        val updated: List<String>,     // 更新されるファイル
        val unchanged: List<String>,   // 変更なし（変更セットに含まれない）
        val errors: List<String>,      // 1件でもあれば changeSet は空
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    private val placeholder = Regex("\\{([^}]+)}")

    /** 表を読み、変更セットのプレビューを作る（この時点では原本に書かない）。 */
    fun build(ws: Workspace, skill: SkillDef, sourceFile: Path): Preview {
        val def = skill.import ?: error("skill「${skill.id}」は import ではありません")
        val table = Tables.read(sourceFile, def)
        return build(ws, def, table)
    }

    internal fun build(ws: Workspace, def: ImportDef, table: Table): Preview {
        val errors = mutableListOf<String>()

        // マッピング・プレースホルダが参照する列がヘッダに存在すること
        val referenced = def.mapping.map { it.column } +
            placeholder.findAll(def.targetFile).map { it.groupValues[1] }.toList()
        referenced.distinct().filter { table.columnIndex(it) < 0 }.forEach {
            errors += "列「$it」が見つかりません（ヘッダ: ${table.headers.joinToString(", ")}）"
        }
        if (errors.isNotEmpty()) return Preview(ChangeSet(emptyList()), emptyList(), emptyList(), emptyList(), errors)

        if (!placeholder.containsMatchIn(def.targetFile) && table.rows.size > 1) {
            return Preview(
                ChangeSet(emptyList()), emptyList(), emptyList(), emptyList(),
                listOf("target.file にプレースホルダ（{列名}）がないため、複数行（${table.rows.size}行）を取り込めません"),
            )
        }

        // 行 → 取り込み先ファイルと値の解決
        data class RowPlan(val rel: String, val values: List<Pair<FieldMapping, YNode>>)
        val plans = mutableListOf<RowPlan>()
        table.rows.forEachIndexed { index, row ->
            val rowNo = index + 1
            fun cellOf(column: String): Any? = row.getOrNull(table.columnIndex(column))

            val rel = runCatching { resolveTargetFile(def.targetFile) { col -> cellOf(col) } }
                .getOrElse { errors += "行$rowNo: ${it.message}"; return@forEachIndexed }
            val values = mutableListOf<Pair<FieldMapping, YNode>>()
            def.mapping.forEach { m ->
                val raw = cellOf(m.column)
                if (raw == null || raw.toString().isBlank()) {
                    if (m.required) errors += "行$rowNo: 必須列「${m.column}」が空です"
                    return@forEach  // 空セルは触らない（既存値を保持）
                }
                runCatching { values += m to convert(raw, m) }
                    .onFailure { errors += "行$rowNo 列「${m.column}」: ${it.message}" }
            }
            plans += RowPlan(rel, values)
        }
        plans.groupBy { it.rel }.filterValues { it.size > 1 }.keys.forEach {
            errors += "複数の行が同じファイル「$it」に解決されます（キー列の重複）"
        }
        if (errors.isNotEmpty()) return Preview(ChangeSet(emptyList()), emptyList(), emptyList(), emptyList(), errors)

        // 変更セットの組み立て
        val created = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val changes = mutableListOf<FileChange>()
        plans.forEach { plan ->
            val file = ws.resolve(plan.rel)
            if (!Files.isRegularFile(file)) {
                val root = YNode.Map()
                plan.values.forEach { (m, node) -> setNested(root, m.path.split('.'), node) }
                changes += FileChange(file = plan.rel, baseHash = null, ops = emptyList(), create = true, content = root)
                created += plan.rel
            } else {
                val text = Files.readString(file)
                val existing = runCatching { YamlIO.parse(text) }.getOrElse {
                    errors += "${plan.rel}: YAMLとして読めないため更新できません（${it.message}）"
                    return@forEach
                }
                val ops = plan.values.mapNotNull { (m, node) ->
                    val current = getAt(existing, m.path.split('.'))
                    // 変化なし判定はコメント・表記スタイルを無視して構造で比較する（R8-01）
                    if (current != null && YamlIO.dumpPlain(current) == YamlIO.dumpPlain(node)) null
                    else PatchOp.Set(m.path.split('.'), node)
                }
                if (ops.isEmpty()) unchanged += plan.rel
                else {
                    changes += FileChange(file = plan.rel, baseHash = ChangeSets.contentHash(text), ops = ops)
                    updated += plan.rel
                }
            }
        }
        if (errors.isNotEmpty()) return Preview(ChangeSet(emptyList()), emptyList(), emptyList(), emptyList(), errors)
        return Preview(ChangeSet(changes), created, updated, unchanged, emptyList())
    }

    // ---------------------------------------------------------------- 値の解決

    private fun resolveTargetFile(pattern: String, cell: (String) -> Any?): String {
        val rel = placeholder.replace(pattern) { match ->
            val column = match.groupValues[1]
            val value = cell(column)?.let(::asString)?.trim()
                ?: error("取り込み先の決定に使う列「$column」が空です")
            sanitizeFileToken(value).ifBlank { error("列「$column」の値がファイル名に使えません: $value") }
        }
        return rel.replace('\\', '/')
    }

    private fun sanitizeFileToken(value: String): String =
        value.map { c -> if (c in "\\/:*?\"<>|" || c.isISOControl()) '-' else c }.joinToString("")

    /** セル値をマッピング（辞書→分割→型変換）に従って YNode へ変換する。 */
    internal fun convert(raw: Any?, m: FieldMapping): YNode {
        fun mapped(value: Any?): Any? {
            if (m.map.isEmpty()) return value
            val key = value?.let(::asString) ?: return value
            return m.map[key] ?: value
        }
        m.split?.let { sep ->
            val parts = asString(raw!!).split(sep).map { it.trim() }.filter { it.isNotEmpty() }
            return YNode.Seq(parts.map { part -> scalar(mapped(part), m.type) }.toMutableList())
        }
        return scalar(mapped(raw), m.type)
    }

    private fun scalar(value: Any?, type: String): YNode.Scalar {
        val text = value?.let(::asString) ?: return YNode.Scalar(null)
        return when (type) {
            "auto" -> if (value is String) YNode.Scalar(value) else YNode.Scalar(value)
            "int" -> YNode.Scalar(text.toLongOrNull() ?: error("整数として解釈できません: $text"))
            "number" -> YNode.Scalar(text.toDoubleOrNull() ?: error("数値として解釈できません: $text"))
            "boolean" -> when (text.lowercase()) {
                "true", "yes", "1" -> YNode.Scalar(true)
                "false", "no", "0" -> YNode.Scalar(false)
                else -> error("真偽値として解釈できません: $text")
            }
            else -> YNode.Scalar(text)  // string
        }
    }

    /** 表のセル値の文字列表現（Excel由来の整数 Double は小数点なしにする）。 */
    private fun asString(value: Any): String = when (value) {
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun setNested(root: YNode.Map, keys: List<String>, value: YNode) {
        var cur = root
        keys.dropLast(1).forEach { key ->
            cur = cur.entries.getOrPut(key) { YNode.Map() } as? YNode.Map
                ?: error("パス「${keys.joinToString(".")}」の途中がobjectではありません")
        }
        cur.entries[keys.last()] = value
    }
}
