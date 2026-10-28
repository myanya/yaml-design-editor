package dev.toros.yde.app.ext

import dev.toros.yde.app.skill.Tables
import dev.toros.yde.semantic.ExtensionViewDef
import dev.toros.yde.workspace.ViewKind
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 拡張の登録簿: 入口が入れた形式だけが skill の format と出力ファイルの拡張子から選べる。
 * 中核には形式が 1 つも無く、登録されていない形式は使える形式を添えた誤りになる。
 */
class ExtensionsTest {

    /** 1 行 1 セルの試験用の形式（本物の形式は拡張モジュールにある）。 */
    private object FakeFormat : FormatCodec {
        override val id = "fake"
        override val extensions = listOf("fk", "fake")
        override fun read(bytes: ByteArray, options: ReadOptions): Table =
            Table(listOf("v"), bytes.decodeStrict(options.encoding).lines().filter { it.isNotBlank() }.map { listOf(it) })
        override fun write(table: Table, options: WriteOptions): ByteArray = table.rows.joinToString("\n") { it[0].toString() }.toByteArray()
    }

    private object ReadOnlyFormat : FormatCodec {
        override val id = "ro"
        override val extensions = listOf("ro")
        override val canWrite: Boolean get() = false
        override fun read(bytes: ByteArray, options: ReadOptions): Table = Table(emptyList(), emptyList())
        override fun write(table: Table, options: WriteOptions): ByteArray = error("書けない")
    }

    @AfterTest
    fun cleanup() {
        Extensions.install(ExtensionRegistry())
    }

    @Test
    fun `登録した形式を id と拡張子で引ける`() {
        Extensions.install(ExtensionRegistry(listOf(FakeFormat, ReadOnlyFormat)))
        assertEquals(FakeFormat, Extensions.format("fake"))
        assertEquals(FakeFormat, Extensions.formatForFile("out/a.FAKE"))
        assertNull(Extensions.formatForFile("a.ro"), "読むだけの形式は拡張子から選ばない")
        assertNull(Extensions.formatForFile("a"))
        assertEquals(listOf("fk", "fake"), Extensions.registry.writableExtensions)
    }

    @Test
    fun `登録の無い形式は使える形式を添えて断る`() {
        Extensions.install(ExtensionRegistry(listOf(FakeFormat)))
        val e = assertFailsWith<IllegalStateException> { Tables.codecFor("xlsx") }
        assertTrue("format「xlsx」は使えません（使える形式: fake）" in e.message.orEmpty(), e.message)
        val w = assertFailsWith<IllegalStateException> { Tables.writerFor("a.xlsx") }
        assertTrue(".fk / .fake" in w.message.orEmpty(), w.message)
        assertFailsWith<IllegalStateException> { ExtensionRegistry(listOf(FakeFormat, FakeFormat)) }
    }

    /** 試験用のビューの種類（ID だけを変える。読むことはしない）。 */
    private class FakeKind(override val id: String) : ViewKind {
        override val label = id
        override val schemaFragment = "kind: { value: $id }\n"
        override fun parse(raw: Map<*, *>, source: DocumentId): ExtensionViewDef = error("読まない")
    }

    @Test
    fun `ビューの種類は二重にも中核の種類とも重ねて登録できない`() {
        Extensions.install(ExtensionRegistry(views = listOf(FakeKind("note"))))
        assertEquals(listOf("note"), Extensions.viewKinds.map { it.id })
        assertFailsWith<IllegalStateException> { ExtensionRegistry(views = listOf(FakeKind("note"), FakeKind("note"))) }
        val e = assertFailsWith<IllegalStateException> { ExtensionRegistry(views = listOf(FakeKind("grid"))) }
        assertTrue("中核の種類と重なります" in e.message.orEmpty(), e.message)
    }
}
