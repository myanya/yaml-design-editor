package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.structure.UnknownFieldsPolicy

/*
 * 利用者が書くスキーマ（意味のスキーマ）の、項目の木のモデル。
 * 3層コア移行 Phase 3（YM-004）で Schema.kt から分離して yaml-core に置いていたが、P27 段C の C-2（2026-09-21）で
 * semantic-core へ移した——この木は、ID・参照・関係・参照先の範囲・フェーズ・表示のヒントという「意味」を持つため
 * （CORE-CALCULUS §25: 文法プリミティブは ID・参照・関係・フェーズの意味を知らない）。
 * yaml-core が構造の検証に使うのは、この木から導出した最小の構造のスキーマ（structure パッケージ。architecture/schema.md「構造スキーマの導出」）。
 * 未定義の項目の扱い（UnknownFieldsPolicy）だけは、構造の決まりなので yaml-core に残している。
 *
 * 注意: DefinitionKey / SchemaCatalog の生成・検索と FieldDef.definitionScope は
 * スキーマ層（SchemaLoader / SchemaDef）専用のAPI。モジュール分割のため internal から
 * public へ広げているが、一般コードから直接使わない。
 */

/**
 * 基底の種類（P33）。スキーマ YAML の `type:` に書ける名前のうち、Kotlin が知るもの。2 種類ある:
 * - 表現の基底（yaml-core の値の形と同じ）: string / int / number / boolean / enum / array / map / object / any
 * - 意味の基底型（値の表現を持つ。直列化は文字列）: id / ref（内部は ID と参照。直列化は word）、date / time / datetime
 *   （内部は LocalDate 系＝ValueSemantics。直列化は正準の形）
 * それ以外の名前（組込みの定義体 `text` / `line` / `word`・利用者の型）は定義の名前で、解決すると型の連鎖
 * （`FieldDef.lineage`）の末端がこの基底の種類になる。見せ方の分岐は型の名前でなくヒント（`FieldDef.multiline` など）で行う。
 */
enum class FieldType(val id: String, val value: ValueSemantics? = null) {
    STRING("string"),   // 文字列（自由。欄は 1 行で始まり改行で伸びる。複数行の保証は組込みの型 text・1 行は line・1 語は word）
    INT("int"),         // 整数
    NUMBER("number"),   // 数値（小数可）
    BOOLEAN("boolean"), // 真偽値
    ENUM("enum"),       // 選択肢（values 必須）
    DATE("date", ValueSemantics.DATE),             // 年月日（YYYY-MM-DD）
    TIME("time", ValueSemantics.TIME),             // 時刻（HH:MM か HH:MM:SS）
    DATETIME("datetime", ValueSemantics.DATETIME), // 日時（YYYY-MM-DD HH:MM。区切りは空白か T）
    ID("id"),           // 論理要素の ID（異なる文書での同一 ID を許容）
    REF("ref"),         // 他要素の ID への参照（リンク化される）
    ARRAY("array"),     // 配列（item 必須）
    MAP("map"),         // 任意キーの連想配列（value 必須）
    OBJECT("object"),   // オブジェクト（fields 必須）
    ANY("any");         // 任意の値（検証しない。メタスキーマ等、単一の型で表せない箇所の明示。R11-02）

    /** 入れ物（object / array / map）。型の連鎖を辿らず定義への参照のまま扱う（再帰のため）のはこの種類だけ。 */
    val isContainer: Boolean get() = this == ARRAY || this == MAP || this == OBJECT

    /** 文字列として直列化される種類（string と、意味の基底型 id / ref / date / time / datetime）。形式（pattern）を書けるのはこれら。 */
    val isTextual: Boolean get() = this == STRING || this == ID || this == REF || value != null

