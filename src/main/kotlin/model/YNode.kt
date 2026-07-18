package model

/**
 * YAML ドキュメントの内部表現。
 * SnakeYAML のロード結果（Map / List / スカラー）を編集可能なツリーに変換して保持する。
 * Compose の再コンポーズは OpenDocument.version のインクリメントで駆動するため、
 * ノード自体は単純な可変オブジェクトとする。
 */
sealed class YNode {
    /** スカラー値。value は String / Boolean / Int / Long / Double / java.util.Date / null など。 */
    class Scalar(var value: Any?) : YNode()

    /** シーケンス（YAML の配列）。 */
    class Seq(val items: MutableList<YNode> = mutableListOf()) : YNode()

    /** マッピング（YAML のオブジェクト）。キー順を保持する。 */
    class Map(val entries: LinkedHashMap<String, YNode> = LinkedHashMap()) : YNode()
}

/** ドキュメント内の位置。String はマップのキー、Int はシーケンスのインデックス。 */
typealias YPath = List<Any>

/** パスを人間可読な文字列にする（例: items[2].title）。 */
fun YPath.pretty(): String {
    if (isEmpty()) return "(root)"
    val sb = StringBuilder()
    for (seg in this) {
        when (seg) {
            is Int -> sb.append('[').append(seg).append(']')
            else -> {
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(seg)
            }
        }
    }
    return sb.toString()
}

/**
 * pretty() 形式のパス文字列（例: items[2].title）を YPath に変換する。
 * キー名に `.` `[` `]` を含むパスは表現できない（本形式の制約）。
 */
fun parseYPath(text: String): YPath {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed == "(root)") return emptyList()
    val path = mutableListOf<Any>()
    val key = StringBuilder()
    fun flushKey() {
        if (key.isNotEmpty()) {
            path.add(key.toString())
            key.clear()
        }
    }
    var i = 0
    while (i < trimmed.length) {
        when (trimmed[i]) {
            '.' -> flushKey()
            '[' -> {
                flushKey()
                val end = trimmed.indexOf(']', i)
                require(end > i) { "パスの [ が閉じられていません: $text" }
                val idx = trimmed.substring(i + 1, end).toIntOrNull()
                    ?: throw IllegalArgumentException("配列インデックスが整数ではありません: $text")
                path.add(idx)
                i = end
            }
            ']' -> throw IllegalArgumentException("対応しない ] があります: $text")
            else -> key.append(trimmed[i])
        }
        i++
    }
    flushKey()
    return path
}

/** ルートからパスをたどってノードを取得する。存在しなければ null。 */
fun getAt(root: YNode, path: YPath): YNode? {
    var cur: YNode = root
    for (seg in path) {
        cur = when {
            cur is YNode.Map && seg is String -> cur.entries[seg] ?: return null
            cur is YNode.Seq && seg is Int -> cur.items.getOrNull(seg) ?: return null
            else -> return null
        }
    }
    return cur
}

/** スカラーノードのテキスト表現（非スカラーや空文字は null）。 */
fun scalarText(node: YNode?): String? {
    val s = (node as? YNode.Scalar)?.value?.toString()
    return if (s.isNullOrBlank()) null else s
}
