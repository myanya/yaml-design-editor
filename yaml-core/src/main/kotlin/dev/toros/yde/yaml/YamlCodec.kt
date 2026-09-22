package dev.toros.yde.yaml

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument

/**
 * YAMLテキストと YDocument の相互変換（3層コア移行 Phase 2。計画書§4.1）。
 * 公開APIにSnakeYAMLの型を出さない。コメント・表記スタイルは YamlIO の挙動どおり保持する（R8-01）。
 * 構造比較・派生成果物向けの正規化出力は従来どおり `YamlIO.dumpPlain` を別APIとして使う。
 */
interface YamlCodec {
    /** テキストを全ドキュメント（`---` 区切り）ごと読み込む。構文エラーは例外で伝える。 */
    fun parse(id: DocumentId, text: String): YDocument

    /** 全ドキュメントをYAMLテキストへ書き出す（コメント・スタイル込み）。 */
    fun dump(document: YDocument): String
}

/** SnakeYAML（YamlIO）による標準実装。 */
object DefaultYamlCodec : YamlCodec {
    override fun parse(id: DocumentId, text: String): YDocument =
        YDocument(id, YamlIO.parseAll(text))

    override fun dump(document: YDocument): String =
        YamlIO.dumpAll(document.documents)
}
