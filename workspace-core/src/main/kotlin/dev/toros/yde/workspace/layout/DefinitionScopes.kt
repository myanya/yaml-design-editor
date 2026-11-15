package dev.toros.yde.workspace.layout

import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.yaml.model.YNode

/**
 * 定義体のスコープ 1 つ（`.yde/workspace.yaml` の `scopes:` の 1 件。ADR-0032）。
 * - `include`: どの定義体が入るか。構成ディレクトリ（`.yde`）から見た場所の glob（例 `schemas/business` の下すべて）。
 *   置き場所で書くのは中身を指定する書き方の 1 つで、スコープそのものは名前のある定義
 * - `uses`: このスコープの定義体が使ってよい、別のスコープ（使う向きは一方向。書かれていないスコープの定義体を使うと誤り）
 */
data class DefinitionScope(
    val id: String,
    val name: String,
    val description: String?,
    val include: List<String>,
    val uses: List<String>,
) {
    private val patterns: List<Regex> by lazy { include.map { globToRegex(it) } }

    /** 構成ディレクトリから見た場所（`schemas/business/common.yaml`）が、このスコープに入るか。 */
    fun includes(relInConfig: String): Boolean = patterns.any { it.matches(relInConfig) }
}

/**
 * ワークスペースのスコープの宣言（`scopes:`）と、宣言の誤り。スコープを書かなければ空で、ワークスペース全体が 1 つのまとまり（従来どおり）。
 * 宣言の誤りは、スキーマの読み込みの誤りと同じところに出る（[dev.toros.yde.workspace.ScopeChecks]）。
 */
data class DefinitionScopes(val scopes: List<DefinitionScope> = emptyList(), val errors: List<String> = emptyList()) {
    fun byId(id: String): DefinitionScope? = scopes.firstOrNull { it.id == id }

    /** 構成ディレクトリから見た場所が入るスコープ（1 つだけが正しい。重なりは ScopeChecks が誤りにする）。 */
    fun scopesOf(relInConfig: String): List<DefinitionScope> = scopes.filter { it.includes(relInConfig) }

    companion object {
        val NONE = DefinitionScopes()

        private val ID = Regex("""[A-Za-z][A-Za-z0-9_-]*""")
        private val KEYS = setOf("id", "name", "description", "include", "uses")

        /** スコープに入れられる定義の置き場（記録の置き場 runs は入れない）。 */
        val ZONES: List<String> = WorkspaceLayout.Zone.entries.filter { it != WorkspaceLayout.Zone.RUNS }.map { it.ydeName }

        /** 構成ファイルのルートから `scopes:` を読む。読めない書き方は誤りに積み、読めたスコープだけを持つ。 */
        fun parse(root: YNode.Map?): DefinitionScopes {
            val raw = root?.entries?.get("scopes") ?: return NONE
            val errors = mutableListOf<String>()
            val items = (raw as? YNode.Seq)?.items ?: run {
                errors += "workspace.yaml の scopes は、スコープの並び（- id: …）で書きます"
                return DefinitionScopes(emptyList(), errors)
            }
            fun texts(node: YNode?): List<String>? = when (node) {
                null -> emptyList()
                is YNode.Scalar -> listOfNotNull(node.value?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                is YNode.Seq -> node.items.map { (it as? YNode.Scalar)?.value?.toString()?.trim() ?: return null }
                else -> null
            }
            val parsed = mutableListOf<DefinitionScope>()
            items.forEachIndexed { i, item ->
                val where = "workspace.yaml の scopes[$i]"
                val m = item as? YNode.Map ?: run { errors += "$where は object（id・name・include・uses）で書きます"; return@forEachIndexed }
                m.entries.keys.firstOrNull { it !in KEYS }?.let { errors += "$where: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）" }
                val id = (m.entries["id"] as? YNode.Scalar)?.value?.toString()?.trim()
                if (id == null || !ID.matches(id)) {
                    errors += "$where: id は英字で始まる英数字（- と _ を含めてよい）で書きます: ${id ?: "（無し）"}"
                    return@forEachIndexed
                }
                val include = texts(m.entries["include"])
                if (include == null || include.isEmpty()) {
                    errors += "$where（$id）: include に、入る定義体の場所（例 schemas/business/**）を 1 つ以上書きます"
                    return@forEachIndexed
                }
                val badPatterns = include.filter { p ->
                    val n = p.replace('\\', '/')
                    n.startsWith("/") || ".." in n || n.substringBefore('/') !in ZONES
                }
                if (badPatterns.isNotEmpty()) {
                    errors += "$where（$id）: include は定義の置き場の名前（${ZONES.joinToString("・")}）から書きます（.yde から見た場所。.. は使えません）: ${badPatterns.joinToString(", ")}"
                    return@forEachIndexed
                }
                val uses = texts(m.entries["uses"]) ?: run { errors += "$where（$id）: uses はスコープの id の並びで書きます"; emptyList() }
                parsed += DefinitionScope(
                    id = id,
                    name = (m.entries["name"] as? YNode.Scalar)?.value?.toString()?.takeIf { it.isNotBlank() } ?: id,
                    description = (m.entries["description"] as? YNode.Scalar)?.value?.toString(),
                    include = include.map { it.replace('\\', '/') },
                    uses = uses,
                )
            }
            parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { errors += "workspace.yaml の scopes: スコープ「$it」が重複しています" }
            val ids = parsed.map { it.id }.toSet()
            for (s in parsed) {
                s.uses.filter { it == s.id }.forEach { _ -> errors += "workspace.yaml の scopes（${s.id}）: uses に自分自身は書きません" }
                s.uses.filter { it != s.id && it !in ids }.forEach { errors += "workspace.yaml の scopes（${s.id}）: uses のスコープ「$it」がありません（あるスコープ: ${ids.joinToString(", ")}）" }
            }
            // 使う向きは一方向: たどって自分へ戻る並びは誤り（互いに使うなら、1 つのスコープにまとめる）
            val graph = parsed.associate { it.id to it.uses.filter { u -> u in ids && u != it.id } }
            val reported = mutableSetOf<Set<String>>()
            for (start in graph.keys) {
                fun reach(at: String, seen: List<String>) {
                    for (next in graph[at].orEmpty()) {
                        if (next == start) {
                            val loop = seen + next
                            if (reported.add(loop.toSet())) errors += "workspace.yaml の scopes: uses がたどって戻ります（${loop.joinToString(" → ")}）。使う向きは一方向にします"
                        } else if (next !in seen) reach(next, seen + next)
                    }
                }
                reach(start, listOf(start))
            }
            return DefinitionScopes(parsed.distinctBy { it.id }, errors)
        }
    }
}
