package dev.toros.yde.app.core

import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.workspace.BuiltinSchemaResources
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 仕様（devtrack/spec/。定義に書ける語）が今の実装と同じであることを CI で守る（devtrack/README.md「仕様」）。
 * 検証のエラーが無いことと、docs/spec/ の参照文書が最新であることは DevtrackTest が見る。ここは実装との一致:
 * - 位置「項目定義」の語と書ける基底の種類は、メタスキーマ（builtin-schemas/schema.yaml の definitions.field）と一致する
 * - 基底の種類は、実装（FieldType）・メタスキーマの type の選択肢と一致する
 * - 位置「スキーマ文書」の語は、スキーマと関係レジストリのメタスキーマの root と一致し、関係レジストリに書ける語も一致する
 * - スキーマの種類は、実装（SchemaKind）・メタスキーマの kind の選択肢と一致する
 * - 語の ID の形の検査が働く。ダッシュボード「仕様」が解決できる
 */
class SpecTest {

    private val root: Path = Path.of("devtrack")

    private fun errorsOf(ws: Workspace): List<String> = ws.files.flatMap { file ->
        val rel = ws.relPath(file)
        ws.validate(rel).filter { it.severity == Severity.ERROR }.map { "$rel ${it.path.pretty()}: ${it.message}" }
    }

    private fun raw(path: Path): Map<*, *> = YamlIO.parseToRaw(path.readText()) as Map<*, *>

    /** 位置 CTX-field の語: キー → 書ける基底の種類（null＝どの種類にも書ける）。 */
    private fun specFieldKeywords(): Map<String, Set<String>?> =
        root.resolve("spec/keywords").listDirectoryEntries("*.yaml").map(::raw).filter { it["context"] == "CTX-field" }.associate { kw ->
            val forms = (kw["forms"] as List<*>).map { (it as Map<*, *>)["applies_to"] as List<*>? }
            (kw["key"] as String) to if (forms.any { it == null }) null else forms.flatMap { it!!.map(Any?::toString) }.toSet()
        }

