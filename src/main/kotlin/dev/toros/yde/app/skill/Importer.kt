package dev.toros.yde.app.skill

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.schema.IdSuggest
import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.semantic.schema.FileNames
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.semantic.schema.Template
import dev.toros.yde.semantic.schema.TemplateCondition
import dev.toros.yde.semantic.schema.TemplateSite
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.changeset.FileChange
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.patch.PatchOp
import java.nio.file.Files
import java.nio.file.Path

/**
 * インポート（R5-02）: 表形式データを skill 定義で変更セットへ変換する。
 * - 行の組み立ては、列の対応表（mapping）か、行（列名 → セル）を項として当てるひな型（build＝変換定義 SemanticTransform）
 * - 1 行 1 文書（target.file だけ）: 新規行は文書の作成、既存ファイルは**組み立てた項目だけの set 操作**（値が変わる場合のみ）
 *   → 人間がYAML側に追記した項目を再インポートが消さない（取り込んだ時点からYAMLが原本）
 * - 文書の中の配列への追記（target.path）: 明細を配列の末尾へ足す。`key` が同じ明細が既にあれば飛ばす（手で直した値を戻さない）。
 *   `id` の無い明細には、接頭辞（明細で埋めるひな型）＋連番を付ける（IdSuggest.nextInSeries。既存の ID は索引と取り込み先の配列から）
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
        /** ファイルごとの内訳（配列への取り込み: 追加した件数と、同じ明細として飛ばした件数）。 */
        val details: Map<String, String> = emptyMap(),
        /** ひな型の条件に合わず飛ばした行の数（build の $when。見出しの行や小計の行など）。 */
        val skippedRows: Int = 0,
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    private val placeholder = Regex("\\{([^}]+)}")

    /** 表を読み、変更セットのプレビューを作る（この時点では原本に書かない）。 */
    fun build(ws: Workspace, skill: SkillDef, sourceFile: Path): Preview {
        val def = skill.import ?: error("skill「${skill.id}」は import ではありません")
        // 表が読めない（文字コードが合わない・形式が違う）ことも、定義の誤りと同じくプレビューの誤りとして返す
        val table = runCatching { Tables.read(sourceFile, def) }.getOrElse { e ->
            return Preview(ChangeSet(emptyList()), emptyList(), emptyList(), emptyList(), listOf(e.message ?: e::class.simpleName.orEmpty()))
        }
        return build(ws, def, table)
    }

    internal fun build(ws: Workspace, def: ImportDef, table: Table): Preview {
        val errors = mutableListOf<String>()
        fun failed() = Preview(ChangeSet(emptyList()), emptyList(), emptyList(), emptyList(), errors)

        if (def.build == null) {
            // 列の対応表: マッピング・プレースホルダが参照する列がヘッダに存在すること
            val referenced = def.mapping.map { it.column } +
                placeholder.findAll(def.targetFile).map { it.groupValues[1] }.toList()
            referenced.distinct().filter { table.columnIndex(it) < 0 }.forEach {
                errors += "列「$it」が見つかりません（ヘッダ: ${table.headers.joinToString(", ")}）"
            }
            if (errors.isNotEmpty()) return failed()
            if (def.targetPath == null && !placeholder.containsMatchIn(def.targetFile) && table.rows.size > 1) {
                errors += "target.file にプレースホルダ（{列名}）がないため、複数行（${table.rows.size}行）を取り込めません"
                return failed()
            }
        } else {
            // ひな型が読む列がどれも見出しに無ければ、ファイルか文字コードの取り違え（無い列を $first で拾う書き方は許す）
            val referenced = referencedColumns(def.build)
            if (referenced.isNotEmpty() && referenced.none { table.columnIndex(it) >= 0 }) {
                errors += "ひな型が読む列（${referenced.joinToString("・")}）がどれも見出しにありません（見出し: ${table.headers.joinToString(", ")}）。" +
                    "文字コード（input.encoding）や見出しの行を確かめてください"
                return failed()
            }
        }

        // 行 → 取り込み先ファイルと明細（組み立てた項）
        class RowPlan(val rowNo: Int, val rel: String, val entry: YNode.Map)
        val plans = mutableListOf<RowPlan>()
        var skippedRows = 0
        table.rows.forEachIndexed { index, row ->
            val rowNo = index + 1
            fun cellOf(column: String): Any? = row.getOrNull(table.columnIndex(column))
            val entry: YNode.Map
            val rel: String
            if (def.build == null) {
                entry = mappedEntry(def, rowNo, ::cellOf, errors)
                rel = runCatching { resolveTargetFile(def.targetFile, ::cellOf) }
                    .getOrElse { errors += "行$rowNo: ${it.message}"; return@forEachIndexed }
            } else {
                val built = runCatching { SemanticTransform.run(def.build, rowTerm(table, row)).term }
                    .getOrElse { errors += "行$rowNo: ${it.message}"; return@forEachIndexed }
                if (built == null) { skippedRows++; return@forEachIndexed }  // 条件に合わない行（$when）は飛ばす
                entry = built as? YNode.Map
                    ?: run { errors += "行$rowNo: build は項目の組（マップ）を組み立てる必要があります"; return@forEachIndexed }
                rel = runCatching { resolveTemplateFile(def.fileTemplate!!, entry) }
                    .getOrElse { errors += "行$rowNo: ${it.message}"; return@forEachIndexed }
            }
            plans += RowPlan(rowNo, rel, entry)
        }
        if (def.targetPath == null) {
            plans.groupBy { it.rel }.filterValues { it.size > 1 }.forEach { (rel, group) ->
                // build で同じ内容に組み立てられた行（例: 表の見出しの値から 1 つの文書を作る）は 1 つに畳む。内容が違えば誤り
                val identical = def.build != null && group.map { YamlIO.dumpPlain(it.entry) }.distinct().size == 1
                if (!identical) errors += "複数の行が同じファイル「$rel」に解決されます（キー列の重複）"
            }
        }
        if (errors.isNotEmpty()) return failed()

        // 変更セットの組み立て
        val created = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val details = LinkedHashMap<String, String>()
        val changes = mutableListOf<FileChange>()
        // 採番の系列に入る既存の ID（索引＝台帳の全文書。取り込み先の配列の ID は、台帳の外でも加える）
        val existingIds = LinkedHashSet<String>()
        if (def.idRule != null) existingIds += ws.index.entries.keys
        for ((rel, group) in plans.groupBy { it.rel }) {
            val file = ws.resolve(rel)
            val text = if (Files.isRegularFile(file)) Files.readString(file) else null
            val parsed = text?.let { t -> runCatching { YamlIO.parse(t) } }
            if (parsed != null && parsed.isFailure) {
                errors += "$rel: YAMLとして読めないため更新できません（${parsed.exceptionOrNull()?.message}）"
                continue
            }
            val existing = parsed?.getOrNull()
            if (def.targetPath == null) {
                val plan = group.first()  // 複数あれば同じ内容（上で確かめた）
                if (existing == null) {
                    changes += FileChange(file = rel, baseHash = null, ops = emptyList(), create = true, content = plan.entry)
                    created += rel
                } else {
                    val ops = leaves(plan.entry).mapNotNull { (path, node) ->
                        val current = getAt(existing, path)
                        // 変化なし判定はコメント・表記スタイルを無視して構造で比較する（R8-01）
                        if (current != null && YamlIO.dumpPlain(current) == YamlIO.dumpPlain(node)) null
                        else PatchOp.Set(path, node)
                    }
                    if (ops.isEmpty()) unchanged += rel
                    else {
                        changes += FileChange(file = rel, baseHash = ChangeSets.contentHash(text!!), ops = ops)
                        updated += rel
                    }
                }
                continue
            }

            // 配列への追記
            val path = def.targetPath
            var createArray = false
            val existingItems: List<YNode> = when {
                existing == null -> emptyList()
                else -> when (val node = getAt(existing, path)) {
                    null -> {
                        val parent = if (path.size == 1) existing else getAt(existing, path.dropLast(1))
                        if (parent !is YNode.Map) {
                            errors += "$rel: 取り込み先「${path.pretty()}」の親が object ではありません"
                            continue
                        }
                        createArray = true
                        emptyList()
                    }
                    is YNode.Seq -> node.items
                    else -> { errors += "$rel: 取り込み先「${path.pretty()}」は配列ではありません"; continue }
                }
            }
            def.idRule?.let { rule -> existingItems.forEach { item -> scalarText(getAt(item, rule.path))?.let(existingIds::add) } }
            val seenKeys = existingItems.mapNotNull { keyOf(def.key, it) }.toMutableSet()
            val added = mutableListOf<YNode.Map>()
            var skipped = 0
            for (plan in group) {
                val key = keyOf(def.key, plan.entry)
                if (key != null && !seenKeys.add(key)) { skipped++; continue }  // 同じ明細は取り込まない
                def.idRule?.let { rule ->
                    if (getAt(plan.entry, rule.path) != null) return@let
                    val prefix = runCatching { SemanticTransform.run(rule.prefix, plan.entry).term?.let(::scalarText) }
                        .getOrElse { errors += "行${plan.rowNo}: ${it.message}"; return@let }
                        ?: run {
                            errors += "行${plan.rowNo}: 採番の接頭辞が決まりません（target.id.prefix の穴が、組み立てた明細の値で埋まりません）"
                            return@let
                        }
                    val id = IdSuggest.nextInSeries(existingIds, prefix, rule.digits)
                    existingIds += id
                    putId(plan.entry, rule.path, YNode.Scalar(id))
                }
                // 表記は隣の明細に合わせる（流れ書きの配列には流れ書きで足す）
                (existingItems.lastOrNull() as? YNode.Map)?.flowHint?.let { plan.entry.flowHint = it }
                added += plan.entry
            }
            if (errors.isNotEmpty()) continue
            val skippedNote = if (skipped > 0) "（同じ明細 $skipped 件を飛ばした）" else ""
            details[rel] = "追加 ${added.size} 件$skippedNote"
            when {
                added.isEmpty() -> unchanged += rel
                existing == null -> {
                    val root = YNode.Map()
                    setNested(root, path.map { it as String }, YNode.Seq(added.toMutableList()))
                    changes += FileChange(file = rel, baseHash = null, ops = emptyList(), create = true, content = root)
                    created += rel
                }
                createArray -> {
                    changes += FileChange(file = rel, baseHash = ChangeSets.contentHash(text!!), ops = listOf(PatchOp.Set(path, YNode.Seq(added.toMutableList()))))
                    updated += rel
                }
                else -> {
                    val ops = added.mapIndexed { i, entry -> PatchOp.Insert(path + (existingItems.size + i), entry) }
                    changes += FileChange(file = rel, baseHash = ChangeSets.contentHash(text!!), ops = ops)
                    updated += rel
                }
            }
        }
        if (errors.isNotEmpty()) return failed()
        return Preview(ChangeSet(changes), created, updated, unchanged, emptyList(), details, skippedRows)
    }

    // ---------------------------------------------------------------- 行の組み立て

    /** 列の対応表（mapping）で行を項目の組にする。空セルは触らない（既存値を保持）。誤りは errors へ足す。 */
    private fun mappedEntry(def: ImportDef, rowNo: Int, cellOf: (String) -> Any?, errors: MutableList<String>): YNode.Map {
        val root = YNode.Map()
        def.mapping.forEach { m ->
            val raw = cellOf(m.column)
            if (raw == null || raw.toString().isBlank()) {
                if (m.required) errors += "行$rowNo: 必須列「${m.column}」が空です"
                return@forEach
            }
            runCatching { setNested(root, m.path.split('.'), convert(raw, m)) }
                .onFailure { errors += "行$rowNo 列「${m.column}」: ${it.message}" }
        }
        return root
    }

    /** 行を項にする（見出し → セル。空のセルは項目ごと無い＝`exists: false`。Excel 由来の整数の Double は整数に）。 */
    internal fun rowTerm(table: Table, row: List<Any?>): YNode.Map {
        val entries = LinkedHashMap<String, YNode>()
        table.headers.forEachIndexed { i, header ->
            val cell = row.getOrNull(i) ?: return@forEachIndexed
            if (cell is String && cell.isEmpty()) return@forEachIndexed
            entries[header] = YNode.Scalar(if (cell is Double && cell % 1.0 == 0.0) cell.toLong() else cell)
        }
        return YNode.Map(entries)
    }

    /** ひな型が「いまの場所」（行）から読む列の名前（パスの先頭のキーと、文字のひな型の穴）。 */
    private fun referencedColumns(def: TransformDef): Set<String> {
        val names = LinkedHashSet<String>()
        fun site(s: TemplateSite) {
            if (s.of == null) (s.path.firstOrNull() as? String)?.let(names::add)
        }
        fun condition(c: TemplateCondition) = c.alternatives.forEach { site(it.site) }
        fun walk(t: Template) {
            when (t) {
                is Template.Literal, is Template.Param -> Unit
                is Template.MapOf -> t.entries.forEach { walk(it.second) }
                is Template.ListOf -> t.items.forEach(::walk)
                is Template.Copy -> site(t.site)
                is Template.Format -> {
                    site(t.site)
                    if (t.site.of == null && t.site.path.isEmpty()) {
                        Projection.placeholders(t.template).forEach { hole ->
                            (runCatching { parseYPath(hole) }.getOrNull()?.firstOrNull() as? String)?.let(names::add)
                        }
                    }
                    t.with.forEach { walk(it.second) }
                }
                is Template.MapValue -> walk(t.input)
                is Template.First -> t.options.forEach(::walk)
                is Template.DateOf -> walk(t.input)
                is Template.NumberOf -> walk(t.input)
                is Template.TrimOf -> walk(t.input)
                // 値の式がいまの場所（行）から読む項目の先頭の名前（列の名前）
                is Template.ValueOf -> t.expr.reads().filter { it.of == null }.forEach { g -> (g.path.firstOrNull() as? String)?.let(names::add) }
                is Template.Each -> { site(t.site); walk(t.body) }
                is Template.Apply -> site(t.site)
                is Template.Concat -> t.parts.forEach(::walk)
                is Template.Guarded -> { t.conditions.forEach(::condition); walk(t.inner) }
            }
        }
        walk(def.build)
        def.rules.forEach { r -> r.conditions.forEach(::condition); walk(r.build) }
        return names
    }

    /** 組み立てた項目の組の葉（スカラー・配列）を、パスつきで列挙する（既存文書の更新は葉ごとの set）。 */
    private fun leaves(entry: YNode.Map, prefix: YPath = emptyList()): List<Pair<YPath, YNode>> =
        entry.entries.flatMap { (key, node) ->
            if (node is YNode.Map && node.entries.isNotEmpty()) leaves(node, prefix + key) else listOf((prefix + key) to node)
        }

    /** 同じ明細の見分け（target.key）: 項目の値の組。どの項目も無ければ null（見分けない）。 */
    private fun keyOf(key: List<YPath>, item: YNode): String? {
        if (key.isEmpty()) return null
        val values = key.map { p -> getAt(item, p)?.let { YamlIO.dumpPlain(it).trim() } }
        if (values.all { it == null }) return null
        return values.joinToString("\u0000") { it ?: "" }
    }

    /** ID を明細の先頭に置く（1 段のキーのとき。入れ子のパスならその場所へ）。 */
    private fun putId(entry: YNode.Map, path: YPath, id: YNode) {
        if (path.size == 1) {
            val key = path.single() as String
            val rest = LinkedHashMap(entry.entries)
            entry.entries.clear()
            entry.entries[key] = id
            entry.entries.putAll(rest)
        } else setNested(entry, path.map { it as String }, id)
    }

    // ---------------------------------------------------------------- 値の解決

    private fun resolveTargetFile(pattern: String, cell: (String) -> Any?): String {
        val rel = placeholder.replace(pattern) { match ->
            val column = match.groupValues[1]
            val value = cell(column)?.let(::asString)?.trim()
                ?: error("取り込み先の決定に使う列「$column」が空です")
            FileNames.safeToken(value).ifBlank { error("列「$column」の値がファイル名に使えません: $value") }
        }
        return rel.replace('\\', '/')
    }

    /** build のとき: 取り込み先のひな型を、組み立てた明細を「いまの場所」として埋める。 */
    private fun resolveTemplateFile(template: TransformDef, entry: YNode.Map): String {
        val rel = SemanticTransform.run(template, entry).term?.let(::scalarText)
            ?.replace('\\', '/')
            ?: error("取り込み先が決まりません（target.file の穴が、組み立てた明細の値で埋まりません）")
        Skills.requireImportableFile(rel)
        rel.split('/').forEach { segment ->
            require(segment.isNotEmpty() && FileNames.safeToken(segment) == segment) { "取り込み先「$rel」にファイル名に使えない文字があります" }
        }
        return rel
    }

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
