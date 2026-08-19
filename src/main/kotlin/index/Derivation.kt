package index

import model.YNode
import model.YPath
import schema.DerivationDef
import semantic.SemanticDerivation
import java.nio.file.Path

/**
 * 決定論的導出の解決器（R16-02 / R16-03a）のディスク索引向けシェル。
 * 解決アルゴリズムの本体は semantic-core の SemanticDerivation（3層コア移行 Phase 6D）で、
 * ここは IdIndex / Path 座標をカーネル入力（DocumentId 座標）へ写して委譲し、結果を写し戻す。
 * ビューの計算列（core/Views）と整合検証（validate/Validator）の共有実装であることは従来どおり。
 */
object Derivation {

    /** プレースホルダ入力1件の来歴: 解決値と入力値の絶対座標（未解決なら座標なし）。 */
    data class Input(
        val placeholder: String,
        val value: String?,
        val file: Path?,
        val path: YPath?,
    )

    /** 導出の解決結果。value=null は元値なし（default もなし）＝導出できない。 */
    data class Result(
        val value: String?,          // 評価＋値辞書適用後の導出値
        val raw: String?,            // 元値（テンプレート。default 使用時はその値）
        val complete: Boolean,       // 全プレースホルダが解決済みか
        val sourceFile: Path?,       // 元値（導出規則）の座標
        val sourcePath: YPath?,
        val inputs: List<Input>,
    )

    /**
     * 導出を解決する。対象IDは lookup 指定時は「起点の出現の ref を1ホップ辿った先」、省略時は startId 自身。
     * - rel: ワークスペース相対パス（selector の fileGlob 判定に使う）
     * - tree: 文書ツリーの読み取り（呼び出し側がキャッシュ・オーバーレイを注入する）
     * - startOccurrences: 起点IDの出現（既定は索引。ローカル出現を重ねたい場合に上書きする）
     */
    fun resolve(
        def: DerivationDef,
        startId: String,
        index: IdIndex,
        rel: (Path) -> String,
        tree: (Path) -> YNode?,
        startOccurrences: List<IdEntry> = index.entries[startId].orEmpty(),
    ): Result {
        val result = SemanticDerivation.resolve(
            def = def,
            startId = startId,
            occurrences = { id -> index.entries[id].orEmpty().map { it.toScanned() } },
            rel = { d -> rel(Path.of(d.value)) },
            tree = { d -> tree(Path.of(d.value)) },
            startOccurrences = startOccurrences.map { it.toScanned() },
        )
        return Result(
            value = result.value,
            raw = result.raw,
            complete = result.complete,
            sourceFile = result.sourceDocument?.let { Path.of(it.value) },
            sourcePath = result.sourcePath,
            inputs = result.inputs.map { Input(it.placeholder, it.value, it.document?.let { d -> Path.of(d.value) }, it.path) },
        )
    }
}
