package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.scalarText

/** ポートは表示部品の名前。ID索引・業務の参照・座標を持たない。 */
object DiagramPorts {
    /** 構造検査の任意項目は空を未指定と扱うが、明示した接続先を箱への接続に変えてはいけない。 */
    fun emptyNames(term: YNode, target: String): List<YPath> {
        fun walk(node: YNode, path: YPath): List<YPath> {
            val map = (node as? YNode.Map)?.entries ?: return emptyList()
            val keys = if (target == "node") listOf("port") else listOf("sourcePort", "targetPort")
            val own = keys.filter { it in map && scalarText(map[it]) == null }.map { path + it }
            val children = if (target == "node") (map["children"] as? YNode.Seq)?.items.orEmpty() else emptyList()
            return own + children.flatMapIndexed { i, child -> walk(child, path + "children" + i) }
        }
        return walk(term, emptyList())
    }

    fun parts(node: DiagramPart?): List<DiagramPart> =
        if (node == null) emptyList() else listOf(node) + node.children.flatMap(::parts)

    fun duplicates(rendering: DiagramRendering): List<DiagramNotice> =
        parts(rendering.node).filter { it.port != null }.groupBy { it.port!! }.filterValues { it.size > 1 }
            .flatMap { (key, parts) -> parts.flatMap { part ->
                val at = part.path + "port"
                rendering.locations(at).ifEmpty { rendering.definition?.let { listOf(DiagramLocation(it, listOf("build") + at)) }.orEmpty() }
                    .map { DiagramNotice("ポート「$key」がノード内で重複しています", it) }
            } }

    /** 未指定は従来のノード接続。指定した名前が一つに決まるときだけ接続できる。 */
    fun issue(key: String?, node: DiagramPart?): String? = if (key == null) null else when (parts(node).count { it.port == key }) {
        0 -> "接続先のポート「$key」がノードにありません"
        1 -> null
        else -> "接続先のポート「$key」が重複しており、一意に選べません"
    }
}
