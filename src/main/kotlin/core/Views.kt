package core

import index.IdEntry
import index.IdIndex
import index.RefEntry
import index.idMatchesPattern
import model.YNode
import model.YPath
import model.getAt
import model.parseYPath
import model.pretty
import model.scalarText
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import schema.SchemaLoader
import schema.effectiveFields
import schema.globToRegex
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * ビュー機構（P6）。複数文書の一部を「行（＝論理ID）×列（＝出現からの相対YPath）」に射影する
 * 宣言的な定義（`_views` 配下のYAML）と、その解決エンジン。
 * - ビューは派生表示であり、データを一切持たない（原本主義）。セルは原本上の座標 (file, YPath) に解決される
 * - 行の集合は索引（IdIndex）から解決する。UI・エージェントはそれぞれの索引（未保存重ね・ステージング
 *   オーバーレイ）と loader を注入することで、同じ定義から同じ射影を得る
 * - 詳細文書のスキャフォールド（filePattern）により「一覧から新しい文書を作る」ペア規約を機械化する
 */

/** 列が値を取り出す出現の選択条件。schemaId / fileGlob の AND（null は制限なし）。 */
data class ViewSource(
    val schemaId: String? = null,
    val fileGlob: String? = null,
) {
    private val regex: Regex? by lazy { fileGlob?.let { runCatching { globToRegex(it) }.getOrNull() } }

    fun matches(entry: IdEntry, rel: String): Boolean =
        (schemaId == null || entry.schemaId == schemaId) &&
            (regex == null || regex!!.matches(rel))

    val isEmpty: Boolean get() = schemaId == null && fileGlob == null
}

/**
 * バックリンク列の条件。行IDへの参照（被参照）を関係種別・参照元スキーマで絞る。
 * 1:N詳細化（新ID＋親への ref。R7-02）の「親から見た分裂先の一覧」を表示する。
 */
data class ViewBacklinksDef(
    val relation: String? = null,
    val schemaIds: List<String> = emptyList(),
) {
    fun matches(ref: RefEntry): Boolean =
        (relation == null || ref.relation == relation) &&
            (schemaIds.isEmpty() || ref.sourceSchemaId in schemaIds)
}

/** ビューの1列。出現要素からの相対YPathで値を指すか、backlinks で被参照の一覧を表示する。 */
data class ViewColumn(
    val label: String,
    val from: ViewSource = ViewSource(),
    val path: YPath,
    val editable: Boolean = false,
    val backlinks: ViewBacklinksDef? = null,  // 非null のとき被参照一覧の列（path / editable とは排他）
)

/** 行の集合の定義。idPattern に一致する論理IDが行になる。 */
data class ViewRowsDef(
    val idPattern: String,
    val schemaIds: List<String> = emptyList(),  // 空でなければ、いずれかのスキーマに出現するIDのみ行にする
)

/** 詳細文書の扱い。source に一致する出現を「詳細」と見なし、filePattern から新規作成できる。 */
data class ViewDetailDef(
    val source: ViewSource = ViewSource(),
    val filePattern: String? = null,   // 例 "designs/{id}.yaml"（{id} が行のIDで置換される）
)

/** 新規行の追加先（一覧文書内の配列）。 */
data class ViewNewRowDef(
    val file: String,
    val path: YPath,
)

data class ViewDef(
    val id: String,
    val name: String,
    val description: String? = null,
    val rows: ViewRowsDef,
    val columns: List<ViewColumn>,
    val orderBy: ViewSource? = null,   // この条件に一致する出現の文書内順で行を並べる（省略時はID順）
    val detail: ViewDetailDef? = null,
    val newRow: ViewNewRowDef? = null,
    val source: Path,
)

/** バックリンク列の1件。参照元要素を含む最も近いID（その位置にIDがなければ null）と、参照値の座標。 */
data class ViewBacklink(
    val sourceId: String?,
    val sourceTitle: String?,  // 参照元要素のタイトル（索引の表示解決結果。R10-01）
    val rel: String,
    val file: Path,
    val path: YPath,        // 参照値（スカラー）の位置
    val relation: String?,
)

