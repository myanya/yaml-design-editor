package skill

import schema.SchemaLoader
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * skill機構（R5-01）。`_skills/` に置く宣言型の入出力変換定義。
 * - kind: import（表形式 → YAML変更セット）/ export（YAML → 表形式の派生成果物）
 * - 宣言型のみ。コード実行は提供しない（サンドボックス原則 ROADMAP §4.3。将来の明示許可制オプトイン）
 * - 定義はスキーマ付きYAML文書（builtin-skill）で、フォーム編集・検証・メタアクティビティの提案対象になる
 */

enum class SkillKind(val id: String) {
    IMPORT("import"),
    EXPORT("export");

    companion object {
        fun parse(raw: String): SkillKind =
            entries.firstOrNull { it.id == raw } ?: error("kind は import / export のいずれかです: $raw")
    }
}

enum class TableFormat(val id: String) {
    XLSX("xlsx"),
    CSV("csv"),
    JSON("json"),
    MARKDOWN("markdown");

    companion object {
        fun parse(raw: String): TableFormat =
            entries.firstOrNull { it.id == raw } ?: error("format は xlsx / csv / json / markdown のいずれかです: $raw")

        /** 出力ファイルの拡張子から書き出し形式を決める。 */
        fun fromExtension(file: String): TableFormat = when (file.substringAfterLast('.').lowercase()) {
            "xlsx" -> XLSX
            "csv" -> CSV
            "md", "markdown" -> MARKDOWN
            else -> error("出力形式を判定できません（.xlsx / .csv / .md のいずれか）: $file")
        }
    }
}

/** インポートの列→フィールド対応。 */
data class FieldMapping(
    val column: String,              // 表の列名（ヘッダ）
    val path: String,                // 文書内のパス（ドット区切りキーのみ。配列インデックス不可）
    val type: String = "string",     // string / int / number / boolean / auto（表の型を保持）
    val required: Boolean = false,   // 空セルをエラーにする
    val map: Map<String, String> = emptyMap(),  // 値の変換辞書（例 未着手→open）
    val split: String? = null,       // 区切り文字で配列化（例 ", "）
)

data class ImportDef(
    val format: TableFormat,
    val sheet: String? = null,       // xlsx: シート名（省略時は先頭）
    val headerRow: Int = 1,          // xlsx: ヘッダ行（1始まり）
    val jsonPath: String? = null,    // json: 行配列へのドット区切りパス（省略時はルート配列）
    val targetFile: String,          // 取り込み先（例 "issues/{ID}.yaml"。{列名} が行の値で置換される）
    val mapping: List<FieldMapping>,
)

data class ExportColumn(
    val header: String,
    val path: String,                // 行ノードからの相対パス（YPath。[n] 可）
    val join: String = ", ",         // 配列セルの結合区切り
)

data class ExportDef(
    val match: String,               // 対象文書の glob
    val rowsPath: String = "",       // "" = 1文書1行。配列パス指定でその要素ごとに1行
    val columns: List<ExportColumn>,
    val outputFile: String,          // 出力先（ワークスペース相対。拡張子で形式決定）
    val sheet: String = "Sheet1",    // xlsx: シート名
)

data class SkillDef(
    val id: String,
    val name: String,
    val description: String? = null,
    val kind: SkillKind,
    val import: ImportDef? = null,
    val export: ExportDef? = null,
    val source: Path,
)

object Skills {

    data class LoadResult(val skills: List<SkillDef>, val errors: List<String>) {
        fun skill(id: String): SkillDef? = skills.firstOrNull { it.id == id }
    }

    fun load(workspace: Path): LoadResult {
        val dir = workspace.resolve(SchemaLoader.SKILLS_DIR)
        if (!Files.isDirectory(dir)) return LoadResult(emptyList(), emptyList())
        val errors = mutableListOf<String>()
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
        val skills = files.mapNotNull { file ->
            runCatching {
                val raw = YamlIO.parseToRaw(Files.readString(file)) as? Map<*, *>
                    ?: error("ルートはobjectである必要があります")
                parseSkill(raw, file)
            }.onFailure { errors += "${file.fileName}: ${it.message ?: it::class.simpleName}" }.getOrNull()
        }
        skills.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { errors += "skillID「$it」が重複しています" }
        return LoadResult(skills, errors)
    }

