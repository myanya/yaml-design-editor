package schema

import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import model.YNode
import model.parseYPath
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
    OBJECT("object"),   // オブジェクト（fields 必須）
    ANY("any");         // 任意の値（検証しない。メタスキーマ等、単一の型で表せない箇所の明示。R11-02）

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
 * フィールドの表示役割（R10-01）。表示解決（`Display`）が要素の要約を作るとき、
 * 予約語規約（name / label / title、description / summary）より優先して使う。
 * 検証・索引の意味論には影響しない（表示専用のヒント）。
 */
enum class FieldRole(val id: String) {
    TITLE("title"),
    SUBTITLE("subtitle");

    companion object {
        fun parse(value: String?): FieldRole? = value?.let { raw ->
            entries.firstOrNull { it.id == raw } ?: error("role は title / subtitle のいずれかである必要があります: $raw")
        }
    }
}

/**
 * 配列・map フィールドの一覧表示形式のヒント（R12-06）。未指定時は `Display.tableColumns` の自動判定に従う。
 * 検証・索引の意味論には影響しない（表示専用のヒント）。
 */
enum class CollectionDisplay(val id: String) {
    TABLE("table"),
    LIST("list");

    companion object {
        fun parse(value: String?): CollectionDisplay? = value?.let { raw ->
            entries.firstOrNull { it.id == raw } ?: error("display は table / list のいずれかである必要があります: $raw")
        }
    }
}

/**
 * enum の選択肢1件（R10-03）。データ・検証・cases のキー・AI生成の制約は常に `value` を使い、
 * label / description はフォーム表示専用。文字列だけの選択肢は label / description なしと等価。
 */
data class EnumOption(
    val value: String,
    val label: String? = null,
    val description: String? = null,
) {
    val displayLabel: String get() = label ?: value
}

/** `_schemas` 配下の文書種別。library は共通定義だけを公開し、業務文書には適用されない。 */
enum class SchemaKind(val id: String) {
    DOCUMENT("document"),
    LIBRARY("library");

    companion object {
        fun parse(value: String?): SchemaKind = when (value) {
            null, "document" -> DOCUMENT
            "library" -> LIBRARY
            else -> error("kind は document / library のいずれかである必要があります: $value")
        }
    }
}

/** 定義を一意に指す修飾名。`module#name` の文字列表現に対応する。 */
internal data class DefinitionKey(val moduleId: String, val name: String) {
    override fun toString(): String = "$moduleId#$name"
}