    /** メタスキーマの項目定義（definitions.field）: キー → 書ける基底の種類（null＝type の cases の外＝どの種類にも書ける）。 */
    private fun metaFieldKeywords(): Map<String, Set<String>?> {
        val field = ((raw("schema")["definitions"] as Map<*, *>)["field"] as Map<*, *>)["fields"] as List<*>
        val common = field.map { (it as Map<*, *>)["key"] as String }.associateWith<String, Set<String>?> { null }
        val byType = metaTypeField()["cases"] as Map<*, *>
        val cased = byType.flatMap { (type, fields) -> (fields as List<*>).map { ((it as Map<*, *>)["key"] as String) to type.toString() } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        return cased + common
    }

    private fun raw(builtin: String): Map<*, *> = YamlIO.parseToRaw(BuiltinSchemaResources.text(builtin)) as Map<*, *>

    private fun metaTypeField(): Map<*, *> =
        ((((raw("schema")["definitions"] as Map<*, *>)["field"] as Map<*, *>)["fields"] as List<*>).single { (it as Map<*, *>)["key"] == "type" }) as Map<*, *>

    @Test
    fun `ダッシュボード「仕様」の区画が解決できる`() {
        val ws = Workspace.open(root)
        val views = Views.load(root, ws.schemas)
        val dashboard = assertNotNull(views.dashboard("spec-dashboard"))
        val resolved = Dashboards.resolve(dashboard, LocalDate.of(2026, 11, 16), views, ws.index, { ws.schemaFor(it) }, ws::treeOf)
        resolved.sections.forEach { s -> assertEquals<String?>(null, s.error, s.definition.id) }
    }

    @Test
    fun `項目定義の語と書ける基底の種類は メタスキーマと同じ`() {
        val spec = specFieldKeywords()
        val meta = metaFieldKeywords()
        assertEquals(meta.keys.sorted(), spec.keys.sorted(), "仕様（devtrack/spec/keywords の CTX-field）とメタスキーマ（definitions.field）で、語が違います")
        val differing = meta.keys.filter { meta[it] != spec[it] }.map { "$it: メタスキーマ ${meta[it] ?: "すべて"}・仕様 ${spec[it] ?: "すべて"}" }
        assertTrue(differing.isEmpty(), "書ける基底の種類が、仕様とメタスキーマで違います:\n" + differing.joinToString("\n"))
    }

    @Test
    fun `基底の種類は 実装とメタスキーマの選択肢と仕様の型と文書で同じ`() {
        val implemented = FieldType.entries.map { it.id }
        assertEquals(implemented, (metaTypeField()["values"] as List<*>).map(Any?::toString), "メタスキーマの type の選択肢")
        val specType = ((raw(root.resolve(".yde/schemas/spec.yaml"))["definitions"] as Map<*, *>)["基底の種類"] as Map<*, *>)["values"] as List<*>
        assertEquals(implemented, specType.map(Any?::toString), "devtrack の型 spec#基底の種類 の選択肢")
        val documented = root.resolve("spec/base-types").listDirectoryEntries("*.yaml").map(::raw)
        assertEquals(implemented.sorted(), documented.map { it["name"].toString() }.sorted(), "devtrack/spec/base-types の文書")
        documented.forEach { assertEquals("BT-${it["name"]}", it["id"], "基底の種類の ID は BT-＋名前") }
    }

    /** メタスキーマの root に並ぶキー。 */
    private fun rootKeys(builtin: String): Set<String> = ((raw(builtin)["root"] as Map<*, *>)["fields"] as List<*>).map { (it as Map<*, *>)["key"] as String }.toSet()

    @Test
    fun `スキーマ文書の語と関係レジストリに書ける語は メタスキーマと同じ`() {
        // 語 → 書けるスキーマの種類（null＝どの種類にも書ける）
        val spec = root.resolve("spec/keywords").listDirectoryEntries("*.yaml").map(::raw).filter { it["context"] == "CTX-schema" }.associate { kw ->
            val forms = (kw["forms"] as List<*>).map { (it as Map<*, *>)["kinds"] as List<*>? }
            (kw["key"] as String) to if (forms.any { it == null }) null else forms.flatMap { it!!.map(Any?::toString) }.toSet()
        }
        val schema = rootKeys("schema")
        val registry = rootKeys("relations")
        assertEquals((schema + registry).sorted(), spec.keys.sorted(), "仕様（devtrack/spec/keywords の CTX-schema）とメタスキーマの root で、語が違います")
        val inRegistry = spec.filterValues { it == null || "relations" in it }.keys
        assertEquals(registry.sorted(), inRegistry.sorted(), "関係レジストリ（kind: relations）に書ける語が、仕様と builtin-schemas/relations.yaml で違います")
        val elsewhere = spec.filterValues { it == null || it.any { k -> k != "relations" } }.keys
        assertEquals(schema.sorted(), elsewhere.sorted(), "関係レジストリ以外に書ける語が、仕様と builtin-schemas/schema.yaml で違います")
    }

    @Test
    fun `スキーマの種類は 実装とメタスキーマの選択肢と仕様の型と文書で同じ`() {
        val implemented = SchemaKind.entries.map { it.id }
        val metaKind = ((raw("schema")["root"] as Map<*, *>)["fields"] as List<*>).single { (it as Map<*, *>)["key"] == "kind" } as Map<*, *>
        assertEquals(implemented, (metaKind["values"] as List<*>).map(Any?::toString), "メタスキーマの kind の選択肢")
        val specType = ((raw(root.resolve(".yde/schemas/spec.yaml"))["definitions"] as Map<*, *>)["スキーマの種類"] as Map<*, *>)["values"] as List<*>
        assertEquals(implemented, specType.map(Any?::toString), "devtrack の型 spec#スキーマの種類 の選択肢")
        val documented = root.resolve("spec/schema-kinds").listDirectoryEntries("*.yaml").map(::raw)
        assertEquals(implemented.sorted(), documented.map { it["name"].toString() }.sorted(), "devtrack/spec/schema-kinds の文書")
        documented.forEach { assertEquals("KIND-${it["name"]}", it["id"], "スキーマの種類の ID は KIND-＋名前") }
    }

    @Test
    fun `語の ID が位置とキーから決まる形と違えば 検査のエラーになる`() {
        val base = createTempDirectory("spec-test")
        try {
            val dir = base.resolve("devtrack")
            Files.walk(root).use { s ->
                s.forEach { p -> val to = dir.resolve(root.relativize(p).toString()); if (Files.isDirectory(p)) Files.createDirectories(to) else Files.copy(p, to) }
            }
            val file = dir.resolve("spec/keywords/KW-field.required.yaml")
            file.writeText(file.readText().replace("key: \"required\"", "key: \"must\""))
            val errors = errorsOf(Workspace.open(dir))
            assertTrue(errors.any { "KW-field.required" in it }, "ID とキーの食い違いを検査が見つけること: $errors")
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