    internal fun parseSkill(raw: Map<*, *>, source: Path): SkillDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        val kind = SkillKind.parse(raw["kind"]?.toString() ?: error("kind が必要です"))
        return SkillDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            kind = kind,
            import = if (kind == SkillKind.IMPORT) parseImport(raw) else null,
            export = if (kind == SkillKind.EXPORT) parseExport(raw) else null,
            source = source,
        )
    }

    private fun parseImport(raw: Map<*, *>): ImportDef {
        val input = raw["input"] as? Map<*, *> ?: error("import には input（format 等）が必要です")
        val target = raw["target"] as? Map<*, *> ?: error("import には target.file が必要です")
        val targetFile = target["file"]?.toString()?.takeIf { it.isNotBlank() } ?: error("target.file が必要です")
        require(targetFile.endsWith(".yaml") || targetFile.endsWith(".yml")) { "target.file はYAML（.yaml / .yml）である必要があります: $targetFile" }
        require(!targetFile.replace('\\', '/').startsWith("_")) { "予約ディレクトリへの取り込みはできません: $targetFile" }
        val mapping = (raw["mapping"] as? List<*>)?.map { parseMapping(it) } ?: error("mapping が必要です")
        if (mapping.isEmpty()) error("mapping は1件以上必要です")
        mapping.groupBy { it.path }.filterValues { it.size > 1 }.keys.forEach { error("mapping の path「$it」が重複しています") }
        val headerRow = (input["headerRow"] as? Number)?.toInt() ?: 1
        require(headerRow >= 1) { "input.headerRow は1以上である必要があります" }
        return ImportDef(
            format = TableFormat.parse(input["format"]?.toString() ?: error("input.format が必要です")),
            sheet = input["sheet"]?.toString(),
            headerRow = headerRow,
            jsonPath = input["path"]?.toString(),
            targetFile = targetFile,
            mapping = mapping,
        )
    }

    private fun parseMapping(raw: Any?): FieldMapping {
        val m = raw as? Map<*, *> ?: error("mapping の各要素はobjectである必要があります")
        val type = m["type"]?.toString() ?: "string"
        require(type in setOf("string", "int", "number", "boolean", "auto")) {
            "mapping.type は string / int / number / boolean / auto のいずれかです: $type"
        }
        val path = m["path"]?.toString()?.takeIf { it.isNotBlank() } ?: error("mapping に path が必要です")
        require('[' !in path) { "mapping.path に配列インデックスは使えません（split で配列化してください）: $path" }
        return FieldMapping(
            column = m["column"]?.toString()?.takeIf { it.isNotBlank() } ?: error("mapping に column が必要です"),
            path = path,
            type = type,
            required = m["required"] as? Boolean ?: false,
            map = (m["map"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap(),
            split = m["split"]?.toString(),
        )
    }

    private fun parseExport(raw: Map<*, *>): ExportDef {
        val sourceMap = raw["source"] as? Map<*, *> ?: error("export には source.match が必要です")
        val output = raw["output"] as? Map<*, *> ?: error("export には output.file が必要です")
        val outputFile = output["file"]?.toString()?.takeIf { it.isNotBlank() } ?: error("output.file が必要です")
        require(!outputFile.endsWith(".yaml") && !outputFile.endsWith(".yml")) {
            "エクスポートは派生成果物の生成です。YAMLへの出力はできません（原本主義）: $outputFile"
        }
        require(!outputFile.replace('\\', '/').startsWith("_")) { "予約ディレクトリへの出力はできません: $outputFile" }
        TableFormat.fromExtension(outputFile)  // 拡張子の妥当性を先に検査
        val columns = (raw["columns"] as? List<*>)?.map { entry ->
            val c = entry as? Map<*, *> ?: error("columns の各要素はobjectである必要があります")
            ExportColumn(
                header = c["header"]?.toString() ?: error("columns に header が必要です"),
                path = c["path"]?.toString() ?: error("columns に path が必要です"),
                join = c["join"]?.toString() ?: ", ",
            )
        } ?: error("columns が必要です")
        if (columns.isEmpty()) error("columns は1件以上必要です")
        return ExportDef(
            match = sourceMap["match"]?.toString()?.takeIf { it.isNotBlank() } ?: error("source.match が必要です"),
            rowsPath = sourceMap["rows"]?.toString() ?: "",
            columns = columns,
            outputFile = outputFile,
            sheet = output["sheet"]?.toString() ?: "Sheet1",
        )
    }
}