/** ワークスペース内の定義モジュールを修飾名で引く中央カタログ。生成・検索はスキーマ層に閉じる。 */
class SchemaCatalog private constructor(
    private val definitionsByModule: Map<String, Map<String, FieldDef>>,
) {
    internal fun find(ref: String, fallbackModuleId: String): Pair<DefinitionKey, FieldDef>? {
        val hash = ref.indexOf('#')
        val moduleId = if (hash >= 0) ref.substring(0, hash) else fallbackModuleId
        val name = if (hash >= 0) ref.substring(hash + 1) else ref
        if (moduleId.isBlank() || name.isBlank()) return null
        val key = DefinitionKey(moduleId, name)
        return definitionsByModule[moduleId]?.get(name)?.let { key to it }
    }

    companion object {
        internal fun local(moduleId: String, definitions: Map<String, FieldDef>): SchemaCatalog =
            SchemaCatalog(mapOf(moduleId to definitions))

        internal fun linked(modules: Iterable<Pair<String, Map<String, FieldDef>>>): SchemaCatalog {
            val definitions = linkedMapOf<String, Map<String, FieldDef>>()
            modules.forEach { (id, values) -> definitions.putIfAbsent(id, values) }
            return SchemaCatalog(definitions)
        }
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
    val values: List<String> = emptyList(),  // ENUM の選択肢（値のリスト。検証・cases・AI制約はこちらを使う）
    val options: List<EnumOption> = emptyList(), // ENUM のリッチ選択肢（label / description。表示専用。R10-03）
    val role: FieldRole? = null,             // 表示役割（title / subtitle。表示解決の上書き。R10-01）
    val display: CollectionDisplay? = null,  // ARRAY / MAP の一覧表示形式（table / list。未指定は自動判定。R12-06）
    val cases: Map<String, List<FieldDef>> = emptyMap(), // ENUM の値ごとに親オブジェクトへ追加される兄弟フィールド
    val item: FieldDef? = null,              // ARRAY の要素定義
    val value: FieldDef? = null,             // MAP の値定義
    val fields: List<FieldDef> = emptyList(),// OBJECT の子フィールド定義
    val idPatterns: List<String> = emptyList(), // REF の参照先 ID glob（空 = 制限なし）
    val scope: RefScope = RefScope(),        // REF の探索範囲（空 = ワークスペース全体）
    val relation: String? = null,            // REF の関係種別（例: implements / depends）。表示・カバレッジ判定に使う
    val minLength: Int? = null,              // STRING / TEXT: 最小文字数（R16-01）
    val maxLength: Int? = null,              // STRING / TEXT: 最大文字数（R16-01）
    val pattern: String? = null,             // STRING / TEXT: 正規表現（完全一致。R16-01）
    val min: Double? = null,                 // INT / NUMBER: 最小値（R16-01）
    val max: Double? = null,                 // INT / NUMBER: 最大値（R16-01）
    val default: Any? = null,                // 新規作成時の初期値
    val description: String? = null,         // フィールドの説明（フォームに表示）
    val ref: String? = null,                 // definitions 内の名前付き定義への参照
    val unknownFields: UnknownFieldsPolicy? = null, // object の未定義キー。null は warning
    /** 非修飾 `$ref` の解決元。パーサーが設定し、横断参照した定義の子でも元モジュールを保つ。 */
    internal val definitionScope: String? = null,
) {
    val displayLabel: String get() = label ?: key ?: "(root)"

    /** enum の選択肢（表示用）。リッチ選択肢（options）がなければ値そのままの選択肢を返す。 */
    fun enumOptions(): List<EnumOption> = options.ifEmpty { values.map { EnumOption(it) } }

    /** 現在値に対応する enum 選択肢（表示用）。 */
    fun optionFor(value: String?): EnumOption? = value?.let { v -> enumOptions().firstOrNull { it.value == v } }
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
 * このスキーマが定義する ID への充足要件（トレーサビリティのカバレッジルール）。
 * - 参照ベース（`requiredBy`）: 「idPattern に一致する ID は、条件を満たす参照を minCount 件以上受けること」
 * - 出現ベース（`detailIn`）: 「idPattern に一致する ID は、指定スキーマの文書に minCount 件以上出現すること」
 *   （段階的詳細化の詳細文書の存在検証。R7-02）
 */
data class CoverageRule(
    val id: String? = null,                       // ルールの識別子（表示用）
    val description: String? = null,              // 人間向けの説明
    val idPattern: String? = null,                // 対象 ID の glob（null = このスキーマの全 ID）
    val fromSchemaIds: List<String> = emptyList(),// 参照元文書のスキーマIDを限定（空 = 制限なし。参照ベースのみ）
    val relation: String? = null,                 // 参照の関係種別を限定（null = 制限なし。参照ベースのみ）
    val detailSchemaIds: List<String> = emptyList(),// 空でなければ出現ベース: このスキーマの文書への出現を数える
    val minCount: Int = 1,                        // 必要な参照数（出現ベースでは出現数）
    val level: CoverageLevel = CoverageLevel.WARNING,
    val activeFrom: String? = null,               // IDを定義する文書がこのフェーズに達してから判定（null = 常時）
) {
    /** true = 出現ベース（詳細文書の存在検証）、false = 参照ベース（被参照数）。 */
    val isOccurrence: Boolean get() = detailSchemaIds.isNotEmpty()
}

/**
 * 型を決める写像（R11-03）: 導出元要素の `path` の値を `map`（値辞書）で FieldType の id に変換する。
 * 語彙（path / map / default）は skill の列マッピング（SK-02）と揃える。
 */
data class DeriveTypeRule(
    val path: String,                        // 型を決める値のパス（導出元要素からのドット区切り）
    val map: Map<String, String> = emptyMap(), // 値辞書（例 varchar → string）
    val default: String = FieldType.STRING.id, // 辞書に無い・未記入のときの型
)

/**
 * 派生スキーマの宣言（R11-03）: 「テーブル定義書のような実質スキーマの文書」から検証スキーマを
 * ロード時にコンパイルする写像。宣言したスキーマ自身は文書にマッチせず、テンプレートに徹する。
 */
data class DeriveDef(
    val from: String,                 // 導出元文書の glob（予約ディレクトリは不可）
    val each: String? = null,         // 1文書内の複数要素から導出する場合の配列パス（null = 文書ルート）
    val idPattern: String,            // 導出スキーマのID（{パス} が導出元要素の値で置換される）
    val namePattern: String? = null,  // 導出スキーマの表示名（同上）
    val matchPattern: String,         // 導出スキーマの適用先 glob（同上）
    val fieldsPath: String,           // フィールド一覧にする配列のパス（導出元要素から相対）
    val keyPath: String,              // 各要素のこの値 → field.key
    val labelPath: String? = null,    // 任意 → field.label
    val descriptionPath: String? = null, // 任意 → field.description
    val requiredPath: String? = null, // 任意 → field.required（真偽値）
    val typeRule: DeriveTypeRule? = null, // 任意 → field.type（省略時は string）
) {
    private val fromRegex: Regex by lazy { globToRegex(from) }

    /** ワークスペース相対パスが導出元文書か（この文書の変更はスキーマ再構築の契機になる）。 */
    fun matchesSource(relPath: String): Boolean = fromRegex.matches(relPath)
}

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
    /** 整合ルール（R16-03a）: このスキーマの文書のID要素の値と、導出値との一致要件。 */
    val consistency: List<ConsistencyRule> = emptyList(),
    val phases: PhasesDef? = null,
    val source: Path,
    val kind: SchemaKind = SchemaKind.DOCUMENT,
    /** 派生スキーマの宣言（R11-03）。非 null のスキーマはテンプレートであり、自身は文書にマッチしない。 */
    val derive: DeriveDef? = null,
    /** SchemaLoader が全モジュールを読み込んだ後にリンクする。単体生成時はローカル定義だけで動作する。 */
    internal val definitionCatalog: SchemaCatalog = SchemaCatalog.local(id, definitions),
) {
    private val regex: Regex by lazy { globToRegex(match) }

    /** ワークスペース相対パス（区切りは '/'）がこのスキーマの対象か。derive 宣言スキーマはマッチしない。 */
    fun matches(relPath: String): Boolean =
        kind == SchemaKind.DOCUMENT && derive == null && match.isNotBlank() && regex.matches(relPath)

    /** `$ref` の参照先。非修飾名はフィールドの定義元、修飾名は `module#name` から引く。 */
    internal fun definitionTarget(field: FieldDef): Pair<DefinitionKey, FieldDef>? {
        val ref = field.ref ?: return null
        return definitionCatalog.find(ref, field.definitionScope ?: id)
    }

    internal fun withDefinitionCatalog(catalog: SchemaCatalog): SchemaDef = copy(definitionCatalog = catalog)

    /**
     * フィールドが参照する named definition の修飾名（`module#name`）。`$ref` でなければ null。
     * ビュー定義の一級参照・リント（R14-02）が「このフィールドはどの定義か」を外部から判定するのに使う。
     */
    fun definitionRefOf(field: FieldDef?): String? = field?.let { f -> definitionTarget(f)?.first?.toString() }

    /** $ref を1段ずつ解決する。循環はデータ走査側が有限なので許容する。 */
    fun resolve(field: FieldDef?): FieldDef? {
        var current = field ?: return null
        val seen = mutableSetOf<DefinitionKey>()
        while (current.ref != null) {
            val (key, target) = definitionTarget(current) ?: return current
            if (!seen.add(key)) return current
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
                role = current.role ?: target.role,
                display = current.display ?: target.display,
                minLength = current.minLength ?: target.minLength,
                maxLength = current.maxLength ?: target.maxLength,
                pattern = current.pattern ?: target.pattern,
                min = current.min ?: target.min,
                max = current.max ?: target.max,
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
    val deriveDef = derive
    if (kind == SchemaKind.DOCUMENT && deriveDef != null) {
        // 派生スキーマの宣言（R11-03）: 自身は文書にマッチしないため match は持たず、root は共通フィールドの object
        runCatching { globToRegex(deriveDef.from) }
            .onFailure { issues += SchemaIssue("derive.from が不正です: ${it.message}", "derive.from") }
        if (resolve(root)?.type != FieldType.OBJECT) {
            issues += SchemaIssue("derive 宣言スキーマの root は object である必要があります（共通フィールドとして合成されます）", "root")
        }
    } else if (kind == SchemaKind.DOCUMENT) {
        if (match.isBlank()) issues += SchemaIssue("適用パターンが空です", "match")
        runCatching { globToRegex(match) }
            .onFailure { issues += SchemaIssue("適用パターンが不正です: ${it.message}", "match") }
    } else {
        if (match.isNotBlank()) issues += SchemaIssue("library スキーマに match は定義できません", "match")
        if (definitions.isEmpty()) issues += SchemaIssue("library スキーマには1つ以上の definitions が必要です", "definitions")
        if (coverage.isNotEmpty()) issues += SchemaIssue("library スキーマに coverage は定義できません", "coverage")
        if (consistency.isNotEmpty()) issues += SchemaIssue("library スキーマに consistency は定義できません", "consistency")
        if (phases != null) issues += SchemaIssue("library スキーマに phases は定義できません", "phases")
    }

    // 制約語彙（R16-01）の妥当性: 型との適合・範囲の整合・正規表現の可否
    fun checkConstraints(f: FieldDef, path: String) {
        val stringy = f.type == FieldType.STRING || f.type == FieldType.TEXT
        if ((f.minLength != null || f.maxLength != null || f.pattern != null) && !stringy) {
            issues += SchemaIssue("minLength / maxLength / pattern は string / text 型でのみ使用できます", path)
        }
        if ((f.min != null || f.max != null) && f.type != FieldType.INT && f.type != FieldType.NUMBER) {
            issues += SchemaIssue("min / max は int / number 型でのみ使用できます", path)
        }
        if (f.minLength != null && f.minLength < 0) issues += SchemaIssue("minLength は 0 以上が必要です", path)
        if (f.minLength != null && f.maxLength != null && f.minLength > f.maxLength) {
            issues += SchemaIssue("minLength が maxLength を超えています", path)
        }
        if (f.min != null && f.max != null && f.min > f.max) issues += SchemaIssue("min が max を超えています", path)
        f.pattern?.let { p ->
            runCatching { Regex(p) }.onFailure { issues += SchemaIssue("pattern が正規表現として不正です: $p", path) }
        }
    }

    fun check(field: FieldDef, path: String, stack: Set<DefinitionKey>) {
        field.requiredFrom?.let { from ->
            if (phases == null) issues += SchemaIssue("requiredFrom を使うには phases の宣言が必要です", path)
            else if (from !in phases.order) issues += SchemaIssue("requiredFrom「$from」は phases に含まれていません", path)
            if (field.required) issues += SchemaIssue("required と requiredFrom は併用できません", path)
        }
        field.ref?.let { ref ->
            val located = definitionTarget(field)
            if (located == null) issues += SchemaIssue("未定義の参照です: \$$ref", path)
            else {
                val (key, target) = located
                if (key !in stack) check(target, "definitions.$key", stack + key)
                val resolved = resolve(field) ?: field
                if (resolved.cases.isNotEmpty() && resolved.type != FieldType.ENUM) {
                    issues += SchemaIssue("casesはenum型でのみ使用できます", path)
                }
                if (resolved.display != null && resolved.type != FieldType.ARRAY && resolved.type != FieldType.MAP) {
                    issues += SchemaIssue("displayはarray / map型でのみ使用できます", path)
                }
                checkConstraints(resolved, path)
            }
            return
        }
        checkConstraints(field, path)
        if (field.cases.isNotEmpty() && field.type != FieldType.ENUM) {
            issues += SchemaIssue("casesはenum型でのみ使用できます", path)
        }
        if (field.display != null && field.type != FieldType.ARRAY && field.type != FieldType.MAP) {
            issues += SchemaIssue("displayはarray / map型でのみ使用できます", path)
        }
        when (field.type) {
            FieldType.OBJECT -> {
                val keys = field.fields.mapNotNull { it.key }
                keys.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                    issues += SchemaIssue("キー「$it」が重複しています", path)
                }
                // 表示役割（R10-01）は object 内で各1つまで（複数あると表示解決が先頭のみを使い、意図が曖昧になる）
                FieldRole.entries.forEach { role ->
                    val holders = field.fields.mapNotNull { ch -> ch.key?.takeIf { (resolve(ch) ?: ch).role == role } }
                    if (holders.size > 1) {
                        issues += SchemaIssue("role「${role.id}」が複数のフィールド（${holders.joinToString(", ")}）に指定されています", path)
                    }
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
                field.values.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                    issues += SchemaIssue("選択肢の値「$it」が重複しています", "$path.values")
                }
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
    if (kind == SchemaKind.DOCUMENT) check(root, "root", emptySet())
    definitions.forEach { (name, field) ->
        check(field, "definitions.$name", setOf(DefinitionKey(id, name)))
    }
    coverage.forEachIndexed { i, rule ->
        if (rule.minCount < 1) issues += SchemaIssue("minCountは1以上が必要です", "coverage[$i]")
        if (rule.idPattern != null && rule.idPattern.isBlank()) issues += SchemaIssue("idPatternが空です", "coverage[$i]")
        rule.activeFrom?.let { from ->
            if (phases == null) issues += SchemaIssue("activeFrom を使うには phases の宣言が必要です", "coverage[$i]")
            else if (from !in phases.order) issues += SchemaIssue("activeFrom「$from」は phases に含まれていません", "coverage[$i]")
        }
    }
    consistency.forEachIndexed { i, rule ->
        if (rule.path.isEmpty()) issues += SchemaIssue("consistency[$i].path が空です", "consistency[$i]")
        if (rule.idPattern != null && rule.idPattern.isBlank()) issues += SchemaIssue("idPatternが空です", "consistency[$i]")
        rule.activeFrom?.let { from ->
            if (phases == null) issues += SchemaIssue("activeFrom を使うには phases の宣言が必要です", "consistency[$i]")
            else if (from !in phases.order) issues += SchemaIssue("activeFrom「$from」は phases に含まれていません", "consistency[$i]")
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
    fun create(original: FieldDef, depth: Int, stack: Set<DefinitionKey>): YNode {
        if (depth > 16) return YNode.Map()
        val reference = definitionTarget(original)?.first
        if (reference != null && reference in stack) return YNode.Map()
        val nextStack = if (reference == null) stack else stack + reference
        val field = resolve(original) ?: original
        if (field.default != null) return YamlIO.fromAny(field.default)
        return when (field.type) {
            FieldType.OBJECT -> {
                val entries = LinkedHashMap<String, YNode>().apply {
                    field.fields.forEach { child -> child.key?.let { put(it, create(child, depth + 1, nextStack)) } }
                }
                // enum の生成値（default または先頭の選択肢）に応じた case フィールドを実効順で合成する
                val ordered = effectiveFields(field.fields, YNode.Map(entries)).fields
                YNode.Map(LinkedHashMap<String, YNode>().apply {
                    ordered.forEach { child ->
                        val key = child.key ?: return@forEach
                        put(key, entries[key] ?: create(child, depth + 1, nextStack))
                    }
                })
            }
            FieldType.ARRAY -> YNode.Seq(
                if (field.item?.let { item -> definitionTarget(item)?.first?.let { it in nextStack } } == true) mutableListOf()
                else field.item?.let { mutableListOf(create(it, depth + 1, nextStack)) } ?: mutableListOf()
            )
            FieldType.MAP -> YNode.Map()
            FieldType.BOOLEAN -> YNode.Scalar(false)
            FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
            FieldType.ENUM -> YNode.Scalar(field.values.firstOrNull() ?: "")
            FieldType.DATE -> YNode.Scalar("2026-01-01")
            FieldType.ID -> YNode.Scalar("SAMPLE-001")
            FieldType.REF -> YNode.Scalar("")
            FieldType.TEXT, FieldType.STRING, FieldType.ANY -> YNode.Scalar("")
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

    fun loadAllWithErrors(workspace: Path, layout: WorkspaceLayout = WorkspaceLayout.of(workspace)): LoadResult {
        val dir = workspace.resolve(layout.schemasDir)
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
            runCatching { parseSchemaFile(f) }.onSuccess { schemas += it }
                .onFailure { errors += "${f.fileName}: ${it.message ?: it::class.simpleName}" }
        }
        schemas.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            errors += "スキーマID「$id」が重複しています（${duplicates.joinToString(", ") { it.source.fileName.toString() }}）"
        }
        // 組込みスキーマ（agents / activities 等の定義ゾーン用）は利用者スキーマの後ろに置く（先勝ちのため利用者定義が優先）
        val all = schemas + BuiltinSchemas.forLayout(layout).filter { builtin -> schemas.none { it.id == builtin.id } }
        val catalog = SchemaCatalog.linked(all.map { it.id to it.definitions })
        val linked = all.map { it.withDefinitionCatalog(catalog) }
        linked.take(schemas.size).forEach { schema ->
            schema.validateDefinition().forEach { issue ->
                errors += "${schema.source.fileName} [${issue.path}]: ${issue.message}"
            }
        }
        // 第3段階「導出」（R11-03）: derive 宣言を導出元文書から検証スキーマへコンパイルし、
        // 宣言スキーマの直後に挿入する（match の先勝ち規則を宣言位置で制御できるようにする）
        val derived = DerivedSchemas.compile(linked, workspace, layout)
        errors += derived.errors
        val result = if (derived.byDeclaring.isEmpty()) linked
        else linked.flatMap { s -> listOf(s) + derived.byDeclaring[s.id].orEmpty() }
        return LoadResult(result, errors)
    }

    fun parseSchemaText(text: String, source: Path, availableSchemas: List<SchemaDef> = emptyList()): SchemaDef {
        val raw = YamlIO.parseToRaw(text) as? Map<*, *> ?: error("スキーマのルートはobjectである必要があります")
        val fallbackId = source.fileName.toString().substringBeforeLast('.')
        val id = raw["id"]?.toString() ?: fallbackId
        val name = raw["name"]?.toString() ?: id
        val description = raw["description"]?.toString()
        val kind = SchemaKind.parse(raw["kind"]?.toString())
        val derive = parseDerive(raw["derive"])
        if (derive != null) {
            require(kind == SchemaKind.DOCUMENT) { "derive は library スキーマには定義できません" }
            require(raw["match"] == null) { "derive 宣言スキーマに match は定義できません（適用先は derive.match で決まります）" }
        }
        val match = raw["match"]?.toString()
            ?: if (kind == SchemaKind.DOCUMENT && derive == null) "**/*.{yaml,yml}" else ""
        val definitions = (raw["definitions"] as? Map<*, *>)?.entries?.associate { (name, value) ->
            val definition = value as? Map<*, *> ?: error("definitions.${name}: objectである必要があります")
            name.toString() to parseField(definition, key = null, definitionScope = id)
        } ?: emptyMap()
        val root = if (kind == SchemaKind.LIBRARY) {
            require(raw["root"] == null) { "library スキーマに root は定義できません" }
            FieldDef(type = FieldType.OBJECT, definitionScope = id)
        } else if (derive != null && raw["root"] == null) {
            // derive 宣言スキーマの root は任意（共通フィールドがなければ空の object）
            FieldDef(type = FieldType.OBJECT, definitionScope = id)
        } else {
            val rootMap = raw["root"] as? Map<*, *> ?: error("root定義が必要です")
            parseField(rootMap, key = null, definitionScope = id)
        }
        val coverage = parseCoverage(raw["coverage"])
        val consistency = parseConsistency(raw["consistency"])
        val phases = parsePhases(raw["phases"])
        val parsed = SchemaDef(
            id = id,
            name = name,
            description = description,
            match = match,
            root = root,
            definitions = definitions,
            coverage = coverage,
            consistency = consistency,
            phases = phases,
            source = source,
            kind = kind,
            derive = derive,
        )
        if (availableSchemas.isEmpty()) return parsed
        val modules = availableSchemas.filter { it.id != parsed.id }.map { it.id to it.definitions } +
            (parsed.id to parsed.definitions)
        val catalog = SchemaCatalog.linked(modules)
        return parsed.withDefinitionCatalog(catalog)
    }

    /** 派生スキーマ宣言（R11-03）のパース。写像の語彙（path / map / default）は SK-02 と揃える。 */
    private fun parseDerive(raw: Any?): DeriveDef? {
        if (raw == null) return null
        val m = raw as? Map<*, *> ?: error("derive は object である必要があります")
        val from = m["from"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.from（導出元文書の glob）が必要です")
        val fromNorm = from.replace('\\', '/').trimStart('/')
        require(!fromNorm.startsWith("_") && !fromNorm.startsWith(".")) { "derive.from に予約ディレクトリ・隠しディレクトリは指定できません: $from" }
        val fieldsMap = m["fields"] as? Map<*, *> ?: error("derive.fields（フィールドの写像）が必要です")
        val typeRule = when (val rawType = fieldsMap["type"]) {
            null -> null
            is Map<*, *> -> {
                val path = rawType["path"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: error("derive.fields.type には path（型を決める値のパス）が必要です")
                val dict = (rawType["map"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
                val default = rawType["default"]?.toString() ?: FieldType.STRING.id
                (dict.values + default).forEach { typeId ->
                    require(FieldType.entries.any { it.id == typeId }) { "derive.fields.type の型「$typeId」は未知の型です" }
                }
                DeriveTypeRule(path = path, map = dict, default = default)
            }
            else -> error("derive.fields.type は object（path / map / default）である必要があります")
        }
        return DeriveDef(
            from = from,
            each = m["each"]?.toString()?.takeIf { it.isNotBlank() },
            idPattern = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.id（導出スキーマのIDパターン）が必要です"),
            namePattern = m["name"]?.toString(),
            matchPattern = m["match"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.match（導出スキーマの適用先 glob）が必要です"),
            fieldsPath = fieldsMap["source"]?.toString()?.takeIf { it.isNotBlank() }
                ?: error("derive.fields.source（フィールドにする配列のパス）が必要です"),
            keyPath = fieldsMap["key"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.fields.key（キーの値パス）が必要です"),
            labelPath = fieldsMap["label"]?.toString(),
            descriptionPath = fieldsMap["description"]?.toString(),
            requiredPath = fieldsMap["required"]?.toString(),
            typeRule = typeRule,
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
        fun schemaIds(raw: Any?): List<String> = when (raw) {
            is List<*> -> raw.map { it.toString() }
            null -> emptyList()
            else -> listOf(raw.toString())
        }
        return list.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("coverage[$i]はobjectである必要があります")
            val requiredBy = m["requiredBy"] as? Map<*, *>
            val detailIn = m["detailIn"] as? Map<*, *>
            if (requiredBy != null && detailIn != null) {
                error("coverage[$i]: requiredBy（参照ベース）と detailIn（出現ベース）は併用できません")
            }
            val detailSchemaIds = schemaIds(detailIn?.get("schemaIds"))
            if (detailIn != null && detailSchemaIds.isEmpty()) {
                error("coverage[$i].detailIn には schemaIds（出現先スキーマ）が必要です")
            }
            CoverageRule(
                id = m["id"]?.toString(),
                description = m["description"]?.toString(),
                idPattern = m["idPattern"]?.toString(),
                fromSchemaIds = schemaIds(requiredBy?.get("schemaIds")),
                relation = requiredBy?.get("relation")?.toString(),
                detailSchemaIds = detailSchemaIds,
                minCount = ((detailIn ?: requiredBy)?.get("minCount") as? Number)?.toInt() ?: 1,
                level = m["level"]?.toString()?.let { value ->
                    CoverageLevel.entries.firstOrNull { it.id == value }
                        ?: error("coverage[$i].level は warning / error のいずれかである必要があります: $value")
                } ?: CoverageLevel.WARNING,
                activeFrom = m["activeFrom"]?.toString(),
            )
        }
    }

    /** 導出宣言（R16-02/03a 共通語彙）のパース。ビューの computed（Views.parseView）と共有する。 */
    internal fun parseDerivationSelector(raw: Any?, where: String): DerivationSelector {
        val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
        return DerivationSelector(
            schemaId = m["schemaId"]?.toString(),
            fileGlob = m["fileGlob"]?.toString(),
        )
    }

    internal fun parseDerivation(raw: Any?, where: String): DerivationDef {
        val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
        val source = parseDerivationSelector(m["source"] ?: error("$where.source が必要です"), "$where.source")
        require(!source.isEmpty) { "$where.source には schemaId か fileGlob が必要です" }
        val lookup = (m["lookup"] as? Map<*, *>)?.let { lm ->
            DerivationLookup(
                from = DerivationSelector(lm["schemaId"]?.toString(), lm["fileGlob"]?.toString()),
                via = parseYPath(lm["via"]?.toString() ?: error("$where.lookup.via が必要です")),
            )
        }
        return DerivationDef(
            lookup = lookup,
            source = source,
            path = parseYPath(m["path"]?.toString() ?: error("$where.path が必要です")),
            values = (m["values"] as? List<*>)?.mapIndexed { i, v ->
                parseDerivationSelector(v, "$where.values[$i]")
            } ?: emptyList(),
            map = (m["map"] as? Map<*, *>)?.entries
                ?.associate { it.key.toString() to (it.value?.toString() ?: "") } ?: emptyMap(),
            default = m["default"]?.toString(),
        )
    }

    /** 整合ルール（R16-03a）のパース。level / activeFrom の語彙は coverage と揃える。 */
    private fun parseConsistency(raw: Any?): List<ConsistencyRule> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("consistencyはリストである必要があります")
        return list.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("consistency[$i]はobjectである必要があります")
            ConsistencyRule(
                id = m["id"]?.toString(),
                description = m["description"]?.toString(),
                idPattern = m["idPattern"]?.toString(),
                path = parseYPath(m["path"]?.toString() ?: error("consistency[$i].path が必要です")),
                expected = parseDerivation(m["expected"] ?: error("consistency[$i].expected が必要です"), "consistency[$i].expected"),
                assert = m["assert"]?.toString()?.let { value ->
                    ConsistencyAssert.entries.firstOrNull { it.id == value }
                        ?: error("consistency[$i].assert は equals / lte / gte のいずれかである必要があります: $value")
                } ?: ConsistencyAssert.EQUALS,
                level = m["level"]?.toString()?.let { value ->
                    CoverageLevel.entries.firstOrNull { it.id == value }
                        ?: error("consistency[$i].level は warning / error のいずれかである必要があります: $value")
                } ?: CoverageLevel.WARNING,
                activeFrom = m["activeFrom"]?.toString(),
            )
        }
    }

    private fun parseSchemaFile(file: Path): SchemaDef = parseSchemaText(Files.readString(file), file)

    private fun parseField(m: Map<*, *>?, key: String?, definitionScope: String): FieldDef {
        if (m == null) return FieldDef(key = key, definitionScope = definitionScope)
        val typeName = m["type"]?.toString()
        val type = if (typeName == null) FieldType.STRING
            else FieldType.entries.firstOrNull { it.id == typeName } ?: error("未知の型です: $typeName")
        val fields = (m["fields"] as? List<*>)?.mapIndexed { index, f ->
            val child = f as? Map<*, *> ?: error("fields[$index]はobjectである必要があります")
            parseField(child, child["key"]?.toString(), definitionScope)
        } ?: emptyList()
        val item = m["item"]?.let {
            parseField(it as? Map<*, *> ?: error("itemはobjectである必要があります"), null, definitionScope)
        }
        val value = m["value"]?.let {
            parseField(it as? Map<*, *> ?: error("valueはobjectである必要があります"), null, definitionScope)
        }
        // enum の選択肢: 文字列、または {value, label, description} の object（リッチ選択肢。R10-03）
        val options = (m["values"] as? List<*>)?.mapIndexed { index, entry ->
            when (entry) {
                is Map<*, *> -> EnumOption(
                    value = entry["value"]?.toString() ?: error("values[$index] には value が必要です"),
                    label = entry["label"]?.toString(),
                    description = entry["description"]?.toString(),
                )
                else -> EnumOption(entry.toString())
            }
        } ?: emptyList()
        val cases = when (val rawCases = m["cases"]) {
            null -> emptyMap()
            is Map<*, *> -> rawCases.entries.associate { (caseValue, defs) ->
                val list = defs as? List<*> ?: error("cases.${caseValue}はフィールド定義のリストである必要があります")
                caseValue.toString() to list.mapIndexed { index, f ->
                    val child = f as? Map<*, *> ?: error("cases.${caseValue}[$index]はobjectである必要があります")
                    parseField(child, child["key"]?.toString(), definitionScope)
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
            values = options.map { it.value },
            options = options,
            role = FieldRole.parse(m["role"]?.toString()),
            display = CollectionDisplay.parse(m["display"]?.toString()),
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
            minLength = (m["minLength"] as? Number)?.toInt(),
            maxLength = (m["maxLength"] as? Number)?.toInt(),
            pattern = m["pattern"]?.toString(),
            min = (m["min"] as? Number)?.toDouble(),
            max = (m["max"] as? Number)?.toDouble(),
            default = m["default"],
            description = m["description"]?.toString(),
            ref = (m["\$ref"] ?: m["ref"])?.toString(),
            unknownFields = m["unknownFields"]?.toString()?.let { raw ->
                UnknownFieldsPolicy.parse(raw) ?: error("unknownFields は allow / warning / error のいずれかである必要があります: $raw")
            },
            definitionScope = definitionScope,
        )
    }
}
