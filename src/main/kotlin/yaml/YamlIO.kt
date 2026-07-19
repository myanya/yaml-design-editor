package yaml

import model.YNode
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

/**
 * YAML テキスト <-> YNode ツリーの変換。
 *
 * 制約:
 * - コメント・アンカー・フロー/ブロックスタイルなどの表層情報は保持しない
 *   （保存時はブロックスタイル・インデント2で、リスト記号も親から一段下げて正規化する）。
 */
object YamlIO {

    private fun newLoader(): Yaml {
        val loader = LoaderOptions().apply { isAllowDuplicateKeys = false }
        return Yaml(loader)
    }

    private fun newDumper(): Yaml {
        val dumper = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            indicatorIndent = 2
            indentWithIndicator = true
            width = 120
            isAllowUnicode = true
        }
        return Yaml(dumper)
    }

    /** テキストを SnakeYAML の生オブジェクト（Map / List / スカラー）にパースする。 */
    fun parseToRaw(text: String): Any? {
        val v: Any? = newLoader().load(text)
        return v
    }

    /** テキストを YNode ツリーにパースする。空ドキュメントは空マップとして扱う。 */
    fun parse(text: String): YNode {
        val raw = parseToRaw(text) ?: LinkedHashMap<String, Any?>()
        return fromAny(raw)
    }

    /** YNode ツリーを YAML テキストにダンプする。 */
    fun dump(node: YNode): String {
        return newDumper().dump(toAny(node))
    }

    /** SnakeYAML の生オブジェクトを YNode に変換する。 */
    fun fromAny(v: Any?): YNode = when (v) {
        is kotlin.collections.Map<*, *> -> {
            val m = LinkedHashMap<String, YNode>()
            for ((k, x) in v) m[k.toString()] = fromAny(x)
            YNode.Map(m)
        }
        is List<*> -> YNode.Seq(v.map { fromAny(it) }.toMutableList())
        else -> YNode.Scalar(v)
    }

    /** YNode を SnakeYAML でダンプ可能な生オブジェクトに変換する。 */
    fun toAny(node: YNode): Any? = when (node) {
        is YNode.Scalar -> node.value
        is YNode.Seq -> node.items.map { toAny(it) }
        is YNode.Map -> {
            val m = LinkedHashMap<String, Any?>()
            for ((k, v) in node.entries) m[k] = toAny(v)
            m
        }
    }
}
