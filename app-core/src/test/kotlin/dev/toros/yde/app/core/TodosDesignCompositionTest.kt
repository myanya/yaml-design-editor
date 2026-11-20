package dev.toros.yde.app.core

import dev.toros.yde.semantic.TermComposition
import dev.toros.yde.semantic.TermComposition.Change
import dev.toros.yde.semantic.TermComposition.Priority
import dev.toros.yde.semantic.TermComposition.Record
import dev.toros.yde.semantic.TermComposition.Source
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.LoadedWorkspace
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 導出記録の宣言 → 候補の合成 → 既存の型解決、を接続する前の確認。設計用の型解決器を作らない。 */
class TodosDesignCompositionTest {
    private val model = (YamlIO.parseToRaw(Files.readString(Path.of("samples/todos-sysdev/design/basic/derived/model.yaml"))) as Map<*, *>)["model"] as Map<*, *>
    private fun rows(map: Map<*, *>, key: String) = (map[key] as List<*>).map { it as Map<*, *> }

    private fun declaration(id: String, field: Map<*, *>): Record {
        val written = rows(field, "declarations").single { it["inherited"] == false }
        @Suppress("UNCHECKED_CAST") val path = written["path"] as YPath
        return Record(id, YamlIO.fromAny(assertNotNull(written["declared"])) as YNode.Map,
            Source(id, DocumentId(assertNotNull(written["document"]).toString()), path))
    }

    private fun shared() = declaration("TYP-common.一行の文", rows(model, "types").single { it["name"] == "common#一行の文" })
    private fun title(): Record {
        val todo = rows(model, "entities").single { it["id"] == "todo" }
        val field = rows(todo, "aspects").flatMap { rows(it, "fields") }.first { it["key"] == "title" }
        return declaration("FLD-todo.title", field)
    }
    private fun change(target: String, value: Any?, id: String, priority: Priority = Priority.INDIVIDUAL, attribute: String = "maxLength") =
        Change(target, listOf(attribute), YamlIO.fromAny(value), priority, Source(id, DocumentId("design/basic/inputs/entities/corrections.yaml")))

    // 合成後の宣言を、既存の読み込み・定義の検査・型の連鎖へ渡す。これはテスト用の接続で、現在の生成にはまだ組み込まない。
    private fun resolve(records: List<Record>): LoadedWorkspace {
        val values = records.associate { it.id to YamlIO.toAny(it.value) }
        val common = mapOf("id" to "common", "kind" to "library", "definitions" to mapOf("一行の文" to values.getValue("TYP-common.一行の文")))
        val fields = records.filter { it.id.startsWith("FLD-") }.map { record ->
            @Suppress("UNCHECKED_CAST") val raw = values.getValue(record.id) as Map<String, Any?>
            raw + mapOf("type" to "common#一行の文")
        }
        val consumer = mapOf("id" to "consumer", "match" to "data/*.yaml", "root" to mapOf("type" to "object", "fields" to fields))
        fun yaml(raw: Any) = YamlIO.dumpPlain(YamlIO.fromAny(raw))
        return WorkspaceReader.load(InMemoryDocumentStore(mapOf(
            ".yde/workspace.yaml" to "ledger: [data]\n",
            ".yde/schemas/common.yaml" to yaml(common),
            ".yde/schemas/consumer.yaml" to yaml(consumer),
        )))
    }

    @Test
    fun `共通型を二百に補正しても 項目に明記した百は残り 未指定の項目だけが二百を継承する`() {
        val common = shared()
        val inherited = title()
        assertTrue("maxLength" !in inherited.value.entries)
        val explicit = inherited.copy(id = "FLD-todo.explicit", value = YamlIO.fromAny(
            (YamlIO.toAny(inherited.value) as Map<*, *>).filterKeys { it != "role" } + mapOf("key" to "explicit", "maxLength" to 100)) as YNode.Map)
        val result = TermComposition.compose(listOf(common, inherited, explicit), emptyList(), listOf(change(common.id, 200, "COR-001")))
        assertEquals(emptyList(), result.issues)
        val ws = resolve(assertNotNull(result.records))
        assertEquals(emptyList(), ws.schemaErrors)
        val tree = ws.schemas.single { it.id == "consumer" }.tree
        assertEquals(200, tree.resolve(tree.root.fields.single { it.key == "title" })!!.maxLength)
        assertEquals(100, tree.resolve(tree.root.fields.single { it.key == "explicit" })!!.maxLength)
        assertEquals(100, YamlIO.toAny(common.value.entries.getValue("maxLength")), "導出記録は補正で書き換えない")
        assertEquals("COR-001", result.decisions.single().selected.single().source.id)
    }

    @Test
    fun `規則の異なる既定値を個別指定で解決してから型を解決する`() {
        val common = shared()
        val changes = listOf(change(common.id, 150, "rule-a", Priority.RULE), change(common.id, 200, "rule-b", Priority.RULE))
        val conflict = TermComposition.compose(listOf(common, title()), emptyList(), changes)
        assertNull(conflict.records)
        val result = TermComposition.compose(listOf(common, title()), emptyList(), changes + change(common.id, 180, "COR-001"))
        val ws = resolve(assertNotNull(result.records))
        assertEquals(emptyList(), ws.schemaErrors)
        val tree = ws.schemas.single { it.id == "consumer" }.tree
        assertEquals(180, tree.resolve(tree.root.fields.single())!!.maxLength)
        assertEquals(3, result.decisions.single().candidates.size)
    }

    @Test
    fun `候補の競合が無くても 型を緩める指定は既存の定義検査で止まる`() {
        val common = shared()
        val field = title()
        val result = TermComposition.compose(listOf(common, field), emptyList(), listOf(change(field.id, 200, "COR-001")))
        assertEquals(emptyList(), result.issues, "候補の合成だけでは型の妥当性を確定しない")
        val ws = resolve(assertNotNull(result.records))
        assertTrue(ws.schemaErrors.any { "maxLength 200" in it && "より緩い" in it }, ws.schemaErrors.toString())
    }
}
