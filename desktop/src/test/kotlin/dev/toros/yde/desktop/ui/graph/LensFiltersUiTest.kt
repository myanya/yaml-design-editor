package dev.toros.yde.desktop.ui.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.toros.yde.desktop.AppState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** レンズの定義の箱の絞り込み（ADR-0027）: 見え方で選ぶと既定の絞り込みが入り、画面で外すと箱が戻る。つながりで残した数も出る。 */
@OptIn(ExperimentalTestApi::class)
class LensFiltersUiTest {
    @Test fun `絞り込みを持つ見え方を選ぶと条件に合う人物だけになり 外すと全員に戻る`() = runComposeUiTest {
        val root = createTempDirectory("lens-filters-ui")
        val state = AppState()
        try {
            val sample = Path.of("samples/diagrams")
            Files.walk(sample).use { paths -> paths.forEach { source ->
                val relative = sample.relativize(source)
                if (!relative.startsWith(Path.of(".yde/state"))) {
                    val target = root.resolve(relative)
                    if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
                }
            } }
            root.resolve(".yde/views/people-active.yaml").writeText(
                """
                id: people-active
                name: 在席の人
                kind: lens
                filters:
                  - id: active-only
                    label: 在席だけ
                    mode: show
                    entityType: person
                    where: {path: data.status, equals: active}
                    default: true
                  - id: hide-away
                    label: 不在を隠す
                    mode: hide
                    entityType: person
                    where: {path: data.status, equals: away}
                    keepIfLinked:
                      via: [friend]
                      where: {path: data.status, equals: active}
                """.trimIndent() + "\n",
            )
            state.openWorkspace(root)
            state.memory.lensAppearanceId = "people-active"
            setContent { Box(Modifier.size(1200.dp, 800.dp)) { GraphLensView(state) } }
            waitUntil(timeoutMillis = 20_000) { state.overlayIndex().graph.entities.values.any { it.entityType == "person" } }
            runOnIdle {
                state.overlayIndex().graph.entities.values.map { it.entityType ?: "（型なし）" }.distinct()
                    .filter { it != "person" }.forEach { state.memory.lensHiddenTypes[it] = true }
            }
            // 蓮（away）が隠れ、結衣 → 葵の線だけが残る
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("2 箱 / 1 辺").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("1 件を隠しています").assertExists()
            onNodeWithText("☑ 在席だけ").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("3 箱 / 3 辺").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("☐ 在席だけ").assertExists()
            assertEquals(false, state.memory.lensFilters["people-active/active-only"], "外したことは画面の覚えに残る")
            // 不在の蓮は、在席の葵・結衣と友人の線でつながるので残る
            onNodeWithText("☐ 不在を隠す").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("0 件を隠し、1 件はつながりで残しています").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("3 箱 / 3 辺").assertExists()
        } finally {
            state.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
