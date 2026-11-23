package dev.toros.yde.desktop.ui.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.form.FormSlots
import dev.toros.yde.desktop.ui.form.LocalFormSlots
import dev.toros.yde.desktop.ui.view.ResolvedViewGrid
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * 個票に当てる文書ビュー（`idPattern`）: 業務詳細（ダッシュボードの一覧から開く個票）は、当たるビューがあれば、
 * 項目のツリーの代わりに、その要素を根にした区画（項目の抜粋と、受け取る値つきの表）を上から重ねて出す。
 */
@OptIn(ExperimentalTestApi::class)
class ElementSheetUiTest {
    private fun workspace(): Path {
        val root = createTempDirectory("element-sheet-ui")
        fun write(rel: String, text: String) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            Files.writeString(file, text.trimIndent() + "\n")
        }
        write("_schemas/catalog.yaml", """
            id: catalog
            match: "catalog.yaml"
            root:
              type: object
              fields:
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, pattern: "^ITM-[0-9]+$" }
                      - { key: name, label: 名前, type: string, role: title }
                      - { key: note, label: メモ, type: string }
                      - key: parts
                        type: array
                        item:
                          type: object
                          fields:
                            - { key: id, type: id, pattern: "^PRT-[0-9]+$" }
                            - { key: label, label: 部品名, type: string, role: title }
        """)
        write("catalog.yaml", """
            items:
              - id: ITM-1
                name: 机
                note: 木製
                parts:
                  - { id: PRT-1, label: 天板 }
                  - { id: PRT-2, label: 脚 }
              - id: ITM-2
                name: 椅子
                parts:
                  - { id: PRT-3, label: 座面 }
        """)
        // その品目の部品だけの表（受け取る値＝品目の ID）
        write("_views/item-parts.yaml", """
            id: item-parts
            name: 品目の部品
            params: [item]
            rows:
              match:
                - node: { id: "ITM-*", bind: i }
                - edge: { contains: true }
                - node: { id: "PRT-*", bind: p }
              rowKey: p
              where:
                - { of: i, path: id, equals: ${'$'}item }
            columns:
              - { label: 部品名, path: label }
        """)
        // 品目の個票: 概要（名前とメモだけ）→ 部品の表
        write("_views/item-sheet.yaml", """
            id: item-sheet
            name: 品目の個票
            kind: document
            idPattern: "ITM-*"
            sections:
              - { label: 概要, fields: { include: [name, note] } }
              - label: 部品の一覧
                grid: { view: item-parts, params: { item: { path: id } } }
        """)
        return root
    }

    private fun cleanup(root: Path) { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }

    private val slots = FormSlots(embeddedGrid = { state, view, rows -> ResolvedViewGrid(state, view, rows) })

    @Test fun `業務詳細は 当たる文書ビューの区画を その要素を根にして重ねて出す`() = runComposeUiTest {
        val root = workspace()
        val state = AppState()
        try {
            state.openWorkspace(root)
            assertEquals("item-sheet", assertNotNull(state.elementViewFor("ITM-1")).first.id)
            assertNull(state.elementViewFor("PRT-1"), "ID の形が合わない要素には当たらない")
            setContent {
                CompositionLocalProvider(LocalFormSlots provides slots) {
                    Box(Modifier.size(1200.dp, 900.dp)) { EntityPage(state, "ITM-1", business = true) }
                }
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("element-sheet").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("品目の個票").assertExists()
            // 区画は定義の順。概要は選んだ項目だけ、表はこの品目の部品だけ
            onNodeWithText("概要").assertExists()
            onNodeWithText("部品の一覧").assertExists()
            onNodeWithText("木製").assertExists()
            // 表の行（ID の欄の表示名と、部品名の列の両方に出る）
            assertTrue(onAllNodesWithText("天板").fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithText("脚").fetchSemanticsNodes().isNotEmpty())
            onAllNodesWithText("座面").assertCountEquals(0)
        } finally {
            cleanup(root)
        }
    }

    @Test fun `業務詳細でない個票と 当たるビューの無い要素は これまでどおり項目のフォームを出す`() = runComposeUiTest {
        val root = workspace()
        val state = AppState()
        try {
            state.openWorkspace(root)
            setContent {
                CompositionLocalProvider(LocalFormSlots provides slots) {
                    Box(Modifier.size(1200.dp, 900.dp)) { EntityPage(state, "ITM-1", business = false) }
                }
            }
            waitForIdle()
            onAllNodesWithTag("element-sheet").assertCountEquals(0)
            onAllNodesWithText("部品の一覧").assertCountEquals(0)
        } finally {
            cleanup(root)
        }
    }
}
