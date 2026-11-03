package dev.toros.yde.desktop.ui.view

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.view.flow.*
import dev.toros.yde.desktop.ui.common.*
import dev.toros.yde.semantic.*
import dev.toros.yde.workspace.DiagramDefinitions
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class FlowLabelLayoutTest {
    private fun sample(): FlowModel {
        val root = Path.of("samples/diagrams")
        fun raw(name: String) = YamlIO.parseToRaw(Files.readString(root.resolve(name))) as Map<*, *>
        val definition = FlowViewKind.parse(raw(".yde/views/er-flow.yaml"), DocumentId(".yde/views/er-flow.yaml"))
        val notations = listOf("er-table", "er-reference").map { name ->
            val file = ".yde/views/$name.yaml"
            DiagramDefinitions.parse(raw(file), DocumentId(file))
        }
        val resolved = definition.resolve(object : ViewContext {
            override val document = DocumentId("data/er.yaml")
            override val notations = notations
            override fun grid(id: String): VirtualGridDef? = null
            override fun rows(grid: VirtualGridDef): List<VirtualRow> = emptyList()
            override fun tree(document: DocumentId) = YamlIO.parse(Files.readString(root.resolve(document.value)))
            override fun schemaId(document: DocumentId) = "er"
        }).resolution
        assertTrue(resolved.diagnostics.isEmpty(), resolved.diagnostics.toString())
        return assertNotNull(resolved.model)
    }

    private fun verify(scene: FlowScene) {
        val a = scene.arrangement
        val drawn = a.edges.associate { route ->
            route.output to scene.edgeVisuals[route.output]?.let { visual ->
                diagramEdgeLabels(visual, route.points.map { Offset(it.x, it.y) }, Offset(route.label.center.x, route.label.center.y))
            }.orEmpty()
        }
        val boxes = a.nodes.values.map { Rect(it.x, it.y, it.right, it.bottom) }
        val ends = drawn.values.flatMap { it.drop(1) }.map { it.bounds.inflate(2f) }
        val central = a.edges.map { Rect(it.label.x, it.label.y, it.label.right, it.label.bottom) }
        a.edges.forEachIndexed { i, route ->
            val label = central[i]
            assertTrue((boxes + ends + central.filterIndexed { j, _ -> j != i }).none { it.overlaps(label) },
                "箱・端ラベル・別の中央ラベルと重ならない: $route")
            val distance = route.points.zipWithNext().minOf { (p, q) ->
                val dx = maxOf(0f, label.left - maxOf(p.x, q.x), minOf(p.x, q.x) - label.right)
                val dy = maxOf(0f, label.top - maxOf(p.y, q.y), minOf(p.y, q.y) - label.bottom)
                hypot(dx, dy)
            }
            assertTrue(distance <= 28.01f, "線の近くに保つ: 距離=$distance $route")
            assertTrue(route.points.zipWithNext().none { (p, q) -> OrthogonalRouting.intersects(
                LensPoint(p.x, p.y), LensPoint(q.x, q.y), LensBox(label.left, label.top, label.width, label.height)) },
                "ラベルの背景で同じ線の折れ点を消さない")
            val text = drawn[route.output]?.firstOrNull()?.text ?: scene.texts.getValue(route.output)
            assertFalse(text.hasVisualOverflow, "文字を切り落とさない")
            assertEquals(text.size.width + if (route.output in scene.edgeVisuals) 4f else 12f, label.width)
            assertEquals(text.size.height + if (route.output in scene.edgeVisuals) 0f else 12f, label.height)
            val bounds = flowBounds(scene)
            assertTrue(bounds.contains(label.topLeft) && bounds.contains(label.bottomRight), "PNGと全体表示にも折り返した領域を含む")
        }
    }

    @Test fun `ERの狭い間隔でも長いラベルを線の近くへ折り返し多重度と重ねない`() = runComposeUiTest {
        val original = sample()
        val styles = original.edges.associate { it.output to it.rendering!!.edge!! }
        lateinit var measurer: TextMeasurer
        lateinit var visuals: Map<YPath, DiagramEdgeVisual>
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                measurer = rememberTextMeasurer()
                visuals = rememberDiagramEdges(styles)
            }
        }
        waitForIdle()
        val colors = diagramColors()
        val bodies = original.nodes.associate { n ->
            val r = n.rendering!!
            n.output to DiagramBody(r, measureDiagram(r.node!!, measurer, colors), emptyMap(), colors)
        }
        val texts = original.edges.associate { e -> e.output to measurer.measure(styles.getValue(e.output).label!!,
            TextStyle(fontSize = 12.sp), constraints = Constraints(maxWidth = 220)) }
        val sizes = bodies.mapValues { FlowSize(it.value.size.width, it.value.size.height) }
        // 報告された配置。中央ラベルが顧客・商品の下へ離れ、注文の多重度と重なっていた。
        val at = mapOf("ER-CUSTOMER" to FlowPoint(965f, 96f), "ER-ORDER" to FlowPoint(520f, 25f),
            "ER-ORDER-LINE" to FlowPoint(38f, 193f), "ER-PRODUCT" to FlowPoint(522f, 357f))
        val pins = original.nodes.associate { it.output to at.getValue(it.key!!) }
        for (kind in listOf("ports", "notation", "plain")) {
            val model = original.copy(edges = original.edges.map { edge -> when (kind) {
                "ports" -> edge
                "notation" -> edge.copy(rendering = edge.rendering!!.copy(edge = edge.rendering!!.edge!!.copy(sourcePort = null, targetPort = null)))
                else -> edge.copy(label = styles.getValue(edge.output).label, rendering = null)
            } })
            val edgeVisuals = if (kind == "plain") emptyMap() else visuals
            val measures = measureFlowLabels(model, texts, edgeVisuals, measurer)
            val labels = measures.mapValues { (_, values) -> values.first().size.let { FlowSize(it.width.toFloat(), it.height.toFloat()) } }
            val base = GraphLayouts.arrangeFlow(model, labels, "right", FlowLayoutMode.GROUPED, sizes, pins)
            fun scene(a: FlowArrangement) = withFlowLabels(FlowScene(model, withFlowPorts(a, model, bodies), texts,
                bodies, edgeVisuals, colors, colors.accent), measures)
            val first = scene(base)
            verify(first)
            assertEquals(base.nodes, first.arrangement.nodes, "ラベルのために箱を動かさない")
            assertEquals(first.arrangement.edges, withFlowLabels(first, measures).arrangement.edges, "再計算で右下へずれない")
            val movedNode = model.nodes.single { it.key == "ER-CUSTOMER" }.output
            val moved = flowDragPreview(base, movedNode, FlowPoint(985f, 76f), model, "right")
            verify(scene(moved))
            if (kind == "ports") {
                val customerEdge = model.edges.single { it.from.key == "ER-ORDER" }.output
                assertTrue(first.edgeVisuals.getValue(customerEdge).labelText!!.lineCount > 1, "顧客との狭い隙間では折り返す")
                val dir = Path.of("desktop/build/reports/flow-labels")
                Files.createDirectories(dir)
                exportFlowPng(dir.resolve("er-flow.png"), first, colors.surface, Density(1.5f))
                for (mode in FlowLayoutMode.entries) for (direction in listOf("right", "down")) {
                    val auto = GraphLayouts.arrangeFlow(model, labels, direction, mode, sizes)
                    try { verify(scene(auto)) } catch (e: AssertionError) {
                        throw AssertionError("自動配置: $mode $direction", e)
                    }
                }
            }
        }
    }

    @Test fun `中央と端が同じ文字でも中央だけを折り返す`() = runComposeUiTest {
        lateinit var measurer: TextMeasurer
        setContent { measurer = rememberTextMeasurer() }
        waitForIdle()
        val text = "同じ長いラベル"
        val before = measurer.measure(text)
        val wrapped = measurer.measure(text, constraints = Constraints(maxWidth = 40))
        val visual = DiagramEdgeVisual(DiagramEdgeStyle(label = text, sourceLabel = text), mapOf(text to before), diagramColors(), wrapped)
        val labels = diagramEdgeLabels(visual, listOf(Offset.Zero, Offset(300f, 0f)))
        assertSame(wrapped, labels[0].text)
        assertSame(before, labels[1].text)
    }
}
