package semantic

import model.DocumentId
import model.YNode
import model.YPath
import model.getAt
import model.parseYPath
import model.scalarText
import schema.DerivationDef
import schema.Projection

/**
 * 決定論的導出の解決器カーネル（R16-02 / R16-03a。3層コア移行 Phase 6D で index/Derivation.kt から移動）。
 * 導出宣言（schema/Derivation.kt）を出現に対して解決し、`Projection` でテンプレートを評価する。
 * ビューの計算列と整合検証の共有実装（ARCHITECTURE §4.26。見えている導出値と検証される期待値を
 * 常に一致させる）。出現・ツリー・相対パスはすべて注入で受け、ファイルI/Oを行わない。
 * 完了条件（計画書 Phase 6D）: 導出値は入力座標（inputs）と規則座標（sourceDocument / sourcePath）の
 * 来歴を持ち、未解決はプレースホルダ単位で構造化される（Input.value = null・座標なし・complete = false）。
 */
object SemanticDerivation {

    /** プレースホルダ入力1件の来歴: 解決値と入力値の絶対座標（未解決なら座標なし）。 */
    data class Input(
        val placeholder: String,
        val value: String?,
        val document: DocumentId?,
        val path: YPath?,
    )

    /** 導出の解決結果。value=null は元値なし（default もなし）＝導出できない。 */
    data class Result(
        val value: String?,          // 評価＋値辞書適用後の導出値
        val raw: String?,            // 元値（テンプレート。default 使用時はその値）
        val complete: Boolean,       // 全プレースホルダが解決済みか
        val sourceDocument: DocumentId?,  // 元値（導出規則）の座標
        val sourcePath: YPath?,
        val inputs: List<Input>,
    )

    /**
     * 導出を解決する。対象IDは lookup 指定時は「起点の出現の ref を1ホップ辿った先」、省略時は startId 自身。
     * スコープ連鎖は「source 要素 → values で選んだ出現要素 → lookup の起点要素」の近い順（1段評価）。
     * - occurrences: 対象IDの出現の解決（索引。上位層が注入する）
     * - rel: ワークスペース相対パス（selector の fileGlob 判定に使う）
     * - tree: 文書ツリーの読み取り（呼び出し側がキャッシュ・オーバーレイを注入する）
     * - startOccurrences: 起点IDの出現（既定は索引。ローカル出現を重ねたい場合に上書きする）
     */
    /*
     * σ 選択方針（CALC-19・意図的差分 D-17）: Expression 側の出現選択は量化せず、
     * 定義出現優先→索引順の決定的な先勝ちとする——実体は共有評価部 SemanticExpression.select（R29-05）。
     */
    fun resolve(
        def: DerivationDef,
        startId: String,
        occurrences: (String) -> List<ScannedId>,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
        startOccurrences: List<ScannedId> = occurrences(startId),
    ): Result {
        fun element(entry: ScannedId): YNode? = tree(entry.document)?.let { getAt(it, entry.path) }
        var originOcc: ScannedId? = null
        var origin: YNode? = null
        val subjectId: String? = if (def.lookup == null) startId else {
            val occ = SemanticExpression.select(startOccurrences, def.lookup.from, rel)
            originOcc = occ
            origin = occ?.let(::element)
            origin?.let { scalarText(getAt(it, def.lookup.via)) }
        }
        val subjectOccs = subjectId?.let(occurrences) ?: emptyList()
        val sourceOcc = SemanticExpression.select(subjectOccs, def.source, rel)
        val sourceEl = sourceOcc?.let(::element)
        val sourcePath = sourceOcc?.let { it.path + def.path }
        val rawText = sourceEl?.let { scalarText(getAt(it, def.path)) } ?: def.default
            ?: return Result(null, null, true, sourceOcc?.document, sourcePath, emptyList())
        // スコープ連鎖（評価用のノード）と、その要素の出現（来歴の入力座標用）を並走させる
        val scopes = buildList {
            sourceEl?.let { add(it to sourceOcc) }
            def.values.forEach { sel ->
                SemanticExpression.select(subjectOccs, sel, rel)
                    ?.let { occ -> element(occ)?.let { add(it to occ) } }
            }
            origin?.let { add(it to originOcc) }
        }
        val evaluated = Projection.evaluate(rawText, scopes.map { it.first })
        val inputs = evaluated.resolutions.map { r ->
            val occ = r.scopeIndex?.let { scopes.getOrNull(it)?.second }
            val inputPath = occ?.let { o -> runCatching { o.path + parseYPath(r.placeholder) }.getOrNull() }
            Input(r.placeholder, r.value, occ?.document, inputPath)
        }
        return Result(
            value = Projection.applyMap(evaluated.text, def.map),
            raw = rawText,
            complete = evaluated.complete,
            sourceDocument = sourceOcc?.document,
            sourcePath = sourcePath,
            inputs = inputs,
        )
    }
}
