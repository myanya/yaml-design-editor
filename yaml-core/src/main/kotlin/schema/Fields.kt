package schema

/*
 * スキーマの構造型モデル（3層コア移行 Phase 3・YM-004で Schema.kt から分離）。
 * このファイルは単一文書の構造（フィールド型・$ref カタログ）だけを扱い、
 * 意味宣言（entity / relation / coverage）・ワークスペース探索・Path を持ち込まない（ADR-0002）。
 *
 * 注意: DefinitionKey / SchemaCatalog の生成・検索と FieldDef.definitionScope は
 * スキーマ層（SchemaLoader / SchemaDef）専用のAPI。モジュール分割のため internal から
 * public へ広げているが、一般コードから直接使わない（ローダ移動後に internal へ戻す）。
 */

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

/**
 * `_schemas` 配下の文書種別。library は共通定義だけを公開し、業務文書には適用されない。
 * relations は関係レジストリ（R20-02。ARCHITECTURE §4.30）で、同様に文書には適用されない。
 */
enum class SchemaKind(val id: String) {
    DOCUMENT("document"),
    LIBRARY("library"),
    RELATIONS("relations");

    companion object {
        fun parse(value: String?): SchemaKind = when (value) {
            null, "document" -> DOCUMENT
            "library" -> LIBRARY
            "relations" -> RELATIONS
            else -> error("kind は document / library / relations のいずれかである必要があります: $value")
        }
    }
}

/** 定義を一意に指す修飾名。`module#name` の文字列表現に対応する（スキーマ層専用）。 */
data class DefinitionKey(val moduleId: String, val name: String) {
    override fun toString(): String = "$moduleId#$name"
}

/** ワークスペース内の定義モジュールを修飾名で引く中央カタログ。生成・検索はスキーマ層に閉じる。 */
class SchemaCatalog private constructor(
    private val definitionsByModule: Map<String, Map<String, FieldDef>>,
) {
    fun find(ref: String, fallbackModuleId: String): Pair<DefinitionKey, FieldDef>? {
        val hash = ref.indexOf('#')
        val moduleId = if (hash >= 0) ref.substring(0, hash) else fallbackModuleId
        val name = if (hash >= 0) ref.substring(hash + 1) else ref
        if (moduleId.isBlank() || name.isBlank()) return null
        val key = DefinitionKey(moduleId, name)
        return definitionsByModule[moduleId]?.get(name)?.let { key to it }
    }

    /** つながっている全モジュールの定義（モジュール → 名前 → 定義）。構造のスキーマの導出が、他のモジュールの定義も導出するのに使う。 */
    fun modules(): Map<String, Map<String, FieldDef>> = definitionsByModule

    companion object {
        fun local(moduleId: String, definitions: Map<String, FieldDef>): SchemaCatalog =
            SchemaCatalog(mapOf(moduleId to definitions))

        fun linked(modules: Iterable<Pair<String, Map<String, FieldDef>>>): SchemaCatalog {
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
    val refTarget: RefTarget = RefTarget.ENTITY, // REF の参照対象種別（R21-03。document = 文書ノード参照）
    val minLength: Int? = null,              // STRING / TEXT: 最小文字数（R16-01）
    val maxLength: Int? = null,              // STRING / TEXT: 最大文字数（R16-01）
    val pattern: String? = null,             // STRING / TEXT / ID: 正規表現（完全一致。R16-01 / UX-79）
    val min: Double? = null,                 // INT / NUMBER: 最小値（R16-01）
    val max: Double? = null,                 // INT / NUMBER: 最大値（R16-01）
    val default: Any? = null,                // 新規作成時の初期値
    val description: String? = null,         // フィールドの説明（フォームに表示）
    val ref: String? = null,                 // definitions 内の名前付き定義への参照
    val unknownFields: UnknownFieldsPolicy? = null, // object の未定義キー。null は warning
    /** 非修飾 `$ref` の解決元。パーサーが設定し、横断参照した定義の子でも元モジュールを保つ（スキーマ層専用）。 */
    val definitionScope: String? = null,
) {
    val displayLabel: String get() = label ?: key ?: "(root)"

    /** enum の選択肢（表示用）。リッチ選択肢（options）がなければ値そのままの選択肢を返す。 */
    fun enumOptions(): List<EnumOption> = options.ifEmpty { values.map { EnumOption(it) } }

    /** 現在値に対応する enum 選択肢（表示用）。 */
    fun optionFor(value: String?): EnumOption? = value?.let { v -> enumOptions().firstOrNull { it.value == v } }
}

/**
 * ref フィールドの参照対象種別（R21-03。ARCHITECTURE §4.31）。既定は entity（論理ID）。
 * document は文書ノード（documentId ＝ `_document.id` ＞ 相対パス）への参照で、別名前空間として解決する。
 */
enum class RefTarget(val id: String) {
    ENTITY("entity"),
    DOCUMENT("document");

    companion object {
        fun parse(value: String?): RefTarget = when (value) {
            null, "entity" -> ENTITY
            "document" -> DOCUMENT
            else -> error("target は entity / document のいずれかである必要があります: $value")
        }
    }
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
