package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.CheckLibrary
import dev.toros.yde.semantic.schema.CheckerRule
import dev.toros.yde.semantic.schema.ConstraintRule
import dev.toros.yde.semantic.schema.ExpandedCheck
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldHints
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.RuleOrigin
import dev.toros.yde.yaml.YamlIO

/**
 * 検査の定義の読み込み（P27 段B-2）: 組込み定義体と利用者の定義（functions / vocabulary）を集め、スキーマに書かれた検査
 * （constraints と語彙）を基本の検査へ展開してパースする。アプリ側のスキーマ読み込み（root の SchemaLoader）と、カーネルの
 * スキーマ読み込み（WorkspaceReader）が、この1つの実装を共有する——以前は root だけが持っていたため、カーネル側の CLI は
 * 定義体で書かれた検査を知らなかった（BACKLOG UX-115・UX-105）。
 */
object CheckCatalog {

    /** 組込み定義体に直接書かれた検査の、出どころの文書名（RuleOrigin.owner）。 */
    const val BUILTIN_OWNER = "(builtin)"

    /** 組込み定義体（同梱の YAML。資源 builtin-definitions）。利用者の定義と同じ仕組みで読み込む。 */
    val builtinLibrary: CheckLibrary by lazy {
        BuiltinDefinitionResources.names.fold(CheckLibrary()) { acc, name ->
            val raw = YamlIO.parseToRaw(BuiltinDefinitionResources.text(name)) as? Map<*, *>
                ?: error("組込み定義体のルートはobjectである必要があります: $name")
            acc + CheckLibrary.parse(raw, "（組込み）$name.yaml")
        }
    }

    /** 組込み定義体に直接書かれた検査の、展開後の姿と由来（CLI `checks` の表示用。展開の透明性 CALC-29）。 */
    val builtinChecks: List<ExpandedCheck> by lazy {
        BuiltinDefinitionResources.names.flatMap { name ->
            val raw = YamlIO.parseToRaw(BuiltinDefinitionResources.text(name)) as? Map<*, *> ?: return@flatMap emptyList()
            if (raw["constraints"] == null) return@flatMap emptyList()
            builtinLibrary.expand(mapOf("constraints" to raw["constraints"]), raw["id"]?.toString() ?: name)
                .map { ExpandedCheck(it.raw, "（組込み）$name.yaml の ${it.origin}", it.index) }
        }
    }

    /**
     * 組込み定義体に直接書かれた検査。特定のスキーマに属さない検査として、ワークスペースのどの文書に定義された要素にも
     * 適用する（エンティティの整合など）。利用者も、kind: library の文書に同じように書ける。
     */
    val builtinRules: List<ConstraintRule> by lazy { rulesOf(builtinChecks, BUILTIN_OWNER).constraints }

    /** 組込み定義体に直接書かれた、専用チェッカーを使う検査。 */
    val builtinCheckers: List<CheckerRule> by lazy { rulesOf(builtinChecks, BUILTIN_OWNER).checkers }

    /**
     * ワークスペースの定義の一式: 組込み定義体に、スキーマ文書（YAML を読んだままの値と、その呼び名）に書かれた定義を足す。
     * 検査の定義と語彙の定義は、どのスキーマ文書に書いてもワークスペース全体で使える。読めなかった文書は onError へ渡して続ける。
     */
    fun libraryOf(sources: List<Pair<String, Map<*, *>>>, onError: (String, Throwable) -> Unit): CheckLibrary {
        var library = builtinLibrary
        for ((name, raw) in sources) {
            runCatching { library += CheckLibrary.parse(raw, name) }.onFailure { onError(name, it) }
        }
        return library
    }

    /** スキーマ1件の検査の一式: 展開後の姿（由来つき）と、それをパースした検査。 */
    class SchemaChecks(
        val expanded: List<ExpandedCheck>,
        val constraints: List<ConstraintRule>,
        val checkers: List<CheckerRule>,
    )

    /**
     * スキーマ1件の検査（constraints に書いた検査と、語彙）を、基本の検査へ展開してパースする。
     * 綴りの誤り・見つからない定義・宣言にない受け取る値は例外（スキーマの読み込みエラー）。
     */
    fun checksOf(library: CheckLibrary, raw: Map<*, *>, schemaId: String): SchemaChecks =
        rulesOf(library.expand(raw, schemaId), schemaId)

    /** 展開後の検査をパースし、出どころ（どの文書の・どの書き方から展開された・何番目の検査か）を付ける。 */
    fun rulesOf(expanded: List<ExpandedCheck>, owner: String): SchemaChecks = SchemaChecks(
        expanded = expanded,
        constraints = expanded.filter { !it.usesChecker }
            .map { ConstraintRule.parse(it.raw, it.origin).copy(origin = RuleOrigin(owner, it.origin, it.index)) },
        checkers = expanded.filter { it.usesChecker }
            .map { CheckerRule.parse(it.raw, it.origin).copy(origin = RuleOrigin(owner, it.origin, it.index)) },
    )

    /**
     * 語彙のキーを、スキーマ文書のメタスキーマの「知っている項目」へ足す——語彙として定義された書き方が、スキーマ文書で
     * 「定義されていない項目」と警告されないようにする（書き方の形の検証までは行わない＝配列であることだけ。OQ-DL-03）。
     * - 語彙が新しい配列を作るとき（例 limits[]）は、その配列を足す（addNewArrays＝スキーマ文書のメタスキーマだけ）
     * - 語彙が既にある配列の要素に効くとき（例 関係の宣言 relations[]）は、語彙が要素から読むキーを、その要素の項目へ足す
     * 足すものが無ければ、渡された root をそのまま返す。
     */
    fun withVocabularyKeys(root: FieldDef, library: CheckLibrary, addNewArrays: Boolean): FieldDef {
        var fields = root.fields
        for (word in library.words) {
            val key = word.location.substringBefore('[')
            val description = "語彙の定義（${word.origin}）が作る書き方。検証の前に基本の検査（constraints）へ展開される"
            val existing = fields.firstOrNull { it.key == key }
            if (existing == null) {
                if (!addNewArrays) continue
                fields = fields + FieldDef(
                    key = key, type = FieldType.ARRAY, item = FieldDef(type = FieldType.ANY),
                    hints = FieldHints(label = "語彙: ${word.word}", description = description),
                )
                continue
            }
            val item = existing.item?.takeIf { it.type == FieldType.OBJECT } ?: continue
            val known = item.fields.mapNotNull { it.key }.toSet()
            val added = word.readKeys.filter { it !in known }.map { readKey ->
                FieldDef(key = readKey, type = FieldType.ANY, hints = FieldHints(label = "語彙: ${word.word}", description = description))
            }
            if (added.isNotEmpty()) {
                fields = fields.map { if (it === existing) existing.copy(item = item.copy(fields = item.fields + added)) else it }
            }
        }
        return if (fields === root.fields) root else root.copy(fields = fields)
    }
}
