package schema

import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import model.YNode
import model.scalarText

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
    val requiredFrom: String? = null, // このフェーズ以降で必須（累積的。phases 宣言が前提。required との併用不可）
    val values: List<String> = emptyList(),  // ENUM の選択肢
    val cases: Map<String, List<FieldDef>> = emptyMap(), // ENUM の値ごとに親オブジェクトへ追加される兄弟フィールド
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

/**
 * フェーズ機構（P7）: 段階的詳細化の工程進行の宣言。
 * 文書はルート直下の `key` で現在フェーズを申告し、`requiredFrom` / `activeFrom` の判定基準になる。
 */
data class PhasesDef(
    val key: String = "phase",        // 現在フェーズを申告するルート直下のフィールドキー
    val order: List<String>,          // フェーズの順序（先頭が最初の工程）
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
    val activeFrom: String? = null,               // IDを定義する文書がこのフェーズに達してから判定（null = 常時）
)

/**
 * スキーマ 1 件。`match` の glob にマッチするファイルへ適用される。
 */
data class SchemaDef(
    val id: String,
    val name: String,
    val description: String? = null,
    val match: String,
    val root: FieldDef,
    val definitions: Map<String, FieldDef> = emptyMap(),
    val coverage: List<CoverageRule> = emptyList(),
    val phases: PhasesDef? = null,
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
                requiredFrom = current.requiredFrom ?: target.requiredFrom,
                description = current.description ?: target.description,
                idPatterns = current.idPatterns.ifEmpty { target.idPatterns },
                scope = if (current.scope == RefScope()) target.scope else current.scope,
                relation = current.relation ?: target.relation,
                unknownFields = current.unknownFields ?: target.unknownFields,
                cases = current.cases.ifEmpty { target.cases },
            )
        }
        return current
    }
}

/** 文書ルートから現在フェーズの申告値を読む（phases 未宣言なら null）。 */
fun SchemaDef.currentPhaseValue(root: YNode?): String? =
    phases?.let { p -> scalarText((root as? YNode.Map)?.entries?.get(p.key)) }

/** 現在フェーズの順序位置。未申告・未知の値は先頭フェーズ（最も緩い判定）。phases 未宣言なら null。 */
fun SchemaDef.currentPhaseIndex(root: YNode?): Int? =
    phases?.let { p -> p.order.indexOf(currentPhaseValue(root)).coerceAtLeast(0) }

/**
 * フェーズを考慮した実効 required。requiredFrom は「そのフェーズ以降で必須」（累積的）。
 * 検証・進捗計算・フォーム表示はこの判定を共有する（ARCHITECTURE §4.15）。
 */
fun SchemaDef.requiredAt(field: FieldDef, phaseIndex: Int?): Boolean {
    val from = field.requiredFrom ?: return field.required
    val p = phases ?: return field.required
    if (phaseIndex == null) return field.required
    val idx = p.order.indexOf(from)
    return idx in 0..phaseIndex
}

/** 非アクティブな case 由来キーの情報（判別フィールドと、そのキーが有効になる enum 値）。 */
data class InactiveCase(val discriminator: FieldDef, val values: List<String>)

/** object の実効フィールド合成の結果。 */
data class EffectiveFields(
    val fields: List<FieldDef>,
    /** 実効フィールドに含まれないが、いずれかの case で定義されているキー → その案内情報 */
    val inactive: Map<String, InactiveCase>,
)

/**
 * object の子フィールド一覧に、データノードの enum 値に応じた case フィールドを合成して返す。
 * object のフィールドを列挙する側（検証・フォーム・索引・AI変換・初期値生成）は必ずこの関数を通すこと。
 * case フィールドは判別フィールドの直後に挿入される。追加されたフィールド自身が cases つき enum の場合も、
 * 同じ親 object 上で続けて展開する。
 */
