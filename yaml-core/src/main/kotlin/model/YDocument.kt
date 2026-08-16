package model

/**
 * 文書の論理識別子（3層コア移行 Phase 2。計画書§4.1）。
 * ファイルパスそのものではなく、上位層（workspace-core）が与える不透明な値。
 * これによりメモリ上の文書やテストにも同じAPIを使える。
 */
data class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentId は空にできません" }
    }

    override fun toString(): String = value
}

/**
 * 1つのYAML文書（`---` 区切りの複数ドキュメントを含む全体）。
 * documents は出現順。先頭だけを編集対象にするかは利用側の判断とする（現行 EditBuffer と同じ扱い）。
 */
data class YDocument(
    val id: DocumentId,
    val documents: List<YNode>,
)
