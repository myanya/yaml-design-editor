package dev.toros.yde.desktop

import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DiagramPositionsTest {
    private val context = DiagramContext(mapOf("kind" to "flow", "view" to "approval", "document" to "doc/a.yaml"),
        mapOf("direction" to listOf("down"), "types" to listOf("B", "A", "B")))
    private val id = DiagramNodeId("flow", "受付")
    private val pin = DiagramPosition(id, DiagramNodeId("lane", "総務"), 32.5f, 48f, true)

    @Test fun `位置だけを現行形式で往復し条件は順序によらず一致する`() {
        val rootId = DiagramNodeId("entity", "A")
        val occurrence = DiagramNodeId("occurrence", "A", "doc/a.yaml", "items[2]")
        val pins = mapOf(id to pin, rootId to DiagramPosition(rootId, null, -200.25f, -50f, false),
            occurrence to DiagramPosition(occurrence, rootId, 10f, 20f, true))
        val text = DiagramPositionCodec.encode(context, pins)
        assertEquals(pins, DiagramPositionCodec.decode(text, context.copy(selection = context.selection + ("types" to listOf("A", "B")))).positions)
        assertFalse(text.contains("version", ignoreCase = true))
        assertFalse(text.contains("zoom"))
        assertFalse(text.contains("width"))
        assertEquals(text, DiagramPositionCodec.encode(context, pins.entries.reversed().associate { it.toPair() }))
        assertFails { DiagramPositionCodec.decode(text, context.copy(diagram = context.diagram + ("document" to "doc/b.yaml"))) }
    }

    @Test fun `グリッドの省略は既定値で間隔や型が不正な設定は適用しない`() {
        val text = DiagramPositionCodec.encode(context, mapOf(id to pin), DiagramGrid(true, 32))
        assertEquals(DiagramGrid(true, 32), DiagramPositionCodec.decode(text, context).grid)
        val raw = YamlIO.parseToRaw(text) as Map<*, *>
        val omitted = YamlIO.dumpPlain(YamlIO.fromAny(raw.filterKeys { it != "grid" }))
        assertEquals(DiagramGrid(), DiagramPositionCodec.decode(omitted, context).grid)
        assertEquals(mapOf(id to pin), DiagramPositionCodec.decode(omitted, context).positions)
        for (grid in listOf(null, true, mapOf("enabled" to "true", "spacing" to 16),
            mapOf("enabled" to true, "spacing" to 16.5), mapOf("enabled" to true, "spacing" to 0),
            mapOf("enabled" to true, "spacing" to 16, "origin" to 0))) {
            assertFails { DiagramPositionCodec.decode(YamlIO.dumpPlain(YamlIO.fromAny(raw + ("grid" to grid))), context) }
        }
    }

    @Test fun `未対応項目と不正な座標を推測して適用しない`() {
        val text = DiagramPositionCodec.encode(context, mapOf(id to pin))
        assertFails { DiagramPositionCodec.decode("formatVersion: 1\n$text", context) }
        assertFails { DiagramPositionCodec.decode(text.replace("32.5", ".nan"), context) }
        assertFails { DiagramPositionCodec.encode(context, mapOf(id to pin.copy(x = Float.POSITIVE_INFINITY))) }
        assertFails { DiagramPositionCodec.encode(context, mapOf(id to pin.copy(x = 1_000_001f))) }
        assertFails { DiagramPositionCodec.decode(text.replace("dip", "px"), context) }
        assertFails { DiagramPositionCodec.decode(text.replace("positions:", "pins:"), context) }
        assertFails { DiagramPositionCodec.decode(text.replace("pinned: true", "pinned: auto"), context) }
    }

    @Test fun `ワークスペースの複製と再起動で復元でき選択条件ごとに分離する`() {
        val root = createTempDirectory("diagram-record")
        val copy = createTempDirectory("diagram-copy")
        try {
            for (layout in listOf(WorkspaceLayout.LEGACY, WorkspaceLayout(".yde", emptyList()))) {
                val store = DiagramPositionStore(root, layout)
                val first = store.save(context, mapOf(id to pin), null, DiagramGrid(true, 8))
                val other = context.copy(selection = context.selection + ("direction" to listOf("right")))
                assertNotEquals(store.relative(context), store.relative(other))
                assertTrue(store.load(other).positions.isEmpty())
                assertEquals(DiagramGrid(), store.load(other).grid)
                assertEquals(mapOf(id to pin), DiagramPositionStore(root, layout).load(context).positions)
                val target = copy.resolve(store.relative(context))
                Files.createDirectories(target.parent)
                Files.copy(root.resolve(store.relative(context)), target)
                assertEquals(mapOf(id to pin), DiagramPositionStore(copy, layout).load(context).positions)
                assertEquals(DiagramGrid(true, 8), DiagramPositionStore(copy, layout).load(context).grid)
                val second = store.save(context, mapOf(id to pin), first, DiagramGrid(false, 32))
                assertEquals(mapOf(id to pin), store.load(context).positions, "設定変更では位置と固定を変えない")
                assertFails { store.save(context, emptyMap(), first, DiagramGrid()) }
                assertFalse(store.relative(context).contains("views"))
                assertTrue(store.load(context).text == second)
            }
        } finally {
            for (dir in listOf(root, copy)) Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `読めない記録と外部変更を勝手に上書きしない`() {
        val root = createTempDirectory("diagram-conflict")
        try {
            val store = DiagramPositionStore(root, WorkspaceLayout.LEGACY)
            val first = store.save(context, mapOf(id to pin), null)
            val file = root.resolve(store.relative(context))
            val broken = "old-format: true\n"
            Files.writeString(file, broken)
            assertFails { store.save(context, emptyMap(), first) }
            val read = store.load(context)
            assertTrue(read.positions.isEmpty())
            assertNotNull(read.warning)
            assertEquals(broken, Files.readString(file))
            store.save(context, emptyMap(), read.text)
            assertTrue(store.load(context).positions.isEmpty())
            assertNull(store.load(context).warning)
            assertFalse(Files.exists(root.resolve(".yde")), "配置の保存でワークスペースの構成を切り替えない")
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
