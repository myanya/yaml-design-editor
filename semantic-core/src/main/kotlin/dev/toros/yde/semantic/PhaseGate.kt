package dev.toros.yde.semantic

/**
 * activeFrom（フェーズゲート）の判定（CALC-14 の母集団判定。R29-03）。
 * すべての検証語彙（coverage / consistency / constraints / matrix / flow）で共有する
 * 唯一の実装——従来はカーネル2箇所＋root Validator 4ブロックへ同一論理が複製されていた
 * （Core Calculus Phase 2 の OC-04 を解消）。
 *
 * 規則: activeFrom 未宣言 → 常時判定／phaseOrder に無い・現在フェーズ不明 → 判定する
 * （保守的に「判定する」側へ倒す）／それ以外は現在フェーズが activeFrom 以降のとき判定。
 */
fun phaseActive(activeFrom: String?, phaseOrder: List<String>, currentPhaseIndex: Int?): Boolean =
    activeFrom?.let { from ->
        val fromIdx = phaseOrder.indexOf(from)
        fromIdx < 0 || currentPhaseIndex == null || currentPhaseIndex >= fromIdx
    } ?: true
