package schema

import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import model.YNode

/**
 * フィールドの型。スキーマ YAML の `type:` に対応する。
 */
enum class FieldType(val id: String) {
    STRING("string"),   // 1行テキスト
    TEXT("text"),       // 複数行テキスト
    INT("int"),         // 整数
    NUMBER("number"),   // 数値（小数可）
    BOOLEAN("boolean"), // 真偽値
    ENUM("enum"),       // 選択肢（values 必須）
    DATE("date"),       // 日付 YYYY-MM-DD
    ID("id"),           // 論理要素の ID（異なる文書での同一 ID を許容）
    REF("ref"),         // 他要素の ID への参照（リンク化される）
    ARRAY("array"),     // 配列（item 必須）
    MAP("map"),         // 任意キーの連想配列（value 必須）
    OBJECT("object");   // オブジェクト（fields 必須）

    companion object {
        fun of(s: String?): FieldType = entries.firstOrNull { it.id == s } ?: STRING
    }
}

/** `object` に含まれる未定義キーの検証方針。未指定時は WARNING。 */
enum class UnknownFieldsPolicy(val id: String) {
    ALLOW("allow"),
    WARNING("warning"),
    ERROR("error");

    companion object {
        fun parse(value: String?): UnknownFieldsPolicy? =
            value?.let { raw -> entries.firstOrNull { it.id == raw } }
    }
}

/**
 * スキーマ内の 1 フィールド定義。
 */
data class FieldDef(
    val key: String? = null,          // 親オブジェクト内でのキー（ルート・配列要素では null）
    val label: String? = null,        // 表示ラベル（未指定なら key）
    val type: FieldType = FieldType.STRING,
    val required: Boolean = false,
    val values: List<String> = emptyList(),  // ENUM の選択肢
    val item: FieldDef? = null,              // ARRAY の要素定義
    val value: FieldDef? = null,             // MAP の値定義
    val fields: List<FieldDef> = emptyList(),// OBJECT の子フィールド定義
    val idPatterns: List<String> = emptyList(), // REF の参照先 ID glob（空 = 制限なし）
    val scope: RefScope = RefScope(),        // REF の探索範囲（空 = ワークスペース全体）
    val relation: String? = null,            // REF の関係種別（例: implements / depends）。表示・カバレッジ判定に使う
    val default: Any? = null,                // 新規作成時の初期値
    val description: String? = null,         // フィールドの説明（フォームに表示）
    val ref: String? = null,                 // definitions 内の名前付き定義への参照
    val unknownFields: UnknownFieldsPolicy? = null, // object の未定義キー。null は warning
) {
    val displayLabel: String get() = label ?: key ?: "(root)"
}

data class RefScope(
    val schemaIds: List<String> = emptyList(),
    val documentIds: List<String> = emptyList(),
)

/** カバレッジルールの重大度。 */
enum class CoverageLevel(val id: String) {
    WARNING("warning"),
    ERROR("error");
}

/**
 * このスキーマが定義する ID への被参照要件（トレーサビリティのカバレッジルール）。
 * 「idPattern に一致する ID は、条件を満たす参照を minCount 件以上受けること」を表す。
 */
data class CoverageRule(
    val id: String? = null,                       // ルールの識別子（表示用）
    val description: String? = null,              // 人間向けの説明
    val idPattern: String? = null,                // 対象 ID の glob（null = このスキーマの全 ID）
    val fromSchemaIds: List<String> = emptyList(),// 参照元文書のスキーマIDを限定（空 = 制限なし）
    val relation: String? = null,                 // 参照の関係種別を限定（null = 制限なし）
    val minCount: Int = 1,                        // 必要な参照数
    val level: CoverageLevel = CoverageLevel.WARNING,
)

/**
 * スキーマ 1 件。`match` の glob にマッチするファイルへ適用される。
 */
