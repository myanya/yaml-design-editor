package schema

/**
 * 定義体の仕組み（意味プリミティブ SP-12。P29 Phase 6 第1段。CORE-CALCULUS §25「検査の書き方の3段」）。
 * - 第1段 基本の書き方: スキーマの `constraints` に1件ずつ書く。プリミティブを直接書く（`match` …）か、
 *   定義を名前で使う（`{use: 定義の名前, with: {渡す値}}`）。
 * - 第2段 関数のような定義（`functions`）: 名前・受け取る値（`params`）・中身（`checks`＝第1段の検査のひな型）。
 *   使われるたびに、渡された値をひな型の `$名前` へ差し込んで、第1段の検査へ展開する。
 * - 第3段 語彙の定義（`vocabulary`）: 短く書くための書き方。どこに書けるか（`in`）と、書かれた内容から
 *   どの定義をどの値で使うか（`uses`）を宣言する。
 * 展開は検証の前に1回だけ、語彙 → 定義の使用 → 基本の検査の順に行い、展開後は利用者が直接書いた検査と
 * 区別なく評価する（CALC-29）。組込み（同梱の YAML）と利用者の定義は、同じこの仕組みを使う（CALC-28）。
 *
 * ここは YAML を読んだままの値（Map / List / スカラー）の上の操作だけを行い、検査の意味（クエリ・判定）は知らない。
 * 差し込みは値の置き換えだけ——ひな型の中に条件分岐・繰り返し・定義からの定義の呼び出しは無い。
 */

/** 関数のような定義1件。checks は第1段の検査のひな型（YAML を読んだままの値）。 */
data class CheckFunction(
    val id: String,
    val params: List<String>,
    val checks: List<Any?>,
    /** 定義が書かれた場所（エラー表示と、展開結果の由来の表示に使う）。 */
    val origin: String,
)

/** 語彙の定義1件。location はスキーマ文書の中の書ける場所（例: `consistency[]`＝スキーマ直下の配列の各要素）。 */
data class VocabularyWord(
    val word: String,
    val location: String,
    val uses: List<VocabularyUse>,
    val origin: String,
    /** 書き方の形（最小）: 必ず書くキー（パス）。書かれていなければ定義エラー。 */
    val requires: List<String> = emptyList(),
    /** 書き方の形（最小）: 廃止したキーと、書かれていたときに示す案内（黙って無視せず、定義エラーにして移行先を示す）。 */
    val removed: Map<String, String> = emptyMap(),
    /** 書き方の形（最小）: 書けるキーの一覧（空 = 点検しない）。宣言すると、それ以外のキーは定義エラー（綴りの誤りを見つける）。 */
    val keys: List<String> = emptyList(),
) {
    /**
     * この語彙が、書かれた要素から読むキー（when / unless と、with の from のパスの先頭）。
     * スキーマ文書の検証が「語彙として意味のあるキー」を知るのに使う（定義されていない項目と警告しないため）。
     */
    val readKeys: Set<String>
        get() {
            fun froms(spec: Any?): List<String> = when (spec) {
                is Map<*, *> -> listOfNotNull(spec["from"]?.toString()) + spec.filterKeys { it.toString() != "from" }.values.flatMap { froms(it) }
                is List<*> -> spec.flatMap { froms(it) }
                else -> emptyList()
            }
            return uses.flatMap { it.whenPaths + it.unlessPaths + it.with.values.flatMap { v -> froms(v) } }
                .map { it.substringBefore('.') }.toSet()
        }
}

/**
 * 語彙から定義の使用への対応1件。whenPaths のキーがすべて書かれているとき（unlessPaths はどれも書かれていないとき）に
 * だけ有効（空の配列・空の object・空文字は「書かれていない」とみなす）。
 * with は「定義へ渡す値」の指定で、値ごとに次のどれかを書く:
 * - そのままの値（スカラー・配列・マップ）
 * - `{from: パス}` 語彙として書かれた内容から読む（`default:` で無いときの値、`map:` で値の読み替え、
 *   `join:` で配列の値を区切り文字でつないだ文字列、`format:` で値があるときだけ `{value}` を埋めた文字列にできる）
 * - `{schema: id}` その語彙が書かれたスキーマのID
 */
data class VocabularyUse(
    val whenPaths: List<String>,
    val unlessPaths: List<String>,
    val function: String,
    val with: Map<String, Any?>,
)