    companion object {
        fun of(s: String?): FieldType = entries.firstOrNull { it.id == s } ?: STRING

        /** 基底の種類の名前か（`type:` に書かれた名前が、定義の名前でなく基底の種類か）。 */
        fun isBaseKind(name: String?): Boolean = entries.any { it.id == name }
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
 * int / number の表示の形（`display: grouped`＝桁区切り）。表示のヒントで、値そのもの・検証・CLI や書き出しのデータは変えない
 * （フォームとビューグリッドの見せ方だけ。編集中は元の表記）。
 */
enum class NumberDisplay(val id: String) {
    GROUPED("grouped");

    /** 表示の文字にする。数の表記（符号・整数・小数）でなければそのまま。 */
    fun format(text: String): String = when (this) {
        GROUPED -> PLAIN_NUMBER.matchEntire(text.trim())?.let { m ->
            val (sign, whole, fraction) = m.destructured
            sign + whole.reversed().chunked(3).joinToString(",").reversed() + fraction
        } ?: text
    }

    companion object {
        private val PLAIN_NUMBER = Regex("""([-+]?)(\d+)(\.\d+)?""")

        fun parse(value: String?): NumberDisplay? = value?.let { raw ->
            entries.firstOrNull { it.id == raw } ?: error("int / number の display は grouped（桁区切り）だけです: $raw")
        }
    }
}

/**
 * 文字列の編集の形（`display: multiline`＝複数行の欄）。表示のヒントで、値も検証も変えない。組込みの型 text がこれを持ち、
 * text から作った型や、使う側の項目に書いても引き継がれる。UI は型の名前ではなくこのヒントで複数行の欄を出す。
 */
enum class TextDisplay(val id: String) {
    MULTILINE("multiline");

    companion object {
        fun parse(value: String?): TextDisplay? = value?.let { raw ->
            entries.firstOrNull { it.id == raw } ?: error("string の display は multiline（複数行の欄）だけです: $raw")
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
 * relations は関係レジストリ（R20-02。architecture/graph-query.md「エンティティ型と関係レジストリ」）で、同様に文書には適用されない。
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

/**
 * ワークスペース内の定義モジュールを修飾名で引く中央カタログ。生成・検索はスキーマ層に閉じる。
 * 組込みの型のモジュール（`builtin-types`。BuiltinTypes）は常に含まれ、非修飾の名前は
 * 「自分のモジュール → 組込みの型」の順に引く（P33。利用者が同じ名前を自分のモジュールに置けば、そちらが勝つ）。
 */
class SchemaCatalog private constructor(
    private val definitionsByModule: Map<String, Map<String, FieldDef>>,
) {
    fun find(ref: String, fallbackModuleId: String): Pair<DefinitionKey, FieldDef>? {
        val hash = ref.indexOf('#')
        val name = if (hash >= 0) ref.substring(hash + 1) else ref
        if (name.isBlank()) return null
        if (hash >= 0) {
            val moduleId = ref.substring(0, hash)
            if (moduleId.isBlank()) return null
            return definitionsByModule[moduleId]?.get(name)?.let { DefinitionKey(moduleId, name) to it }
        }
        if (fallbackModuleId.isBlank()) return null
        definitionsByModule[fallbackModuleId]?.get(name)?.let { return DefinitionKey(fallbackModuleId, name) to it }
        // 組込みの型は最後に引く（自分のモジュールの同じ名前が優先）
        return definitionsByModule[BuiltinTypes.MODULE_ID]?.get(name)?.let { DefinitionKey(BuiltinTypes.MODULE_ID, name) to it }
    }

    /** つながっている全モジュールの定義（モジュール → 名前 → 定義。組込みの型のモジュールを含む）。構造のスキーマの導出が、他のモジュールの定義も導出するのに使う。 */
    fun modules(): Map<String, Map<String, FieldDef>> = definitionsByModule

    companion object {
        fun local(moduleId: String, definitions: Map<String, FieldDef>): SchemaCatalog =
            SchemaCatalog(withBuiltinTypes(linkedMapOf(moduleId to definitions)))

        fun linked(modules: Iterable<Pair<String, Map<String, FieldDef>>>): SchemaCatalog {
            val definitions = linkedMapOf<String, Map<String, FieldDef>>()
            modules.forEach { (id, values) -> definitions.putIfAbsent(id, values) }
            return SchemaCatalog(withBuiltinTypes(definitions))
        }

        /** 組込みの型のモジュールを足す（利用者が同じ ID のモジュールを置いていれば、そのまま）。 */
        private fun withBuiltinTypes(modules: LinkedHashMap<String, Map<String, FieldDef>>): Map<String, Map<String, FieldDef>> {
            modules.putIfAbsent(BuiltinTypes.MODULE_ID, BuiltinTypes.definitions)
            return modules
        }
    }
}

/**
 * 項目の表示と編集のためのヒント（P27 段C の C-3b。ROADMAP の SC-05「項目ごとに、意味と表示の区画を分ける」）。
 * 検証・走査（ID と参照）・検査は、ここを読まない——読むのは、フォーム・一覧・AI 生成・ひな型の文書の作成だけ。
 * 例外は label だけ: 指摘の文面のために、導出した構造のスキーマへ表示名として写される（SC-04）。
 * タイトルの役割（role）は、索引のタイトルを決めるので、表示のヒントではなく項目の側（意味）に置く。
 */
data class FieldHints(
    /** 表示ラベル（未指定なら key）。 */
    val label: String? = null,
    /** 項目の説明（フォームに表示）。 */
    val description: String? = null,
    /** ENUM のリッチ選択肢（label / description。R10-03）。 */
    val options: List<EnumOption> = emptyList(),
    /** ARRAY / MAP の一覧表示形式（table / list。未指定は自動判定。R12-06）。 */
    val display: CollectionDisplay? = null,
    /** INT / NUMBER の表示の形（grouped＝桁区切り）。 */
    val numberDisplay: NumberDisplay? = null,
    /** 新規作成時の初期値。 */
    val default: Any? = null,
    /** STRING（とそれを基底にする型）の編集の形（multiline＝複数行の欄）。 */
    val textDisplay: TextDisplay? = null,
    /** 入力欄の案内（値が空のときに薄く出す文字。例 YYYY-MM-DD）。 */
    val placeholder: String? = null,
    /** サンプル値（プレビューのサンプル YAML で、初期値が無いときに使う。例 2026-01-01）。 */
    val sample: Any? = null,
    /** 複数行の欄の行数（欄の高さの目安。display: multiline の型で使う）。 */
    val rows: Int? = null,
    /**
     * 表示の形（値と表示形式の分離。Excel の表示形式）: 数は DecimalFormat のパターン（例 `¥#,##0`）、日付・時刻・日時は
     * DateTimeFormatter のパターン（例 `yyyy年M月d日`）。ファイルと編集中は素の値のままで、見せるときだけこの形に組み立てる。
     */
    val format: String? = null,
) {
    /**
     * 定義（型）への参照の解決: 使う側に書いたラベル・説明・見せ方・案内・サンプルは、定義の側より優先し、書かなければ型のものを引き継ぐ。
     * 選択肢のラベルと初期値の合成は SchemaTree.narrow が行う。
     */
    fun over(target: FieldHints): FieldHints = target.copy(
        label = label ?: target.label,
        description = description ?: target.description,
        display = display ?: target.display,
        numberDisplay = numberDisplay ?: target.numberDisplay,
        textDisplay = textDisplay ?: target.textDisplay,
        placeholder = placeholder ?: target.placeholder,
        sample = sample ?: target.sample,
        rows = rows ?: target.rows,
        format = format ?: target.format,
    )
}

/**
 * スキーマ内の 1 フィールド定義。表示と編集のためのヒントは hints にまとめてある（label などの読み出しは、今までどおり項目から直接できる）。
 */
data class FieldDef(
    val key: String? = null,          // 親オブジェクト内でのキー（ルート・配列要素では null）
    val type: FieldType = FieldType.STRING,
    val required: Boolean = false,
    val requiredFrom: String? = null, // このフェーズ以降で必須（累積的。phases 宣言が前提。required との併用不可）
    val values: List<String> = emptyList(),  // ENUM の選択肢（値のリスト。検証・cases・AI制約はこちらを使う）
    val role: FieldRole? = null,             // 表示役割（title / subtitle。表示解決の上書き。R10-01）
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
    val ref: String? = null,                 // definitions 内の名前付き定義への参照
    val unknownFields: UnknownFieldsPolicy? = null, // object の未定義キー。null は warning
    /** 非修飾 `$ref` の解決元。パーサーが設定し、横断参照した定義の子でも元モジュールを保つ（スキーマ層専用）。 */
    val definitionScope: String? = null,
    /**
     * 型の名前（P33）: `type:` に基底の種類でなく定義の名前（組込みの型 `text`・利用者の型 `common#金額`）を書いたときの、
     * その名前。このとき ref にも同じ名前が入り、解決は `$ref` と同じ（type は解決までの仮の値）。
     */
    val typeName: String? = null,
    /** 型の連鎖から引き継いだ形式（正規表現）の一覧。自分の pattern とあわせて、すべてに一致すること（解決が埋める）。 */
    val inheritedPatterns: List<String> = emptyList(),
    /**
     * REF の参照先 ID の絞り込みのうち、型の連鎖の外側の層（解決が埋める）。各層は OR の一覧で、すべての層に合う ID だけが
     * 許される（型を使う側で idPattern をさらに絞れる＝重ね合わせ）。
     */
    val outerIdPatterns: List<List<String>> = emptyList(),
    /**
     * 型の連鎖（解決で通った定義の修飾名。使う側に書いた名前が先頭。例 `common#予約日`・`builtin-types#text`）。
     * 末端の基底の種類は type。UI などは isA で連鎖を見て判定し、基底の種類の分岐を増やさない。
     */
    val lineage: List<String> = emptyList(),
    /**
     * 値の式を受け取る項目（P31）: 値は短い書き方（`{パス}` を含む文字列）でも完全な書き方（式の object）でもよい。
     * 式が返す値の型が type。どの項目が式かはこの印だけが知り、読み取りは schema.ExpressionFields・評価は semantic.SemanticValues。
     */
    val expression: Boolean = false,
    /** 表示と編集のためのヒント（検証・走査・検査は読まない）。 */
    val hints: FieldHints = FieldHints(),
    /**
     * 同一性（オブジェクト型。P33 第三弾）: この object の要素がどのエンティティ型か（`entity: {type, defines, aspect, owns}`）。
     * 名前付きの型にも、その場の object にも書ける。走査はこれを文書スキーマの `entity:`（ID の形で判別）より優先する。
     */
    val entity: EntityDecl? = null,
    /**
     * 型の検査（オブジェクト型。P33 第三弾）: この object の要素ごとに評価する検査（`constraints`）。型を使う側の object は
     * 型の検査に自分の検査を足す（合成は SchemaTree.narrowOnto）。評価は semantic.SemanticElementChecks。
     */
    val checks: List<ElementRule> = emptyList(),
    /**
     * `type`（または `$ref`）が書かれていたか（パーサが設定する）。型を使う側が同じキーの項目を書き直すとき、
     * 型を省いた（＝定義の型のまま狭める）のか、型を書いた（＝定義の型を狭めた型であること）のかを見分けるために持つ。
     */
    val typeGiven: Boolean = true,
) {
    val label: String? get() = hints.label
    val description: String? get() = hints.description
    val options: List<EnumOption> get() = hints.options
    val display: CollectionDisplay? get() = hints.display
    val default: Any? get() = hints.default

    val displayLabel: String get() = label ?: key ?: "(root)"

    /** 複数行の欄で編集する文字列か（組込みの型 text のヒント。型の連鎖で引き継ぐ）。 */
    val multiline: Boolean get() = hints.textDisplay == TextDisplay.MULTILINE
    /** 入力欄の案内（ヒント → 意味の基底型の既定の順）。 */
    val placeholder: String? get() = hints.placeholder ?: type.value?.placeholder
    /** サンプル値（ヒント → 意味の基底型の既定の順）。 */
    val sample: Any? get() = hints.sample ?: type.value?.sample
    val rows: Int? get() = hints.rows
    /** 表示の形（数・日付・時刻・日時。画面で見せるときだけ使う。ValueFormat.display）。 */
    val format: String? get() = hints.format

    /** 効く形式（正規表現）の一覧: 型の連鎖から引き継いだものと自分の pattern。値はすべてに一致すること。 */
    val patterns: List<String> get() = (inheritedPatterns + listOfNotNull(pattern)).distinct()

    /** REF の参照先 ID の絞り込みの層（外側から順。各層は OR の一覧）。空なら制限なし。 */
    val idPatternLayers: List<List<String>> get() = (outerIdPatterns + listOf(idPatterns)).filter { it.isNotEmpty() }

    /** この項目が、その型（定義の修飾名。例 BuiltinTypes.TEXT）か基底の種類（例 "string"）を連鎖に持つか。解決した項目で使う。 */
    fun isA(name: String): Boolean = name in lineage || name == type.id

    /** enum の選択肢（表示用）。リッチ選択肢（options）がなければ値そのままの選択肢を返す。 */
    fun enumOptions(): List<EnumOption> = options.ifEmpty { values.map { EnumOption(it) } }

    /** 現在値に対応する enum 選択肢（表示用）。 */
    fun optionFor(value: String?): EnumOption? = value?.let { v -> enumOptions().firstOrNull { it.value == v } }
}

/**
 * ref フィールドの参照対象種別（R21-03。architecture/graph-query.md「エンティティ操作」）。既定は entity（論理ID）。
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