data class SchemaDef(
    val id: String,
    val name: String,
    val match: String,
    val root: FieldDef,
    val definitions: Map<String, FieldDef> = emptyMap(),
    val coverage: List<CoverageRule> = emptyList(),
    val source: Path,
) {
    private val regex: Regex by lazy { globToRegex(match) }

    /** ワークスペース相対パス（区切りは '/'）がこのスキーマの対象か。 */
    fun matches(relPath: String): Boolean = regex.matches(relPath)

    /** $ref を1段ずつ解決する。循環はデータ走査側が有限なので許容する。 */
    fun resolve(field: FieldDef?): FieldDef? {
        var current = field ?: return null
        val seen = mutableSetOf<String>()
        while (current.ref != null && seen.add(current.ref)) {
            val target = definitions[current.ref] ?: return current
            current = target.copy(
                key = current.key ?: target.key,
                label = current.label ?: target.label,
                required = current.required || target.required,
                description = current.description ?: target.description,
                idPatterns = current.idPatterns.ifEmpty { target.idPatterns },
                scope = if (current.scope == RefScope()) target.scope else current.scope,
                relation = current.relation ?: target.relation,
                unknownFields = current.unknownFields ?: target.unknownFields,
            )
        }
        return current
    }
}

data class SchemaIssue(val message: String, val path: String)

/** スキーマ定義自体を検証するメタスキーマ検証。 */
fun SchemaDef.validateDefinition(): List<SchemaIssue> {
    val issues = mutableListOf<SchemaIssue>()
    if (name.isBlank()) issues += SchemaIssue("スキーマ名が空です", "name")
    if (id.isBlank()) issues += SchemaIssue("スキーマIDが空です", "id")
    if (match.isBlank()) issues += SchemaIssue("適用パターンが空です", "match")
    runCatching { globToRegex(match) }.onFailure { issues += SchemaIssue("適用パターンが不正です: ${it.message}", "match") }

    fun check(field: FieldDef, path: String, stack: Set<String>) {
        field.ref?.let { ref ->
            val target = definitions[ref]
            if (target == null) issues += SchemaIssue("未定義の参照です: \$$ref", path)
            else if (ref !in stack) check(target, "definitions.$ref", stack + ref)
            return
        }
        when (field.type) {
            FieldType.OBJECT -> {
                val keys = field.fields.mapNotNull { it.key }
                keys.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                    issues += SchemaIssue("キー「$it」が重複しています", path)
                }
                field.fields.forEachIndexed { index, child ->
                    if (child.key.isNullOrBlank()) issues += SchemaIssue("フィールドのキーが空です", "$path.fields[$index]")
                    check(child, "$path.${child.key ?: index}", stack)
                }
            }
            FieldType.ARRAY -> {
                if (field.item == null) issues += SchemaIssue("arrayには要素定義が必要です", "$path.item")
                else check(field.item, "$path.item", stack)
            }
            FieldType.MAP -> {
                if (field.value == null) issues += SchemaIssue("mapには値定義が必要です", "$path.value")
                else check(field.value, "$path.value", stack)
            }
            FieldType.ENUM -> if (field.values.isEmpty()) issues += SchemaIssue("enumには選択肢が必要です", "$path.values")
            FieldType.REF -> Unit
            else -> Unit
        }
    }
    check(root, "root", emptySet())
    definitions.forEach { (name, field) -> check(field, "definitions.$name", setOf(name)) }
    coverage.forEachIndexed { i, rule ->
        if (rule.minCount < 1) issues += SchemaIssue("minCountは1以上が必要です", "coverage[$i]")
        if (rule.idPattern != null && rule.idPattern.isBlank()) issues += SchemaIssue("idPatternが空です", "coverage[$i]")
    }
    return issues.distinct()
}