/** 解決済みのセル。file が null のときは対応する出現がない（値なし）。backlinks はバックリンク列のみ非null。 */
data class ViewCell(
    val column: ViewColumn,
    val rel: String?,
    val file: Path?,
    val path: YPath?,       // 絶対YPath（出現要素のパス + 列の相対パス）
    val value: YNode?,
    val backlinks: List<ViewBacklink>? = null,
)

/** 解決済みの1行（＝1論理ID）。 */
data class ViewRow(
    val id: String,
    val title: String?,
    val occurrences: List<IdEntry>,
    val cells: List<ViewCell>,
    val detailRel: String?,          // 詳細出現のファイル（なければ null）
    val detailPath: YPath?,          // 詳細出現の要素パス
)

object Views {

    data class LoadResult(val views: List<ViewDef>, val errors: List<String>) {
        fun view(id: String): ViewDef? = views.firstOrNull { it.id == id }
    }

    /** `_views/` からビュー定義を読み込む（エラー収集つき）。 */
    fun load(workspace: Path): LoadResult {
        val dir = workspace.resolve(SchemaLoader.VIEWS_DIR)
        if (!Files.isDirectory(dir)) return LoadResult(emptyList(), emptyList())
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
        val views = mutableListOf<ViewDef>()
        val errors = mutableListOf<String>()
        files.forEach { file ->
            runCatching {
                val raw = YamlIO.parseToRaw(Files.readString(file)) as? Map<*, *>
                    ?: error("ルートはobjectである必要があります")
                parseView(raw, file)
            }.onSuccess { views += it }
                .onFailure { errors += "${file.fileName}: ${it.message ?: it::class.simpleName}" }
        }
        views.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            errors += "ビューID「$id」が重複しています（${duplicates.joinToString(", ") { it.source.fileName.toString() }}）"
        }
        return LoadResult(views, errors)
    }

    /** ビュー定義1件のパース。ステージングの定義ゲート（Staging）からも使う。 */
    fun parseView(raw: Map<*, *>, source: Path): ViewDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        val rowsMap = raw["rows"] as? Map<*, *> ?: error("rows が必要です")
        val idPattern = rowsMap["idPattern"]?.toString()?.takeIf { it.isNotBlank() } ?: error("rows.idPattern が必要です")
        val columnsRaw = raw["columns"] as? List<*> ?: error("columns が必要です")
        require(columnsRaw.isNotEmpty()) { "columns には1列以上が必要です" }
        val columns = columnsRaw.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("columns[$i] はobjectである必要があります")
            val label = m["label"]?.toString()?.takeIf { it.isNotBlank() } ?: error("columns[$i].label が必要です")
            if (m.containsKey("backlinks")) {
                // バックリンク列: 被参照の派生表示のため、出現の選択（path / schemaId / fileGlob）や編集とは排他
                val b = m["backlinks"] as? Map<*, *> ?: error("columns[$i].backlinks はobjectである必要があります")
                if (m["path"] != null) error("columns[$i]: path と backlinks は併用できません")
                if (m["editable"] == true) error("columns[$i]: backlinks 列は editable にできません")
                if (m["schemaId"] != null || m["fileGlob"] != null) {
                    error("columns[$i]: backlinks 列に schemaId / fileGlob は指定できません（絞り込みは backlinks 配下に書きます）")
                }
                ViewColumn(
                    label = label,
                    path = emptyList(),
                    backlinks = ViewBacklinksDef(
                        relation = b["relation"]?.toString(),
                        schemaIds = stringList(b["schemaIds"]),
                    ),
                )
            } else ViewColumn(
                label = label,
                from = parseSource(m),
                path = parseYPath(m["path"]?.toString() ?: error("columns[$i].path が必要です")),
                editable = m["editable"] as? Boolean ?: false,
            )
        }
        val detail = (raw["detail"] as? Map<*, *>)?.let { m ->
            val pattern = m["filePattern"]?.toString()
            if (pattern != null) {
                require(pattern.contains("{id}")) { "detail.filePattern には {id} を含めてください: $pattern" }
                require(pattern.endsWith(".yaml") || pattern.endsWith(".yml")) { "detail.filePattern はYAMLファイルである必要があります: $pattern" }
            }
            ViewDetailDef(source = parseSource(m), filePattern = pattern)
        }
        val newRow = (raw["newRow"] as? Map<*, *>)?.let { m ->
            ViewNewRowDef(
                file = m["file"]?.toString()?.takeIf { it.isNotBlank() } ?: error("newRow.file が必要です"),
                path = parseYPath(m["path"]?.toString() ?: error("newRow.path が必要です")),
            )
        }
        return ViewDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            rows = ViewRowsDef(
                idPattern = idPattern,
                schemaIds = stringList(rowsMap["schemaIds"]),
            ),
            columns = columns,
            orderBy = (raw["order"] as? Map<*, *>)?.let { parseSource(it) }?.takeIf { !it.isEmpty },
            detail = detail,
            newRow = newRow,
            source = source,
        )
    }

    /** 出現の選択条件（schemaId / fileGlob）を、定義の当該オブジェクト直下のキーから読む。 */
    private fun parseSource(m: Map<*, *>): ViewSource = ViewSource(
        schemaId = m["schemaId"]?.toString(),
        fileGlob = m["fileGlob"]?.toString(),
    )

    private fun stringList(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.map { it.toString() }
        null -> emptyList()
        else -> listOf(raw.toString())
    }

    // ---------------------------------------------------------------- 解決

    /**
     * ビューを行×セルに解決する。
     * - index: 行の集合と出現の解決に使う（UIはディスク索引、エージェントはオーバーレイ索引を渡す）
     * - loader: セル値の読み取り（UIは未保存編集を優先する loader、エージェントはステージングの tree）
     */
    fun resolve(
        view: ViewDef,
        workspace: Path,
        index: IdIndex,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
    ): List<ViewRow> {
        val trees = mutableMapOf<Path, YNode?>()
        fun tree(file: Path): YNode? = trees.getOrPut(file) { loader(file) }
        fun rel(file: Path): String = IdIndex.relPathOf(workspace, file)

        // バックリンク列の「参照元要素のID」解決に使う。参照位置を含む最も長いパスの出現を最内のIDとみなす
        val entriesByFile: Map<Path, List<IdEntry>> by lazy { index.entries.values.flatten().groupBy { it.file } }
        fun backlinksFor(id: String, def: ViewBacklinksDef): List<ViewBacklink> =
            index.refs.filter { it.targetId == id && def.matches(it) }
                .map { ref ->
                    val source = entriesByFile[ref.file].orEmpty()
                        .filter { it.path.size <= ref.path.size && ref.path.subList(0, it.path.size) == it.path }
                        .maxByOrNull { it.path.size }
                    ViewBacklink(source?.id, source?.title, rel(ref.file), ref.file, ref.path, ref.relation)
                }
                .sortedWith(compareBy({ it.rel }, { pathSortKey(it.path) }))

        val rows = index.entries.asSequence()
            .filter { (id, _) -> idMatchesPattern(id, view.rows.idPattern) }
            .filter { (_, occurrences) ->
                view.rows.schemaIds.isEmpty() || occurrences.any { it.schemaId in view.rows.schemaIds }
            }
            .map { (id, occurrences) ->
                val cells = view.columns.map { column ->
                    val backlinksDef = column.backlinks
                    val occurrence =
                        if (backlinksDef != null) null
                        else occurrences.firstOrNull { column.from.matches(it, rel(it.file)) }
                    if (backlinksDef != null) ViewCell(column, null, null, null, null, backlinks = backlinksFor(id, backlinksDef))
                    else if (occurrence == null) ViewCell(column, null, null, null, null)
                    else {
                        val absolute = occurrence.path + column.path
                        ViewCell(
                            column = column,
                            rel = rel(occurrence.file),
                            file = occurrence.file,
                            path = absolute,
                            value = tree(occurrence.file)?.let { getAt(it, absolute) },
                        )
                    }
                }
                val detailOccurrence = view.detail?.let { d ->
                    occurrences.firstOrNull { o -> d.source.matches(o, rel(o.file)) }
                }
                ViewRow(
                    id = id,
                    title = occurrences.firstNotNullOfOrNull { it.title },
                    occurrences = occurrences,
                    cells = cells,
                    detailRel = detailOccurrence?.let { rel(it.file) },
                    detailPath = detailOccurrence?.path,
                )
            }
            .toList()
        return sortRows(view, rows) { rel(it) }
    }

    /** 行の並び: orderBy 出現の（ファイル→文書内パス）順 → それ以外はID順で後ろに。 */
    private fun sortRows(view: ViewDef, rows: List<ViewRow>, rel: (Path) -> String): List<ViewRow> {
        val orderBy = view.orderBy ?: return rows.sortedBy { it.id }
        fun anchor(row: ViewRow): IdEntry? = row.occurrences.firstOrNull { orderBy.matches(it, rel(it.file)) }
        val comparator = compareBy<Pair<ViewRow, IdEntry?>>(
            { (_, a) -> if (a == null) 1 else 0 },
            { (_, a) -> a?.let { rel(it.file) } ?: "" },
            { (_, a) -> a?.path?.let(::pathSortKey) ?: "" },
            { (row, _) -> row.id },
        )
        return rows.map { it to anchor(it) }.sortedWith(comparator).map { it.first }
    }

    /** YPath の文書内順を保つソートキー（配列インデックスはゼロ詰めで桁を揃える）。 */
    private fun pathSortKey(path: YPath): String =
        path.joinToString("/") { seg -> if (seg is Int) "%08d".format(seg) else seg.toString() }

    // ---------------------------------------------------------------- スキーマフィールドの解決

    /**
     * データパスに対応するフィールド定義を、スキーマを辿って解決する（セルエディタの型決定に使う）。
     * object の子は effectiveFields（enum の cases 合成）、$ref は resolve を通す。
     */
    fun fieldAt(schema: SchemaDef, root: YNode, path: YPath): FieldDef? {
        var def: FieldDef = schema.resolve(schema.root) ?: return null
        var node: YNode? = root
        for (seg in path) {
            val next: FieldDef? = when {
                def.type == FieldType.OBJECT && seg is String ->
                    schema.effectiveFields(def.fields, node as? YNode.Map).fields.firstOrNull { it.key == seg }
                def.type == FieldType.MAP && seg is String -> def.value
                def.type == FieldType.ARRAY && seg is Int -> def.item
                else -> null
            }
            def = schema.resolve(next) ?: return null
            node = node?.let { getAt(it, listOf(seg)) }
        }
        return def
    }

    // ---------------------------------------------------------------- 詳細文書のスキャフォールド

    data class Scaffold(val rel: String, val root: YNode)

    /**
     * 行のIDから詳細文書の新規作成内容を組み立てる（作成自体は呼び出し側が行う:
     * UIはファイル書き込み、エージェントは create_document）。
     * スキーマは filePattern の解決先パスにマッチするものを使い、必須フィールドの初期値と
     * ルート直下の ID フィールドへ行のIDを設定する。
     */
    fun detailScaffold(view: ViewDef, id: String, schemas: List<SchemaDef>): Scaffold {
        val pattern = view.detail?.filePattern
            ?: error("このビューには detail.filePattern が定義されていません")
        require(id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..")) {
            "ファイル名に使用できないIDです: $id"
        }
        val rel = pattern.replace("{id}", id)
        val schema = schemas.firstOrNull { it.matches(rel) }
        val root = schema?.let { defaultTree(it.resolve(it.root) ?: it.root, it) } ?: YNode.Map()
        (root as? YNode.Map)?.let { map ->
            val idKey = schema?.let { s ->
                (s.resolve(s.root) ?: s.root).fields.firstOrNull { (s.resolve(it) ?: it).type == FieldType.ID }?.key
            } ?: "id"
            map.entries[idKey] = YNode.Scalar(id)
        }
        return Scaffold(rel, root)
    }

    /** スキーマ定義から必須フィールドの初期値ツリーを生成する（default 尊重・cases 対応）。 */
    fun defaultTree(field: FieldDef, schema: SchemaDef, depth: Int = 0): YNode {
        if (depth > 24) return YNode.Map()
        val f = schema.resolve(field) ?: field
        if (f.default != null) return YamlIO.fromAny(f.default)
        return when (f.type) {
            FieldType.OBJECT -> {
                val entries = LinkedHashMap<String, YNode>().apply {
                    f.fields.filter { it.required && it.key != null }
                        .forEach { put(it.key!!, defaultTree(it, schema, depth + 1)) }
                }
                val map = YNode.Map(entries)
                schema.effectiveFields(f.fields, map).fields.forEach { child ->
                    val key = child.key ?: return@forEach
                    if (child.required && key !in entries) entries[key] = defaultTree(child, schema, depth + 1)
                }
                map
            }
            FieldType.ARRAY -> YNode.Seq()
            FieldType.MAP -> YNode.Map()
            FieldType.BOOLEAN -> YNode.Scalar(false)
            FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
            else -> YNode.Scalar("")
        }
    }

    // ---------------------------------------------------------------- テキスト表現（エージェント・CLI 共用）

    /**
     * 解決済みビューの機械可読テキスト。各セルに原本上の座標（file / path）を添えるため、
     * エージェントはこの出力から直接 apply_patch の対象を決められる。
     */
    fun renderText(view: ViewDef, rows: List<ViewRow>): String = buildString {
        appendLine("ビュー: ${view.name}（${view.id}）")
        view.description?.let { appendLine(it.trim()) }
        appendLine("行: ${rows.size} 件（idPattern: ${view.rows.idPattern}）")
        for (row in rows) {
            append("- id: ").append(row.id)
            row.title?.let { append("  title: ").append(it) }
            appendLine()
            for (cell in row.cells) {
                append("    ").append(cell.column.label).append(": ")
                val backlinks = cell.backlinks
                if (backlinks != null) {
                    // バックリンク列: 参照元のIDと座標を列挙する（エージェントはこの座標から参照元へ辿れる）
                    if (backlinks.isEmpty()) appendLine("（被参照なし）")
                    else appendLine(
                        backlinks.joinToString(", ") { b ->
                            listOfNotNull(b.sourceId ?: b.rel, b.sourceTitle).joinToString(" ") +
                                "  [" + b.rel + " :: " + b.path.pretty() + "]"
                        }
                    )
                } else if (cell.file == null) {
                    appendLine("（出現なし）")
                } else {
                    append(cellText(cell.value))
                    append("  [").append(cell.rel).append(" :: ").append(cell.path!!.pretty())
                    if (cell.column.editable) append("  編集可")
                    appendLine("]")
                }
            }
            if (view.detail != null) {
                if (row.detailRel != null) appendLine("    詳細: ${row.detailRel}")
                else appendLine(
                    "    詳細: 未作成" +
                        (view.detail.filePattern?.let { "（filePattern: $it → ${it.replace("{id}", row.id)}）" } ?: "")
                )
            }
        }
    }.trimEnd()

    /** セル値の1行要約。 */
    fun cellText(value: YNode?, maxLength: Int = 80): String = when (value) {
        null -> "（未設定）"
        is YNode.Scalar -> {
            val text = value.value?.toString() ?: "null"
            val single = text.replace("\n", " ")
            if (single.length > maxLength) single.take(maxLength) + "…" else single
        }
        is YNode.Seq -> "（配列 ${value.items.size} 件）"
        is YNode.Map -> "（オブジェクト: ${value.entries.keys.take(4).joinToString(", ")}${if (value.entries.size > 4) "…" else ""}）"
    }
}
