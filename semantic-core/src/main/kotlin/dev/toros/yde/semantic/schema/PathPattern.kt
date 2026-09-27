package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath

/** パスのパターンの「任意の番号」（`[*]`）。配列の全ての要素に広がる。 */
object AnyIndex {
    override fun toString(): String = "[*]"
}

/**
 * `[*]`（任意の番号）を含むパスのパターン。YPath の書き方に `[*]` を足したもので、ビューのアタッチ先（`attachments.at`）・
 * 値の式の集計（`comments[*].date`）・改名の影響（ビューのパスの言及）が同じ読み取りを使う。
 * 実装は `[*]` を仮の番号 -1 として parseYPath に通し、あとから AnyIndex へ戻す（リテラルの `[-1]` はデータのパスとして
 * 成立しないため衝突しない）。`[*]` が広がるのは配列だけ（map の任意のキーには広がらない）。
 */
object PathPattern {

    /** パターンの読み取り。`[*]` は AnyIndex。 */
    fun parse(text: String): List<Any> =
        parseYPath(text.replace("[*]", "[-1]")).map { seg -> if (seg == -1) AnyIndex else seg }

    /** `[*]` を含むか。含まなければ、そのまま 1 つの場所のパス。 */
    fun hasWildcard(pattern: List<Any>): Boolean = pattern.any { it === AnyIndex }

    /** 人間可読な文字列（例: comments[*].date）。 */
    fun pretty(pattern: List<Any>): String {
        if (pattern.isEmpty()) return "(root)"
        val sb = StringBuilder()
        for (seg in pattern) {
            when {
                seg === AnyIndex -> sb.append("[*]")
                seg is Int -> sb.append('[').append(seg).append(']')
                else -> {
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(seg)
                }
            }
        }
        return sb.toString()
    }

    /** 具体的なパスがこのパターンに合うか。`[*]` は任意の番号に一致する。 */
    fun matches(pattern: List<Any>, path: YPath): Boolean =
        pattern.size == path.size && pattern.zip(path).all { (p, seg) -> (p === AnyIndex && seg is Int) || p == seg }

    /**
     * パターンに合う場所（木の順）。`[*]` は、その場所にある配列の全ての要素に広がる（配列でなければ 0 件）。
     * `[*]` 以外の区切りは、木に無くてもそのまま場所になる（値が「書かれていない」ことを、呼び出し側が場所ごとに扱えるように）。
     */
    fun expand(root: YNode?, pattern: List<Any>): List<YPath> {
        val out = mutableListOf<YPath>()
        fun walk(node: YNode?, i: Int, prefix: YPath) {
            if (i == pattern.size) {
                out += prefix
                return
            }
            val seg = pattern[i]
            if (seg === AnyIndex) {
                (node as? YNode.Seq)?.items?.forEachIndexed { k, child -> walk(child, i + 1, prefix + k) }
                return
            }
            val child = when {
                node is YNode.Map && seg is String -> node.entries[seg]
                node is YNode.Seq && seg is Int -> node.items.getOrNull(seg)
                else -> null
            }
            walk(child, i + 1, prefix + seg)
        }
        walk(root, 0, emptyList())
        return out
    }
}
