package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.YamlIO

/**
 * 組込みの型（定義体。P33 第一弾）。資源 builtin-types ディレクトリの YAML に `kind: library` の形で書いた定義を、
 * 1 つのモジュール `builtin-types` として読む。利用者の定義（definitions）と同じ書き方・同じ能力で、特権は無い
 * （CORE-CALCULUS §25「組込み定義体の非特権」）。
 * - 型の名前の解決順は、基底の種類 → 自分のモジュール → `モジュール#名前` → 組込みの型（SchemaCatalog）。
 *   利用者が同じ名前の定義を自分のモジュールに置けば、そちらが勝つ。
 * - UI・AI 生成・サンプル値は、組込みの型の名前を知っていてよい（解決した項目の連鎖 `FieldDef.lineage` に
 *   `builtin-types#text` があれば複数行の欄、など）。基底の種類（Kotlin の FieldType）のような分岐は増やさない。
 */
object BuiltinTypes {

    /** 組込みの型のモジュール ID（定義の修飾名 `builtin-types#名前`）。 */
    const val MODULE_ID = "builtin-types"

    /** 複数行の文字列（決まりは string と同じ。編集の欄が複数行になる）。 */
    const val TEXT = "$MODULE_ID#text"

    /** 資源名（読み込みの順序もこの並び）。 */
    val names: List<String> = listOf("text")

    /** 1 件の原文を読む（読み込み失敗はあり得ない前提。失敗時は例外で気付かせる）。 */
    fun text(name: String): String =
        requireNotNull(BuiltinTypes::class.java.getResourceAsStream("/builtin-types/$name.yaml")) {
            "組込みの型が見つかりません: $name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 組込みの型の定義（名前 → 定義。定義元のモジュールは builtin-types）。 */
    val definitions: Map<String, FieldDef> by lazy {
        val result = LinkedHashMap<String, FieldDef>()
        for (name in names) {
            val raw = YamlIO.parseToRaw(text(name)) as? Map<*, *> ?: error("組込みの型のルートはobjectである必要があります: $name")
            require(raw["kind"] == "library") { "組込みの型の文書は kind: library である必要があります: $name" }
            SchemaTreeParser.parseDefinitions(raw["definitions"], MODULE_ID).forEach { (key, def) ->
                require(result.putIfAbsent(key, def) == null) { "組込みの型「$key」が重複しています" }
            }
        }
        result
    }

    /** 修飾名 `builtin-types#名前` を作る。 */
    fun qualified(name: String): String = "$MODULE_ID#$name"
}
