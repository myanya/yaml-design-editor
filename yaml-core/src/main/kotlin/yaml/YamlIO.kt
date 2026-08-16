package yaml

import model.YComments
import model.YNode
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.comments.CommentLine
import org.yaml.snakeyaml.comments.CommentType
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.emitter.Emitter
import org.yaml.snakeyaml.nodes.AnchorNode
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import org.yaml.snakeyaml.serializer.Serializer
import java.io.StringReader
import java.io.StringWriter

/**
 * YAML テキスト <-> YNode ツリーの変換。
 *
 * R8-01 で SnakeYAML の Node API（processComments）ベースに差し替え、
 * コメント・キー順・スカラー/フロー表記スタイルを「開く→編集→保存」の往復で保持する。
 *
 * 残る正規化（意図的な制約）:
 * - アンカー／エイリアスは読み込み時に展開される（保存すると実体化する）
 * - インデントはブロックスタイル時 2 に正規化される（リスト記号も親から一段下げる）
 * - ブロック配列の要素の前行コメントは、SnakeYAML エミッタの制約により「- # コメント」の形に
 *   後置される（内容は保持され、以降の往復は安定する）
 */
object YamlIO {

    private fun newLoaderOptions(): LoaderOptions = LoaderOptions().apply {
        isAllowDuplicateKeys = false
        isProcessComments = true
    }

