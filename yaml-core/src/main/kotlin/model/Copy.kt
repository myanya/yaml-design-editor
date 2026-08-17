package model

/** YComments の複製（内容コピー。元と共有しない）。 */
fun YComments.deepCopy(): YComments = YComments(
    before = before.toMutableList(),
    inline = inline,
    after = after.toMutableList(),
)

/**
 * ノードの深い複製（値・子・コメント・表記スタイルを含む。3層コア移行 YM-007b）。
 * copy-on-write 規律（ADR-0003）の「対象文書の複製」と、逆操作が保持する旧ノードの
 * 所有コピーに使う（`inheritMetaFrom` は YComments を共有するため、履歴に入れる
 * ノードは必ずこの複製を通す）。
 */
fun YNode.deepCopy(): YNode {
    val copied: YNode = when (this) {
        is YNode.Scalar -> YNode.Scalar(value).also { it.styleHint = styleHint }
        is YNode.Seq -> YNode.Seq(items.mapTo(mutableListOf()) { it.deepCopy() }).also { it.flowHint = flowHint }
        is YNode.Map -> YNode.Map(
            entries.entries.associateTo(LinkedHashMap()) { (k, v) -> k to v.deepCopy() }
        ).also { it.flowHint = flowHint }
    }
    copied.comments = comments?.deepCopy()
    return copied
}
