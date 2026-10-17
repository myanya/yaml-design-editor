package dev.toros.yde.yaml

import dev.toros.yde.yaml.model.YPath
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode

/** テキストの中の範囲（0 始まりの行と列。終わりは排他）。 */
data class SourceSpan(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int) {
    /** 位置（行・列）がこの範囲の中か。 */
    fun contains(line: Int, column: Int): Boolean {
        val afterStart = line > startLine || (line == startLine && column >= startColumn)
        val beforeEnd = line < endLine || (line == endLine && column < endColumn)
        return afterStart && beforeEnd
    }
}

/**
 * ノードの位置の表（文法の事実。先頭ドキュメントだけ）。パス → 値の範囲と、マップのキーの範囲。
 * ソースエディタが「指摘のパス → 行」「カーソルの位置 → パス」に使う。意味（ID・参照・検証）はここに持ち込まない。
 */
class SourcePositions internal constructor(
    val values: Map<YPath, SourceSpan>,
    val keys: Map<YPath, SourceSpan>,
) {
    /** パスの行（キーがあればキーの行、無ければ値の行。無ければ null）。 */
    fun lineOf(path: YPath): Int? = keys[path]?.startLine ?: values[path]?.startLine

    /**
     * 位置を含む最も深いパス。キーの範囲にあればその項目のパス、値の範囲にあればその値のパス（入れ子なら最も深いもの）。
     * どの範囲にも無ければ null。
     */
    fun pathAt(line: Int, column: Int): YPath? {
        keys.entries.firstOrNull { (_, span) -> span.contains(line, column) }?.let { return it.key }
        return values.entries.filter { (_, span) -> span.contains(line, column) }.maxByOrNull { it.key.size }?.key
    }

    val isEmpty: Boolean get() = values.isEmpty() && keys.isEmpty()

    companion object {
        val EMPTY = SourcePositions(emptyMap(), emptyMap())
    }
}

object YamlPositions {
    /** テキストの先頭ドキュメントの位置の表。解析できなければ空の表。 */
    fun of(text: String): SourcePositions {
        val node = runCatching { YamlIO.composeFirstNode(text) }.getOrNull() ?: return SourcePositions.EMPTY
        val values = LinkedHashMap<YPath, SourceSpan>()
        val keys = LinkedHashMap<YPath, SourceSpan>()
        walk(node, emptyList(), values, keys, HashSet())
        return SourcePositions(values, keys)
    }

    private fun spanOf(node: Node): SourceSpan? {
        val s = node.startMark ?: return null
        val e = node.endMark ?: return null
        return SourceSpan(s.line, s.column, e.line, e.column)
    }

    private fun walk(
        node: Node, path: YPath,
        values: MutableMap<YPath, SourceSpan>, keys: MutableMap<YPath, SourceSpan>,
        visiting: MutableSet<Node>,
    ) {
        // エイリアスで同じノードが再帰的に現れても止まる（位置は最初の出現のもの）
        if (!visiting.add(node)) return
        spanOf(node)?.let { values[path] = it }
        when (node) {
            is MappingNode -> for (tuple in node.value) {
                val key = (tuple.keyNode as? ScalarNode)?.value ?: continue
                val childPath = path + key
                spanOf(tuple.keyNode)?.let { keys[childPath] = it }
                walk(tuple.valueNode, childPath, values, keys, visiting)
            }
            is SequenceNode -> node.value.forEachIndexed { i, child -> walk(child, path + i, values, keys, visiting) }
            else -> Unit
        }
        visiting.remove(node)
    }
}