/** 展開後の基本の検査1件（YAML を読んだままの値）と、その由来（どの語彙・どの定義から来たか）。 */
data class ExpandedCheck(val raw: Map<String, Any?>, val origin: String) {
    /** 専用チェッカーを使う検査か（CheckerRule として読む。そうでなければクエリの検査 ConstraintRule）。 */
    val usesChecker: Boolean get() = raw.containsKey("checker")
}

data class CheckLibrary(
    val functions: Map<String, CheckFunction> = emptyMap(),
    val words: List<VocabularyWord> = emptyList(),
) {
    /** 定義の合成。関数の名前と語彙の名前の重複は定義エラー（暗黙の上書きをしない）。 */
    operator fun plus(other: CheckLibrary): CheckLibrary {
        other.functions.keys.firstOrNull { it in functions }?.let { id ->
            error("検査の定義「$id」が重複しています（${functions.getValue(id).origin} と ${other.functions.getValue(id).origin}）")
        }
        other.words.firstOrNull { w -> words.any { it.word == w.word } }?.let { w ->
            error("語彙「${w.word}」が重複しています（${words.first { it.word == w.word }.origin} と ${w.origin}）")
        }
        return CheckLibrary(functions + other.functions, words + other.words)
    }

    /** この定義が作る語彙の、スキーマ直下のキー（例: consistency）。スキーマの読み込みが「知っているキー」の判定に使う。 */
    val topLevelKeys: Set<String> get() = words.map { it.location.substringBefore('[').substringBefore('.') }.toSet()

    /**
     * スキーマ1件の検査を、基本の検査（プリミティブを直接書いた形）の並びへ展開する。
     * 並びは「constraints に書いた順 → 語彙の定義順 → 語彙の中で書いた順」で決定的。
     */
    fun expand(schemaRaw: Map<*, *>, schemaId: String): List<ExpandedCheck> {
        val out = mutableListOf<ExpandedCheck>()
        when (val constraints = schemaRaw["constraints"]) {
            null -> Unit
            is List<*> -> constraints.forEachIndexed { i, entry ->
                val m = entry as? Map<*, *> ?: error("constraints[$i] はobjectである必要があります")
                if (m.containsKey("use")) {
                    m.keys.map { it.toString() }.firstOrNull { it !in setOf("use", "with") }
                        ?.let { error("constraints[$i]: 定義を使う検査に書けるのは use と with だけです: $it") }
                    val args = when (val w = m["with"]) {
                        null -> emptyMap()
                        is Map<*, *> -> w.entries.associate { it.key.toString() to it.value }
                        else -> error("constraints[$i].with はobject（渡す値）である必要があります")
                    }
                    out += call(m["use"].toString(), args, "constraints[$i]")
                } else {
                    out += ExpandedCheck(m.entries.associate { it.key.toString() to it.value }, "constraints[$i]")
                }
            }
            else -> error("constraintsはリストである必要があります")
        }
        for (word in words) {
            for ((entry, at) in entriesAt(schemaRaw, word.location)) {
                word.removed.entries.firstOrNull { isWritten(readPath(entry, it.key)) }
                    ?.let { (key, guide) -> error("$at.$key は廃止されました。$guide") }
                word.requires.firstOrNull { !isWritten(readPath(entry, it)) }
                    ?.let { error("$at には $it が必要です（語彙「${word.word}」の書き方）") }
                if (word.keys.isNotEmpty()) {
                    entry.keys.map { it.toString() }.firstOrNull { it !in word.keys }
                        ?.let { error("$at: 未知のキーです: $it（語彙「${word.word}」に書けるキー: ${word.keys.joinToString(", ")}）") }
                }
                for (use in word.uses) {
                    if (use.whenPaths.any { !isWritten(readPath(entry, it)) }) continue
                    if (use.unlessPaths.any { isWritten(readPath(entry, it)) }) continue
                    val args = use.with.mapValues { (name, spec) -> argument(spec, entry, schemaId, "${word.word} の with.$name") }
                    out += call(use.function, args, at)
                }
            }
        }
        return out
    }

    /** 定義を、渡された値で基本の検査へ展開する。 */
    private fun call(functionId: String, args: Map<String, Any?>, at: String): List<ExpandedCheck> {
        val function = functions[functionId]
            ?: error("$at: 検査の定義「$functionId」が見つかりません（定義: ${functions.keys.sorted().joinToString(", ")}）")
        args.keys.firstOrNull { it !in function.params }
            ?.let { error("$at: 定義「$functionId」に「$it」という受け取る値はありません（params: ${function.params.joinToString(", ")}）") }
        return function.checks.mapIndexed { i, template ->
            val expanded = substitute(template, args) as? Map<*, *>
                ?: error("${function.origin} の checks[$i] はobjectである必要があります")
            ExpandedCheck(expanded.entries.associate { it.key.toString() to it.value }, "$at（定義 $functionId）")
        }
    }

    companion object {
        private val PARAM = Regex("""\$(\$|[A-Za-z][A-Za-z0-9_]*)""")

        /** スキーマ文書（YAML を読んだままの値）から、その文書が持つ定義（functions / vocabulary）を読む。 */
        fun parse(schemaRaw: Map<*, *>, origin: String): CheckLibrary {
            val functions = list(schemaRaw["functions"], "$origin の functions").mapIndexed { i, raw ->
                val at = "$origin の functions[$i]"
                val m = raw as? Map<*, *> ?: error("$at はobjectである必要があります")
                m.keys.map { it.toString() }.firstOrNull { it !in setOf("id", "description", "params", "checks") }
                    ?.let { error("$at: 未知のキーです: $it（使えるキー: id, description, params, checks）") }
                val id = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$at.id（定義の名前）が必要です")
                val params = list(m["params"], "$at.params").map { it.toString() }
                val checks = list(m["checks"], "$at.checks")
                require(checks.isNotEmpty()) { "$at.checks には1件以上の検査が必要です" }
                // ひな型が参照する名前は、受け取る値として宣言されていること（綴りの誤りを読み込み時に見つける）
                referencedParams(checks).firstOrNull { it !in params }
                    ?.let { error("$at: ひな型の「\$$it」は params に宣言されていません") }
                CheckFunction(id, params, checks, "$origin の定義「$id」")
            }
            functions.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()
                ?.let { error("$origin: 検査の定義「$it」が重複しています") }
            val words = list(schemaRaw["vocabulary"], "$origin の vocabulary").mapIndexed { i, raw ->
                val at = "$origin の vocabulary[$i]"
                val m = raw as? Map<*, *> ?: error("$at はobjectである必要があります")
                m.keys.map { it.toString() }.firstOrNull { it !in setOf("word", "description", "in", "uses", "requires", "removed", "keys") }
                    ?.let { error("$at: 未知のキーです: $it（使えるキー: word, description, in, uses, requires, removed, keys）") }
                val word = m["word"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$at.word（語彙の名前）が必要です")
                val location = m["in"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$at.in（書ける場所。例: consistency[]）が必要です")
                val uses = list(m["uses"], "$at.uses").mapIndexed { j, u ->
                    val um = u as? Map<*, *> ?: error("$at.uses[$j] はobjectである必要があります")
                    um.keys.map { it.toString() }.firstOrNull { it !in setOf("when", "unless", "use", "with") }
                        ?.let { error("$at.uses[$j]: 未知のキーです: $it（使えるキー: when, unless, use, with）") }
                    VocabularyUse(
                        whenPaths = paths(um["when"]),
                        unlessPaths = paths(um["unless"]),
                        function = um["use"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$at.uses[$j].use（使う定義の名前）が必要です"),
                        with = when (val w = um["with"]) {
                            null -> emptyMap()
                            is Map<*, *> -> w.entries.associate { it.key.toString() to it.value }
                            else -> error("$at.uses[$j].with はobject（渡す値）である必要があります")
                        },
                    )
                }
                require(uses.isNotEmpty()) { "$at.uses には1件以上の対応が必要です" }
                val removed = when (val r = m["removed"]) {
                    null -> emptyMap()
                    is Map<*, *> -> r.entries.associate { it.key.toString() to it.value.toString() }
                    else -> error("$at.removed はobject（廃止したキー: 案内）である必要があります")
                }
                VocabularyWord(word, location, uses, "$origin の語彙「$word」", paths(m["requires"]), removed, paths(m["keys"]))
            }
            return CheckLibrary(functions.associateBy { it.id }, words)
        }

        /** when / unless の値: パス1つ、またはパスの並び。 */
        private fun paths(raw: Any?): List<String> = when (raw) {
            null -> emptyList()
            is List<*> -> raw.map { it.toString() }
            else -> listOf(raw.toString())
        }

        /** 「書かれている」の判定: 値があり、false・空の配列・空の object・空文字でない。 */
        private fun isWritten(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is List<*> -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            is String -> value.isNotBlank()
            else -> true
        }

        private fun list(raw: Any?, where: String): List<Any?> = when (raw) {
            null -> emptyList()
            is List<*> -> raw
            else -> error("$where は配列である必要があります")
        }

        /** ひな型への差し込み。文字列がちょうど `$名前` なら渡された値そのもの（配列・マップ・null も可）、文中の `$名前` は文字列として埋める。`$$` は `$`。 */
        internal fun substitute(template: Any?, args: Map<String, Any?>): Any? = when (template) {
            is Map<*, *> -> LinkedHashMap<Any?, Any?>().also { out ->
                template.forEach { (k, v) -> out[k] = substitute(v, args) }
            }
            is List<*> -> template.map { substitute(it, args) }
            is String -> {
                val whole = PARAM.matchEntire(template)
                if (whole != null && whole.groupValues[1] != "$") args[whole.groupValues[1]]
                else PARAM.replace(template) { m ->
                    val name = m.groupValues[1]
                    if (name == "$") "$" else args[name]?.toString() ?: ""
                }
            }
            else -> template
        }

        private fun referencedParams(template: Any?): Set<String> = when (template) {
            is Map<*, *> -> template.values.flatMap { referencedParams(it) }.toSet()
            is List<*> -> template.flatMap { referencedParams(it) }.toSet()
            is String -> PARAM.findAll(template).map { it.groupValues[1] }.filter { it != "$" }.toSet()
            else -> emptySet()
        }

        /**
         * 語彙の with の1項目を、書かれた内容から具体的な値にする。マップと配列の中の指定も読む。
         * マップの値がすべて無ければ、マップごと「無い」とする（文書の指定の schemaId も fileGlob も
         * 書かれていなければ、指定そのものが無い、という読み方になる）。
         */
        private fun argument(spec: Any?, entry: Map<*, *>, schemaId: String, where: String): Any? {
            if (spec is List<*>) return spec.map { argument(it, entry, schemaId, where) }
            val m = spec as? Map<*, *> ?: return spec
            return when {
                m.containsKey("from") -> {
                    m.keys.map { it.toString() }.firstOrNull { it !in setOf("from", "default", "map", "join", "format") }
                        ?.let { error("$where: 未知のキーです: $it（使えるキー: from, default, map, join, format）") }
                    val read = readPath(entry, m["from"].toString())
                        // join: 配列の値を、区切り文字でつないだ1つの文字列にする（文面に並べて示すため）
                        ?.let { value -> m["join"]?.let { sep -> (value as? List<*>)?.joinToString(sep.toString()) } ?: value }
                    val mapped = (m["map"] as? Map<*, *>)?.let { dict ->
                        read?.let { value -> dict.entries.firstOrNull { it.key.toString() == value.toString() }?.value ?: value }
                    } ?: read
                    // map つきで値が無いときは、default も読み替えの対象にする（「省略時は equals」のような既定を1か所で書く）
                    val value = mapped ?: m["default"]?.let { d ->
                        (m["map"] as? Map<*, *>)?.entries?.firstOrNull { it.key.toString() == d.toString() }?.value ?: d
                    }
                    // format: 値があるときだけ、ひな型の {value} へ埋めた文字列にする（「（説明）」のように、値があるときだけ添える文言）
                    val format = m["format"]?.toString()
                    if (format != null && value != null) format.replace("{value}", value.toString()) else value
                }
                m.keys.map { it.toString() } == listOf("schema") && m["schema"]?.toString() == "id" -> schemaId
                m.isEmpty() -> spec
                else -> LinkedHashMap<Any?, Any?>().also { out ->
                    m.forEach { (k, v) -> out[k] = argument(v, entry, schemaId, "$where.$k") }
                }.takeUnless { out -> out.values.all { it == null } }
            }
        }

        /** ドット区切りのパスで値を読む（配列の添字は扱わない。語彙の1要素の中の読み出し用）。 */
        private fun readPath(entry: Map<*, *>, path: String): Any? {
            var current: Any? = entry
            for (segment in path.split('.')) {
                current = (current as? Map<*, *>)?.entries?.firstOrNull { it.key.toString() == segment }?.value ?: return null
            }
            return current
        }

        /** 語彙が書ける場所の要素を列挙する。第1段は「スキーマ直下の配列の各要素」（`名前[]`）だけを扱う。 */
        private fun entriesAt(schemaRaw: Map<*, *>, location: String): List<Pair<Map<*, *>, String>> {
            require(location.endsWith("[]") && '.' !in location && location.count { it == '[' } == 1) {
                "語彙の in は、スキーマ直下の配列の各要素（例: consistency[]）で書きます: $location"
            }
            val key = location.removeSuffix("[]")
            return when (val raw = schemaRaw[key]) {
                null -> emptyList()
                is List<*> -> raw.mapIndexed { i, e ->
                    (e as? Map<*, *> ?: error("$key[$i] はobjectである必要があります")) to "$key[$i]"
                }
                else -> error("$key はリストである必要があります")
            }
        }
    }
}
