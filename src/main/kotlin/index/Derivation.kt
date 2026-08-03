package index

import model.YNode
import model.YPath
import model.getAt
import model.parseYPath
import model.scalarText
import schema.DerivationDef
import schema.Projection
import java.nio.file.Path

/**
 * 決定論的導出の解決器（R16-02 / R16-03a）。導出宣言（`schema/Derivation.kt`）を索引の出現に対して解決し、
 * `Projection` でテンプレートを評価する。ビューの計算列（core/Views）と整合検証（validate/Validator）の
 * 共有実装（ARCHITECTURE §4.26。ビューで見えている導出値と検証される期待値を常に一致させる）。
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
     * スコープ連鎖は「source 要素 → values で選んだ出現要素 → lookup の起点要素」の近い順（1段評価）。
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
        fun element(entry: IdEntry): YNode? = tree(entry.file)?.let { getAt(it, entry.path) }
        var originOcc: IdEntry? = null
        var origin: YNode? = null
        val subjectId: String? = if (def.lookup == null) startId else {
            val occ = startOccurrences.firstOrNull { def.lookup.from.matches(it.schemaId, rel(it.file)) }
            originOcc = occ
            origin = occ?.let(::element)
            origin?.let { scalarText(getAt(it, def.lookup.via)) }
        }
        val subjectOccs = subjectId?.let { index.entries[it].orEmpty() } ?: emptyList()
        val sourceOcc = subjectOccs.firstOrNull { def.source.matches(it.schemaId, rel(it.file)) }
        val sourceEl = sourceOcc?.let(::element)
        val sourcePath = sourceOcc?.let { it.path + def.path }
        val rawText = sourceEl?.let { scalarText(getAt(it, def.path)) } ?: def.default
            ?: return Result(null, null, true, sourceOcc?.file, sourcePath, emptyList())
        // スコープ連鎖（評価用のノード）と、その要素の出現（来歴の入力座標用）を並走させる
        val scopes = buildList {
            sourceEl?.let { add(it to sourceOcc) }
            def.values.forEach { sel ->
                subjectOccs.firstOrNull { sel.matches(it.schemaId, rel(it.file)) }
                    ?.let { occ -> element(occ)?.let { add(it to occ) } }
            }
            origin?.let { add(it to originOcc) }
        }
        val evaluated = Projection.evaluate(rawText, scopes.map { it.first })
        val inputs = evaluated.resolutions.map { r ->
            val occ = r.scopeIndex?.let { scopes.getOrNull(it)?.second }
            val inputPath = occ?.let { o -> runCatching { o.path + parseYPath(r.placeholder) }.getOrNull() }
            Input(r.placeholder, r.value, occ?.file, inputPath)
        }
        return Result(
            value = Projection.applyMap(evaluated.text, def.map),
            raw = rawText,
            complete = evaluated.complete,
            sourceFile = sourceOcc?.file,
            sourcePath = sourcePath,
            inputs = inputs,
        )
    }
}