fun SchemaDef.effectiveFields(fields: List<FieldDef>, node: YNode.Map?): EffectiveFields {
    val result = mutableListOf<FieldDef>()
    val inactive = LinkedHashMap<String, InactiveCase>()
    fun append(child: FieldDef, depth: Int) {
        result += child
        // $ref を介した自己参照で不正な無限展開にならないよう、スキーマ走査と同程度の上限を設ける。
        if (depth > 32) return
        val eff = resolve(child) ?: child
        if (eff.type != FieldType.ENUM || eff.cases.isEmpty()) return
        val current = child.key?.let { k -> scalarText(node?.entries?.get(k)) }
        if (current != null) eff.cases[current].orEmpty().forEach { append(it, depth + 1) }
        for ((value, caseFields) in eff.cases) {
            if (value == current) continue
            for (caseField in caseFields) {
                val key = caseField.key ?: continue
                val enabling = eff.values.filter { v -> eff.cases[v].orEmpty().any { it.key == key } }
                    .ifEmpty { eff.cases.filterValues { list -> list.any { it.key == key } }.keys.toList() }
                inactive.putIfAbsent(key, InactiveCase(eff, enabling))
            }
        }
    }
    fields.forEach { append(it, 0) }
    result.forEach { it.key?.let(inactive::remove) }
    return EffectiveFields(result, inactive)
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
        field.requiredFrom?.let { from ->
            if (phases == null) issues += SchemaIssue("requiredFrom を使うには phases の宣言が必要です", path)
            else if (from !in phases.order) issues += SchemaIssue("requiredFrom「$from」は phases に含まれていません", path)
            if (field.required) issues += SchemaIssue("required と requiredFrom は併用できません", path)
        }
        field.ref?.let { ref ->
            val target = definitions[ref]
            if (target == null) issues += SchemaIssue("未定義の参照です: \$$ref", path)
            else {
                if (ref !in stack) check(target, "definitions.$ref", stack + ref)
                val resolved = resolve(field) ?: field
                if (resolved.cases.isNotEmpty() && resolved.type != FieldType.ENUM) {
                    issues += SchemaIssue("casesはenum型でのみ使用できます", path)
                }
            }
            return
        }
        if (field.cases.isNotEmpty() && field.type != FieldType.ENUM) {
            issues += SchemaIssue("casesはenum型でのみ使用できます", path)
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
                // case 由来キーの衝突検査（共通フィールド・別の判別フィールドの cases と重複させない）
                val commonKeys = keys.toSet()
                val caseKeyOwner = mutableMapOf<String, String>()
                field.fields.forEach { child ->
                    val eff = resolve(child) ?: child
                    if (eff.type != FieldType.ENUM || eff.cases.isEmpty()) return@forEach
                    val discriminator = child.key ?: return@forEach
                    eff.cases.values.flatten().mapNotNull { it.key }.distinct().forEach { caseKey ->
                        if (caseKey in commonKeys) {
                            issues += SchemaIssue("caseのキー「$caseKey」が既存のフィールドと重複しています", "$path.$discriminator.cases")
                        } else {
                            val owner = caseKeyOwner.putIfAbsent(caseKey, discriminator)
                            if (owner != null && owner != discriminator) {
                                issues += SchemaIssue("caseのキー「$caseKey」が「$owner」と「$discriminator」の両方のcasesに現れています", path)
                            }
                        }
                    }
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
            FieldType.ENUM -> {
                if (field.values.isEmpty()) issues += SchemaIssue("enumには選択肢が必要です", "$path.values")
                field.cases.forEach { (value, caseFields) ->
                    if (value !in field.values) {
                        issues += SchemaIssue("casesの値「$value」は選択肢に含まれていません", "$path.cases")
                    }
                    caseFields.mapNotNull { it.key }.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                        issues += SchemaIssue("キー「$it」が重複しています", "$path.cases.$value")
                    }
                    caseFields.forEachIndexed { index, caseField ->
                        if (caseField.key.isNullOrBlank()) issues += SchemaIssue("フィールドのキーが空です", "$path.cases.$value[$index]")
                        check(caseField, "$path.cases.$value.${caseField.key ?: index}", stack)
                    }
                }
            }
            FieldType.REF -> Unit
            else -> Unit
        }
    }
    check(root, "root", emptySet())
    definitions.forEach { (name, field) -> check(field, "definitions.$name", setOf(name)) }
    coverage.forEachIndexed { i, rule ->
        if (rule.minCount < 1) issues += SchemaIssue("minCountは1以上が必要です", "coverage[$i]")
        if (rule.idPattern != null && rule.idPattern.isBlank()) issues += SchemaIssue("idPatternが空です", "coverage[$i]")
        rule.activeFrom?.let { from ->
            if (phases == null) issues += SchemaIssue("activeFrom を使うには phases の宣言が必要です", "coverage[$i]")
            else if (from !in phases.order) issues += SchemaIssue("activeFrom「$from」は phases に含まれていません", "coverage[$i]")
        }
    }
    phases?.let { p ->
        if (p.order.isEmpty()) issues += SchemaIssue("phases には1つ以上のフェーズが必要です", "phases")
        if (p.key.isBlank()) issues += SchemaIssue("phases のフェーズ申告キーが空です", "phases.key")
        p.order.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
            issues += SchemaIssue("フェーズ「$it」が重複しています", "phases.order")
        }
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
            FieldType.OBJECT -> {
                val entries = LinkedHashMap<String, YNode>().apply {
                    field.fields.forEach { child -> child.key?.let { put(it, create(child, depth + 1, stack)) } }
                }
                // enum の生成値（default または先頭の選択肢）に応じた case フィールドを実効順で合成する
                val ordered = effectiveFields(field.fields, YNode.Map(entries)).fields
                YNode.Map(LinkedHashMap<String, YNode>().apply {
                    ordered.forEach { child ->
                        val key = child.key ?: return@forEach
                        put(key, entries[key] ?: create(child, depth + 1, stack))
                    }
                })
            }
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
    const val SKILLS_DIR = "_skills"
    const val VIEWS_DIR = "_views"

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
        val description = raw["description"]?.toString()
        val match = raw["match"]?.toString() ?: "**/*.{yaml,yml}"
        val definitions = (raw["definitions"] as? Map<*, *>)?.entries?.associate { (name, value) ->
            val definition = value as? Map<*, *> ?: error("definitions.${name}: objectである必要があります")
            name.toString() to parseField(definition, key = null)
        } ?: emptyMap()
        val rootMap = raw["root"] as? Map<*, *> ?: error("root定義が必要です")
        val root = parseField(rootMap, key = null)
        val coverage = parseCoverage(raw["coverage"])
        val phases = parsePhases(raw["phases"])
        return SchemaDef(
            id = id,
            name = name,
            description = description,
            match = match,
            root = root,
            definitions = definitions,
            coverage = coverage,
            phases = phases,
            source = source,
        )
    }

    private fun parsePhases(raw: Any?): PhasesDef? = when (raw) {
        null -> null
        is List<*> -> PhasesDef(order = raw.map { it.toString() })
        is Map<*, *> -> PhasesDef(
            key = raw["key"]?.toString() ?: "phase",
            order = (raw["order"] as? List<*>)?.map { it.toString() } ?: error("phases.order（フェーズの順序リスト）が必要です"),
        )
        else -> error("phases はフェーズのリスト、または key / order を持つ object である必要があります")
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
                activeFrom = m["activeFrom"]?.toString(),
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
        val cases = when (val rawCases = m["cases"]) {
            null -> emptyMap()
            is Map<*, *> -> rawCases.entries.associate { (caseValue, defs) ->
                val list = defs as? List<*> ?: error("cases.${caseValue}はフィールド定義のリストである必要があります")
                caseValue.toString() to list.mapIndexed { index, f ->
                    val child = f as? Map<*, *> ?: error("cases.${caseValue}[$index]はobjectである必要があります")
                    parseField(child, child["key"]?.toString())
                }
            }
            else -> error("casesはobject（enum値 → フィールド定義リスト）である必要があります")
        }
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
            requiredFrom = m["requiredFrom"]?.toString(),
            values = values,
            cases = cases,
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
