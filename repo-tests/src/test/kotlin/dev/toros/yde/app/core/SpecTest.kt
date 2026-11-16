package dev.toros.yde.app.core

import dev.toros.yde.semantic.schema.FieldType
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
 * 仕様（spec/）を CI で守る（spec/README.md「運用」）:
 * - 検証のエラーが無い。ビュー・ダッシュボード・レポートの定義が読める
 * - docs/spec/ の参照文書が、spec の YAML から作り直したものと同じ（古いままコミットしない）
 * - 仕様が今の実装と同じ: 位置「項目定義」の語と書ける基底の種類はメタスキーマ（builtin-schemas/schema.yaml の definitions.field）と、
 *   基底の種類は実装（FieldType）・メタスキーマの type の選択肢と一致する
 */
class SpecTest {

    private val root: Path = Path.of("spec")

    /** レポート → 書き出す先（cli/build.gradle.kts の specReports と同じ）。 */
    private val reports = mapOf("schema-fields-md" to "docs/spec/schema-fields.md")

    private fun errorsOf(ws: Workspace): List<String> = ws.files.flatMap { file ->
        val rel = ws.relPath(file)
        ws.validate(rel).filter { it.severity == Severity.ERROR }.map { "$rel ${it.path.pretty()}: ${it.message}" }
    }

    private fun raw(path: Path): Map<*, *> = YamlIO.parseToRaw(path.readText()) as Map<*, *>

    /** 位置 CTX-field の語: キー → 書ける基底の種類（null＝どの種類にも書ける）。 */
    private fun specFieldKeywords(): Map<String, Set<String>?> =
        root.resolve("language/keywords").listDirectoryEntries("*.yaml").map(::raw).filter { it["context"] == "CTX-field" }.associate { kw ->
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
    fun `spec は検証のエラーが無く ビューとダッシュボードが解決できる`() {
        val ws = Workspace.open(root)
        assertTrue(ws.schemaErrors.isEmpty(), ws.schemaErrors.toString())
        val errors = errorsOf(ws)
        assertTrue(errors.isEmpty(), "spec の検証のエラー:\n" + errors.joinToString("\n"))
        val views = Views.load(root, ws.schemas)
        assertTrue(views.errors.isEmpty(), views.errors.toString())
        val dashboard = assertNotNull(views.defaultDashboard())
        val resolved = Dashboards.resolve(dashboard, LocalDate.of(2026, 11, 16), views, ws.index, { ws.schemaFor(it) }, ws::treeOf)
        resolved.sections.forEach { s -> assertEquals<String?>(null, s.error, s.definition.id) }
    }

    @Test
    fun `docs の仕様の参照文書は spec から作り直したものと同じ`() {
        val ws = Workspace.open(root)
        val loaded = Reports.load(ws.root)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val catalog = Views.load(ws.root, ws.schemas)
        val stale = reports.filter { (report, target) ->
            val result = Reports.run(assertNotNull(loaded.report(report), report), emptyMap(), catalog, ws.index, ws::treeOf, ws::schemaFor)
            assertTrue(result.missing.isEmpty(), "$report の埋まらなかった穴: ${result.missing}")
            Path.of(target).readText() != result.text
        }.values
        assertTrue(stale.isEmpty(), "spec から作り直していない文書があります: $stale（./gradlew specDocs で作り直してコミットする）")
    }

    @Test
    fun `項目定義の語と書ける基底の種類は メタスキーマと同じ`() {
        val spec = specFieldKeywords()
        val meta = metaFieldKeywords()
        assertEquals(meta.keys.sorted(), spec.keys.sorted(), "仕様（spec/language/keywords の CTX-field）とメタスキーマ（definitions.field）で、語が違います")
        val differing = meta.keys.filter { meta[it] != spec[it] }.map { "$it: メタスキーマ ${meta[it] ?: "すべて"}・仕様 ${spec[it] ?: "すべて"}" }
        assertTrue(differing.isEmpty(), "書ける基底の種類が、仕様とメタスキーマで違います:\n" + differing.joinToString("\n"))
    }

    @Test
    fun `基底の種類は 実装とメタスキーマの選択肢と仕様の型と文書で同じ`() {
        val implemented = FieldType.entries.map { it.id }
        assertEquals(implemented, (metaTypeField()["values"] as List<*>).map(Any?::toString), "メタスキーマの type の選択肢")
        val specType = ((raw(root.resolve(".yde/schemas/common.yaml"))["definitions"] as Map<*, *>)["基底の種類"] as Map<*, *>)["values"] as List<*>
        assertEquals(implemented, specType.map(Any?::toString), "spec の型「基底の種類」の選択肢")
        val documented = root.resolve("language/base-types").listDirectoryEntries("*.yaml").map(::raw)
        assertEquals(implemented.sorted(), documented.map { it["name"].toString() }.sorted(), "spec/language/base-types の文書")
        documented.forEach { assertEquals("BT-${it["name"]}", it["id"], "基底の種類の ID は BT-＋名前") }
    }

    @Test
    fun `語の ID が位置とキーから決まる形と違えば 検査のエラーになる`() {
        val base = createTempDirectory("spec-test")
        try {
            val dir = base.resolve("spec")
            Files.walk(root).use { s ->
                s.forEach { p -> val to = dir.resolve(root.relativize(p).toString()); if (Files.isDirectory(p)) Files.createDirectories(to) else Files.copy(p, to) }
            }
            val file = dir.resolve("language/keywords/KW-field.required.yaml")
            file.writeText(file.readText().replace("key: \"required\"", "key: \"must\""))
            val errors = errorsOf(Workspace.open(dir))
            assertTrue(errors.any { "KW-field.required" in it }, "ID とキーの食い違いを検査が見つけること: $errors")
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