    private fun newDumperOptions(): DumperOptions = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indent = 2
        indicatorIndent = 2
        indentWithIndicator = true
        width = 120
        isAllowUnicode = true
        isProcessComments = true
    }

    /**
     * TIMESTAMP を暗黙解決しない Resolver。引用符なしの日付（例 `2026-08-21`）を Date へ型付けせず、
     * 素の文字列として保持する（型付けすると検証の現在値が `Date.toString()` になり、保存時に
     * ISO 形式へ書き換わってしまうため。UX-29）。読み込み（compose）と書き出し（Serializer）の
     * 両側で同じ解決規則を使うことで、引用符なしの日付が引用符もタグも付かずにそのまま往復する。
     */
    private class PlainDateResolver : Resolver() {
        override fun addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, Resolver.BOOL, "yYnNtTfFoO", 10)
            addImplicitResolver(Tag.INT, Resolver.INT, "-+0123456789")
            addImplicitResolver(Tag.FLOAT, Resolver.FLOAT, "-+0123456789.")
            addImplicitResolver(Tag.MERGE, Resolver.MERGE, "<", 10)
            // 既定の Resolver は NUL 文字も first に含めるが、実データに現れないため省く
            addImplicitResolver(Tag.NULL, Resolver.NULL, "~nN", 10)
            addImplicitResolver(Tag.NULL, Resolver.EMPTY, null, 10)
            // Tag.TIMESTAMP は登録しない: 日付・日時は文字列のまま扱う
            addImplicitResolver(Tag.YAML, Resolver.YAML, "!&*", 10)
        }
    }

    /** compose 用の Yaml（constructor は未使用だが、生成には一式が必要）。 */
    private fun newComposer(): Yaml {
        val loader = newLoaderOptions()
        val dumper = newDumperOptions()
        return Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader, PlainDateResolver())
    }

    /** compose したスカラーノードを load() と同じ規則で型付きの値へ変換する。 */
    private class ScalarValues(options: LoaderOptions) : SafeConstructor(options) {
        fun value(node: ScalarNode): Any? {
            val construct = yamlConstructors[node.tag] ?: return node.value
            return runCatching { construct.construct(node) }.getOrDefault(node.value)
        }
    }

    /** テキストを SnakeYAML の生オブジェクト（Map / List / スカラー）にパースする（コメントなし）。 */
    fun parseToRaw(text: String): Any? {
        val loader = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val dumper = newDumperOptions()
        val v: Any? = Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader, PlainDateResolver()).load(text)
        return v
    }

    /** テキストを YNode ツリーにパースする。空ドキュメントは空マップとして扱う。 */
    fun parse(text: String): YNode {
        val node = newComposer().compose(StringReader(text)) ?: return YNode.Map()
        return convertDocument(node)
    }

    /** `---` 区切りの複数ドキュメントをすべて YNode にパースする（R8-05）。空テキストは空リスト。 */
    fun parseAll(text: String): List<YNode> =
        newComposer().composeAll(StringReader(text)).mapNotNull { it }.map { convertDocument(it) }

    /**
     * 先頭ドキュメントだけを読む寛容なパース（索引・検索など読み取り専用の用途向け）。
     * 書き込みを伴う経路では、後続ドキュメントの黙殺による消失を防ぐため使わないこと。
     */
    fun parseFirst(text: String): YNode? = parseAll(text).firstOrNull()

    private fun convertDocument(node: Node): YNode {
        val values = ScalarValues(newLoaderOptions())
        val converted = fromNode(node, values, HashSet())
        // 文書先頭のコメントと文書末尾のコメントはルートノードに保持する
        readBlockComments(node)?.let { converted.ensureComments().before += it }
        readComments(node.endComments)?.let { converted.ensureComments().after += it }
        return converted
    }

    /** YNode ツリーを YAML テキストにダンプする（コメント・スタイルヒントを再現する）。 */
    fun dump(node: YNode): String = dumpInternal(node, withComments = true)

    /** コメント・表記スタイルヒントを無視した正規化ダンプ（派生成果物・構造比較用）。 */
    fun dumpPlain(node: YNode): String = dumpInternal(node, withComments = false, withStyles = false)

    /** 複数ドキュメントを `---` 区切りの 1 テキストにダンプする（R8-05）。 */
    fun dumpAll(nodes: List<YNode>): String {
        if (nodes.isEmpty()) return ""
        return nodes.joinToString("---\n") { dump(it) }
    }

    private fun dumpInternal(node: YNode, withComments: Boolean, withStyles: Boolean = true): String {
        val opts = newDumperOptions()
        val representer = Representer(opts)
        val root = toNode(node, representer, withComments, withStyles)
        if (withComments) {
            node.comments?.let { c ->
                if (c.before.isNotEmpty()) root.blockComments = commentLines(c.before)
                if (c.after.isNotEmpty()) root.endComments = commentLines(c.after)
                c.inline?.let { root.inLineComments = listOf(inlineComment(it)) }
            }
        }
        val writer = StringWriter()
        val serializer = Serializer(Emitter(writer, opts), PlainDateResolver(), opts, null)
        serializer.open()
        serializer.serialize(root)
        serializer.close()
        return writer.toString()
    }

    // ---------------------------------------------------------------- Node -> YNode

    private fun fromNode(node: Node, values: ScalarValues, visiting: MutableSet<Node>): YNode = when (node) {
        is AnchorNode -> fromNode(node.realNode, values, visiting)
        is ScalarNode -> YNode.Scalar(values.value(node)).also { scalar ->
            // 引用・ブロック表記は保存時に再現する（プレーンはヒントなし）
            if (node.tag == Tag.STR || node.tag == Tag.NULL) scalar.styleHint = styleChar(node.scalarStyle)
        }
        is SequenceNode -> {
            require(visiting.add(node)) { "再帰的なアンカー参照は扱えません" }
            val seq = YNode.Seq(node.value.map { child ->
                fromNode(child, values, visiting).also { attachOwnComments(it, child) }
            }.toMutableList())
            visiting.remove(node)
            // 空のコレクションは記法の選択余地がなく [] と書くしかないため、ヒントを記録しない
            // （要素を追加したときは従来どおりブロック表記へ移行する）
            if (node.flowStyle == DumperOptions.FlowStyle.FLOW && seq.items.isNotEmpty()) seq.flowHint = true
            seq
        }
        is MappingNode -> {
            require(visiting.add(node)) { "再帰的なアンカー参照は扱えません" }
            val map = YNode.Map()
            for (tuple in node.value) {
                val keyNode = unwrap(tuple.keyNode)
                if (keyNode is ScalarNode && keyNode.tag == Tag.MERGE) {
                    // マージキー（<<）は load() と同様に展開する（明示キーが優先）
                    val explicitKeys = node.value.mapNotNull { t ->
                        (unwrap(t.keyNode) as? ScalarNode)?.takeIf { it.tag != Tag.MERGE }?.value
                    }.toSet()
                    for (merged in mergeSources(tuple.valueNode)) {
                        for (mt in merged.value) {
                            val mk = (unwrap(mt.keyNode) as? ScalarNode)?.value ?: continue
                            if (mk in explicitKeys || mk in map.entries) continue
                            map.entries[mk] = fromNode(mt.valueNode, values, visiting)
                        }
                    }
                    continue
                }
                val key = when (keyNode) {
                    is ScalarNode -> values.value(keyNode)?.toString() ?: "null"
                    else -> error("マップのキーはスカラーである必要があります（${keyNode.startMark.line + 1} 行目）")
                }
                if (key in map.entries) {
                    error("キーが重複しています: $key（${keyNode.startMark.line + 1} 行目）")
                }
                val value = fromNode(tuple.valueNode, values, visiting)
                // キー行の前のコメントと、値または「キー:」行末のコメントを値ノードへ保持する
                val before = readBlockComments(tuple.keyNode)
                val inline = readInline(tuple.valueNode) ?: readInline(tuple.keyNode)
                if (before != null || inline != null) {
                    val c = value.ensureComments()
                    before?.let { c.before += it }
                    c.inline = c.inline ?: inline
                }
                map.entries[key] = value
            }
            visiting.remove(node)
            if (node.flowStyle == DumperOptions.FlowStyle.FLOW && map.entries.isNotEmpty()) map.flowHint = true
            map
        }
        else -> YNode.Scalar(null)
    }

    private fun unwrap(node: Node): Node = if (node is AnchorNode) node.realNode else node

    private fun mergeSources(node: Node): List<MappingNode> = when (val n = unwrap(node)) {
        is MappingNode -> listOf(n)
        is SequenceNode -> n.value.map { unwrap(it) }.filterIsInstance<MappingNode>()
        else -> emptyList()
    }

    /** シーケンス要素など、自分自身に付くコメントを YNode へ写す。 */
    private fun attachOwnComments(target: YNode, source: Node) {
        val before = readBlockComments(source)
        val inline = readInline(source)
        if (before != null || inline != null) {
            val c = target.ensureComments()
            before?.let { c.before += it }
            c.inline = c.inline ?: inline
        }
    }

    /** ブロックコメント（前行コメント）を読み取る。null 要素は空行。 */
    private fun readBlockComments(node: Node): List<String?>? = readComments(node.blockComments)

    private fun readComments(lines: List<CommentLine>?): List<String?>? {
        if (lines.isNullOrEmpty()) return null
        return lines.map { if (it.commentType == CommentType.BLANK_LINE) null else it.value }
    }

    private fun readInline(node: Node): String? =
        node.inLineComments?.firstOrNull { it.commentType == CommentType.IN_LINE }?.value

    private fun styleChar(style: DumperOptions.ScalarStyle?): Char? = when (style) {
        DumperOptions.ScalarStyle.DOUBLE_QUOTED -> '"'
        DumperOptions.ScalarStyle.SINGLE_QUOTED -> '\''
        DumperOptions.ScalarStyle.LITERAL -> '|'
        DumperOptions.ScalarStyle.FOLDED -> '>'
        else -> null
    }

    private fun styleOf(hint: Char?): DumperOptions.ScalarStyle? = when (hint) {
        '"' -> DumperOptions.ScalarStyle.DOUBLE_QUOTED
        '\'' -> DumperOptions.ScalarStyle.SINGLE_QUOTED
        '|' -> DumperOptions.ScalarStyle.LITERAL
        '>' -> DumperOptions.ScalarStyle.FOLDED
        else -> null
    }

    // ---------------------------------------------------------------- YNode -> Node

    private fun toNode(node: YNode, representer: Representer, withComments: Boolean, withStyles: Boolean): Node = when (node) {
        is YNode.Scalar -> {
            val represented = representer.represent(node.value)
            val hint = if (withStyles) node.styleHint?.let { styleOf(it) } else null
            // スタイルヒントは文字列（と null）にだけ適用する（数値等に適用すると型が変わるため）
            if (hint != null && represented is ScalarNode && (represented.tag == Tag.STR || represented.tag == Tag.NULL)) {
                ScalarNode(represented.tag, represented.value, null, null, hint)
            } else represented
        }
        is YNode.Seq -> SequenceNode(
            Tag.SEQ,
            node.items.map { child ->
                toNode(child, representer, withComments, withStyles).also { if (withComments) applyOwnComments(child, it) }
            },
            if (withStyles && node.flowHint == true) DumperOptions.FlowStyle.FLOW else DumperOptions.FlowStyle.BLOCK,
        )
        is YNode.Map -> MappingNode(
            Tag.MAP,
            node.entries.map { (k, v) ->
                val keyNode = representer.represent(k)
                val valueNode = toNode(v, representer, withComments, withStyles)
                if (withComments) {
                    v.comments?.let { c ->
                        if (c.before.isNotEmpty()) keyNode.blockComments = commentLines(c.before)
                        c.inline?.let { inline ->
                            // スカラー値は値の行末へ、複合値は「キー:」の行末へ付ける
                            val target = if (valueNode is ScalarNode) valueNode else keyNode
                            target.inLineComments = listOf(inlineComment(inline))
                        }
                    }
                }
                NodeTuple(keyNode, valueNode)
            },
            if (withStyles && node.flowHint == true) DumperOptions.FlowStyle.FLOW else DumperOptions.FlowStyle.BLOCK,
        )
    }

    private fun applyOwnComments(source: YNode, target: Node) {
        source.comments?.let { c ->
            if (c.before.isNotEmpty()) target.blockComments = commentLines(c.before)
            c.inline?.let { target.inLineComments = listOf(inlineComment(it)) }
        }
    }


    private fun commentLines(lines: List<String?>): List<CommentLine> = lines.map { line ->
        if (line == null) CommentLine(null, null, "", CommentType.BLANK_LINE)
        else CommentLine(null, null, line, CommentType.BLOCK)
    }

    private fun inlineComment(text: String): CommentLine = CommentLine(null, null, text, CommentType.IN_LINE)

    // ---------------------------------------------------------------- 生オブジェクト変換

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

    /** YNode を SnakeYAML でダンプ可能な生オブジェクトに変換する（コメント・スタイルは落ちる）。 */
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
