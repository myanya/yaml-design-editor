package dev.toros.yde.yaml

import kotlin.test.Test
import kotlin.test.assertNotNull

/** モジュール骨格のsmoke test（Phase 1）。実装の移動が始まったら本物のテストに置き換わる。 */
class SmokeTest {

    @Test
    fun `SnakeYAML依存が構成されている`() {
        assertNotNull(Class.forName("org.yaml.snakeyaml.Yaml"))
    }
}
