package dev.toros.yde.semantic

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * モジュール骨格のsmoke test（Phase 1）。実装の移動が始まったら本物のテストに置き換わる。
 * SnakeYAML・Pathの非依存（ADR-0002）はコンパイル依存の構成で担保する（実行時クラスパスでは検査できない）。
 */
class SmokeTest {

    @Test
    fun `モジュールがコンパイル・実行できる`() {
        assertTrue(true)
    }
}
