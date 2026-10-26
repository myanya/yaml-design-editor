package dev.toros.yde.semantic

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * 文字のひな形（レポート。R30-04）。値の一式（項）を Markdown などの文字にはめ込む。
 * 書き方は 4 つだけ: 穴 `{パス}`（`{パス:形}` で数や日付の形）、繰り返し `{#each パス}…{/each}`、条件 `{#if パス}…{/if}`、
 * 文字の `{` `}` は `{{` `}}`。コードは書けず、同じ入力からは同じ文字ができる（決定的）。
 * 穴のパスは、いま見ている値（繰り返しの要素）→ その外側 → 根の順に探す。繰り返しの中では `{index}`（0 始まり）と
 * `{position}`（1 始まり）も使える。マップを繰り返すと要素は `{key}` と `{value}`。
 * タグだけの行は行ごと消える（表の行を繰り返しで書けるように）。埋まらなかった穴は空にして、名前を結果に残す。
 */
object TextTemplate {

    /** 組み立てた文字と、埋まらなかった穴（パスの文字。初出順）。 */
    data class Result(val text: String, val missing: List<String>)

    private sealed interface Node {
        data class Text(val text: String) : Node
        data class Hole(val path: String, val format: String?) : Node
        data class Each(val path: String, val body: List<Node>) : Node
        data class If(val path: String, val body: List<Node>) : Node
    }

    private val TAG = Regex("""\{\{|\}\}|\{#each\s+([^}\s]+)\s*\}|\{#if\s+([^}\s]+)\s*\}|\{/each\}|\{/if\}|\{([^{}#/][^{}]*)\}""")
    private val STANDALONE = Regex("""^\s*(\{#each\s+[^}\s]+\s*\}|\{#if\s+[^}\s]+\s*\}|\{/each\}|\{/if\})\s*$""")
    private val NUMBER_SYMBOLS = DecimalFormatSymbols(Locale.US)

    /** ひな形の点検。問題（閉じ忘れ・対応しない閉じ）があればその文、無ければ null。 */
    fun check(template: String): String? = runCatching { parse(template) }.exceptionOrNull()?.message

    /** 値の一式（根の項）でひな形を埋める。 */
    fun render(template: String, values: YNode): Result {
        val missing = LinkedHashSet<String>()
        val out = StringBuilder()
        fun lookup(path: String, scopes: List<YNode>): YNode? {
            if (path == ".") return scopes.firstOrNull()
            val p: YPath = runCatching { parseYPath(path) }.getOrElse { return null }
            for (scope in scopes) getAt(scope, p)?.let { return it }
            return null
        }
        fun loopVars(i: Int): YNode = YNode.Map(LinkedHashMap<String, YNode>().also { it["index"] = YNode.Scalar(i); it["position"] = YNode.Scalar(i + 1) })
        fun emit(nodes: List<Node>, scopes: List<YNode>) {
            for (n in nodes) when (n) {
                is Node.Text -> out.append(n.text)
                is Node.Hole -> {
                    val v = lookup(n.path, scopes)
                    if (v == null) missing += n.path else out.append(text(v, n.format))
                }
                is Node.Each -> when (val v = lookup(n.path, scopes)) {
                    is YNode.Seq -> v.items.forEachIndexed { i, item -> emit(n.body, listOf(item, loopVars(i)) + scopes) }
                    is YNode.Map -> v.entries.entries.forEachIndexed { i, (k, item) ->
                        val entry = YNode.Map(LinkedHashMap<String, YNode>().also { it["key"] = YNode.Scalar(k); it["value"] = item })
                        emit(n.body, listOf(entry, loopVars(i)) + scopes)
                    }
                    null -> missing += n.path
                    else -> Unit
                }
                is Node.If -> if (truthy(lookup(n.path, scopes))) emit(n.body, scopes)
            }
        }
        emit(parse(template), listOf(values))
        return Result(out.toString(), missing.toList())
    }

    /** 穴の文字。配列やマップは YAML の文字。形は数（0 か # を含む DecimalFormat）か日付（y・M・d） */
    private fun text(node: YNode, format: String?): String {
        val raw = scalarText(node) ?: return YamlIO.dumpPlain(node).trimEnd()
        if (format == null) return raw
        return when {
            format.any { it == '0' || it == '#' } ->
                SemanticExpression.numberOf(raw)?.let { n -> runCatching { DecimalFormat(format, NUMBER_SYMBOLS).format(n) }.getOrNull() } ?: raw
            format.any { it == 'y' || it == 'M' || it == 'd' } ->
                SemanticExpression.readDate(raw)?.let { runCatching { SemanticExpression.formatDate(it, format) }.getOrNull() } ?: raw
            else -> raw
        }
    }

    /** 条件の真偽: 無い・空・false・0 件は偽。 */
    private fun truthy(node: YNode?): Boolean = when (node) {
        null -> false
        is YNode.Scalar -> scalarText(node).let { it != null && it.isNotEmpty() && it != "false" }
        is YNode.Seq -> node.items.isNotEmpty()
        is YNode.Map -> node.entries.isNotEmpty()
    }

    /** タグだけの行は、行ごと（改行も）消してタグだけにする。 */
    private fun stripStandaloneLines(template: String): String {
        val lines = template.split("\n")
        val sb = StringBuilder()
        lines.forEachIndexed { i, line ->
            val m = STANDALONE.find(line)
            if (m != null) sb.append(m.groupValues[1])
            else {
                sb.append(line)
                if (i < lines.lastIndex) sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun parse(template: String): List<Node> {
        val src = stripStandaloneLines(template)
        val root = mutableListOf<Node>()
        val stack = ArrayDeque<Triple<String, String, MutableList<Node>>>()
        var current = root
        var last = 0
        for (m in TAG.findAll(src)) {
            if (m.range.first > last) current += Node.Text(src.substring(last, m.range.first))
            last = m.range.last + 1
            val tag = m.value
            when {
                tag == "{{" -> current += Node.Text("{")
                tag == "}}" -> current += Node.Text("}")
                tag.startsWith("{#each") -> { stack.addLast(Triple("each", m.groupValues[1], current)); current = mutableListOf() }
                tag.startsWith("{#if") -> { stack.addLast(Triple("if", m.groupValues[2], current)); current = mutableListOf() }
                tag == "{/each}" || tag == "{/if}" -> {
                    val kind = if (tag == "{/each}") "each" else "if"
                    val (openKind, path, parent) = stack.removeLastOrNull() ?: error("ひな形: $tag に対応する開き（{#$kind …}）がありません")
                    require(openKind == kind) { "ひな形: {#$openKind $path} が {/$openKind} で閉じられていません（$tag があります）" }
                    val body = current
                    current = parent
                    current += if (kind == "each") Node.Each(path, body) else Node.If(path, body)
                }
                else -> {
                    val inner = m.groupValues[3]
                    val colon = inner.indexOf(':')
                    current += if (colon > 0) Node.Hole(inner.substring(0, colon).trim(), inner.substring(colon + 1).trim().ifEmpty { null })
                    else Node.Hole(inner.trim(), null)
                }
            }
        }
        if (last < src.length) current += Node.Text(src.substring(last))
        stack.lastOrNull()?.let { (kind, path, _) -> error("ひな形: {#$kind $path} が閉じられていません（{/$kind} が要ります）") }
        return root
    }
}