/** プレビューや新規作成に使うサンプルYAMLツリーを生成する。 */
fun SchemaDef.generateSample(): YNode {
    fun create(original: FieldDef, depth: Int, stack: Set<String>): YNode {
        if (depth > 16) return YNode.Map()
        original.ref?.let { ref ->
            if (ref in stack) return YNode.Map()
            return definitions[ref]?.let { create(it, depth + 1, stack + ref) } ?: YNode.Scalar("")
        }
        val field = original
        if (field.default != null) return YamlIO.fromAny(field.default)
        return when (field.type) {
            FieldType.OBJECT -> YNode.Map(LinkedHashMap<String, YNode>().apply {
                field.fields.forEach { child -> child.key?.let { put(it, create(child, depth + 1, stack)) } }
            })
            FieldType.ARRAY -> YNode.Seq(
                if (field.item?.ref?.let { it in stack } == true) mutableListOf()
                else field.item?.let { mutableListOf(create(it, depth + 1, stack)) } ?: mutableListOf()
            )
            FieldType.MAP -> YNode.Map()
            FieldType.BOOLEAN -> YNode.Scalar(false)
            FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
            FieldType.ENUM -> YNode.Scalar(field.values.firstOrNull() ?: "")
            FieldType.DATE -> YNode.Scalar("2026-01-01")
            FieldType.ID -> YNode.Scalar("SAMPLE-001")
            FieldType.REF -> YNode.Scalar("")
            FieldType.TEXT, FieldType.STRING -> YNode.Scalar("")
        }
    }
    return create(root, 0, emptySet())
}

/**
 * glob パターンを正規表現に変換する（OS 非依存。パス区切りは '/' 前提）。
 * 対応: `**`（区切りを跨ぐ任意）, `*`（区切りを含まない任意）, `?`（1文字）, `{a,b}`（選択）
 */
fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var braceDepth = 0
    var i = 0
    while (i < glob.length) {
        when (val c = glob[i]) {
            '*' -> {
                if (i + 1 < glob.length && glob[i + 1] == '*') {
                    sb.append(".*")
                    i++
                    // "**/" は末尾のスラッシュごと吸収（"a/**/b" が "a/b" にもマッチするように）
                    if (i + 1 < glob.length && glob[i + 1] == '/') {
                        sb.setLength(sb.length - 2)
                        sb.append("(?:.*/)?")
                        i++
                    }
                } else {
                    sb.append("[^/]*")
                }
            }
            '?' -> sb.append("[^/]")
            '{' -> { sb.append('('); braceDepth++ }
            '}' -> { sb.append(')'); if (braceDepth > 0) braceDepth-- }
            ',' -> if (braceDepth > 0) sb.append('|') else sb.append(',')
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    sb.append('$')
    return Regex(sb.toString())
}

/**
 * ワークスペース直下の `_schemas/` からスキーマ定義（*.yaml / *.yml）を読み込む。
 */
object SchemaLoader {

    const val SCHEMA_DIR = "_schemas"

    /** エージェント実行の予約ディレクトリ（セルフホスティング。ROADMAP §4.2）。 */
    const val AGENTS_DIR = "_agents"
    const val ACTIVITIES_DIR = "_activities"
    const val RUNS_DIR = "_runs"
    const val EVALS_DIR = "_evals"

    data class LoadResult(val schemas: List<SchemaDef>, val errors: List<String>)

    fun loadAll(workspace: Path): List<SchemaDef> = loadAllWithErrors(workspace).schemas

    fun loadAllWithErrors(workspace: Path): LoadResult {
        val dir = workspace.resolve(SCHEMA_DIR)
        if (!Files.isDirectory(dir)) return LoadResult(emptyList(), emptyList())
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
        val schemas = mutableListOf<SchemaDef>()
        val errors = mutableListOf<String>()
        files.forEach { f ->
            runCatching { parseSchemaFile(f) }.onSuccess { schema ->
                schemas += schema
                schema.validateDefinition().forEach { issue ->
                    errors += "${f.fileName} [${issue.path}]: ${issue.message}"
                }
            }.onFailure { errors += "${f.fileName}: ${it.message ?: it::class.simpleName}" }
        }
        schemas.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            errors += "スキーマID「$id」が重複しています（${duplicates.joinToString(", ") { it.source.fileName.toString() }}）"
        }
        // 組込みスキーマ（_agents / _activities）は利用者スキーマの後ろに置く（先勝ちのため利用者定義が優先）
        schemas += BuiltinSchemas.all.filter { builtin -> schemas.none { it.id == builtin.id } }
        return LoadResult(schemas, errors)
    }

    fun parseSchemaText(text: String, source: Path): SchemaDef {
        val raw = YamlIO.parseToRaw(text) as? Map<*, *> ?: error("スキーマのルートはobjectである必要があります")
        val fallbackId = source.fileName.toString().substringBeforeLast('.')
        val id = raw["id"]?.toString() ?: fallbackId
        val name = raw["name"]?.toString() ?: id
        val match = raw["match"]?.toString() ?: "**/*.{yaml,yml}"
        val definitions = (raw["definitions"] as? Map<*, *>)?.entries?.associate { (name, value) ->
            val definition = value as? Map<*, *> ?: error("definitions.${name}: objectである必要があります")
            name.toString() to parseField(definition, key = null)
        } ?: emptyMap()
        val rootMap = raw["root"] as? Map<*, *> ?: error("root定義が必要です")
        val root = parseField(rootMap, key = null)
        val coverage = parseCoverage(raw["coverage"])
        return SchemaDef(id = id, name = name, match = match, root = root, definitions = definitions, coverage = coverage, source = source)
    }

    private fun parseCoverage(raw: Any?): List<CoverageRule> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("coverageはリストである必要があります")
        return list.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("coverage[$i]はobjectである必要があります")
            val requiredBy = m["requiredBy"] as? Map<*, *>
            val fromSchemaIds = when (val v = requiredBy?.get("schemaIds")) {
                is List<*> -> v.map { it.toString() }
                null -> emptyList()
                else -> listOf(v.toString())
            }
            CoverageRule(
                id = m["id"]?.toString(),
                description = m["description"]?.toString(),
                idPattern = m["idPattern"]?.toString(),
                fromSchemaIds = fromSchemaIds,
                relation = requiredBy?.get("relation")?.toString(),
                minCount = (requiredBy?.get("minCount") as? Number)?.toInt() ?: 1,
                level = m["level"]?.toString()?.let { value ->
                    CoverageLevel.entries.firstOrNull { it.id == value }
                        ?: error("coverage[$i].level は warning / error のいずれかである必要があります: $value")
                } ?: CoverageLevel.WARNING,
            )
        }
    }

    private fun parseSchemaFile(file: Path): SchemaDef = parseSchemaText(Files.readString(file), file)

    private fun parseField(m: Map<*, *>?, key: String?): FieldDef {
        if (m == null) return FieldDef(key = key)
        val typeName = m["type"]?.toString()
        val type = if (typeName == null) FieldType.STRING
            else FieldType.entries.firstOrNull { it.id == typeName } ?: error("未知の型です: $typeName")
        val fields = (m["fields"] as? List<*>)?.mapIndexed { index, f ->
            val child = f as? Map<*, *> ?: error("fields[$index]はobjectである必要があります")
            parseField(child, child["key"]?.toString())
        } ?: emptyList()
        val item = m["item"]?.let { parseField(it as? Map<*, *> ?: error("itemはobjectである必要があります"), null) }
        val value = m["value"]?.let { parseField(it as? Map<*, *> ?: error("valueはobjectである必要があります"), null) }
        val values = (m["values"] as? List<*>)?.map { it.toString() } ?: emptyList()
        val idPatterns = when (val rawPatterns = m["idPatterns"] ?: m["idPattern"]) {
            is List<*> -> rawPatterns.map { it.toString() }
            null -> emptyList()
            else -> listOf(rawPatterns.toString())
        }
        val scopeMap = m["scope"] as? Map<*, *>
        fun stringList(key: String): List<String> = when (val value = scopeMap?.get(key)) {
            is List<*> -> value.map { it.toString() }
            null -> emptyList()
            else -> listOf(value.toString())
        }
        return FieldDef(
            key = key,
            label = m["label"]?.toString(),
            type = type,
            required = (m["required"] as? Boolean) ?: false,
            values = values,
            item = item,
            value = value,
            fields = fields,
            idPatterns = idPatterns,
            scope = RefScope(
                schemaIds = stringList("schemaIds"),
                documentIds = stringList("documentIds"),
            ),
            relation = m["relation"]?.toString(),
            default = m["default"],
            description = m["description"]?.toString(),
            ref = (m["\$ref"] ?: m["ref"])?.toString(),
            unknownFields = m["unknownFields"]?.toString()?.let { raw ->
                UnknownFieldsPolicy.parse(raw) ?: error("unknownFields は allow / warning / error のいずれかである必要があります: $raw")
            },
        )
    }
}
