package dev.toros.yde.app.schema

import dev.toros.yde.app.cli.runCli
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * スキーマ推測（R11-01）の単体テスト。
 * 型推測・参照認識（命名規約の流用）・enum 候補・required 候補と、
 * 「生成ドラフトがメタスキーマ検証（S-04）を通る」ことを検証する。
 */
class SchemaInferenceTest {

    private fun sample(rel: String, yaml: String): SchemaInference.Sample =
        SchemaInference.Sample(rel, YamlIO.parse(yaml.trimIndent()))

    private fun parseDraft(draft: YNode.Map): SchemaDef =
        SchemaLoader.parseSchemaText(YamlIO.dump(draft), Path.of("_schemas/inferred.yaml"))

    private fun fieldOf(schema: SchemaDef, key: String): FieldDef? =
        schema.root.fields.firstOrNull { it.key == key }

    // ---------------------------------------------------------------- 型推測

    @Test
    fun `scalar types are inferred from examples`() {
        // int / number / boolean / date / 長文 text / string を値の実例から推測する
        val longText = "これは長い説明文です。".repeat(10)
        val docs = listOf(
            sample("issues/a.yaml", """
                id: ISS-1
                title: タイトルA
                points: 3
                rate: 0.5
                done: false
                due: 2026-01-10
                body: |
                  $longText
            """),
            sample("issues/b.yaml", """
                id: ISS-2
                title: タイトルB
                points: 5
                rate: 1.5
                done: true
                due: 2026-02-01
                body: 短い行
            """),
        )
        val schema = parseDraft(SchemaInference.infer(docs, "issue", "issues/**/*.yaml"))
        assertEquals(FieldType.ID, fieldOf(schema, "id")?.type)
        assertEquals(FieldType.STRING, fieldOf(schema, "title")?.type)
        assertEquals(FieldType.INT, fieldOf(schema, "points")?.type)
        assertEquals(FieldType.NUMBER, fieldOf(schema, "rate")?.type)
        assertEquals(FieldType.BOOLEAN, fieldOf(schema, "done")?.type)
        assertEquals(FieldType.DATE, fieldOf(schema, "due")?.type)
        // 片方が長文なら text（複数行テキスト）とする
        // 長文は組込みの型 text（型の名前。解決すると基底の種類は string）
        assertEquals("text", fieldOf(schema, "body")?.typeName)
        assertEquals(FieldType.STRING, schema.resolve(fieldOf(schema, "body"))?.type)
    }

    @Test
    fun `required is proposed for keys present in all documents`() {
        val docs = listOf(
            sample("a.yaml", "id: A-1\ntitle: あ\nnote: メモ"),
            sample("b.yaml", "id: A-2\ntitle: い"),
        )
        val schema = parseDraft(SchemaInference.infer(docs, "doc", "*.yaml"))
        assertTrue(fieldOf(schema, "id")!!.required)
        assertTrue(fieldOf(schema, "title")!!.required)   // 全文書に出現
        assertTrue(!fieldOf(schema, "note")!!.required)   // 片方にしかない
    }

    @Test
    fun `single document does not mark fields required except id`() {
        val docs = listOf(sample("a.yaml", "id: A-1\ntitle: あ"))
        val schema = parseDraft(SchemaInference.infer(docs, "doc", "*.yaml"))
        assertTrue(fieldOf(schema, "id")!!.required)
        assertTrue(!fieldOf(schema, "title")!!.required)  // 1件だけの合議では必須にしない
    }

    // ---------------------------------------------------------------- 参照認識（I-02 の命名規約流用）

    @Test
    fun `ref naming conventions are reused from index scanner`() {
        val docs = listOf(
            sample("a.yaml", "id: A-1\nparent_ref: B-1\ndepends_on: [B-2, B-3]"),
            sample("b.yaml", "id: A-2\nparent_ref: B-9\ndepends_on: [B-4]"),
        )
        val schema = parseDraft(SchemaInference.infer(docs, "doc", "*.yaml"))
        assertEquals(FieldType.REF, fieldOf(schema, "parent_ref")?.type)
        val depends = fieldOf(schema, "depends_on")
        assertEquals(FieldType.ARRAY, depends?.type)
        assertEquals(FieldType.REF, depends?.item?.type)
        // DocScanner 側の述語と同じ実体（RefNaming）を共有している
        assertTrue(dev.toros.yde.app.index.DocScanner.isGenericRefKey("parent_ref"))
        assertTrue(dev.toros.yde.app.index.DocScanner.isGenericRefListKey("depends_on"))
    }

    // ---------------------------------------------------------------- enum 候補

    @Test
    fun `small repeating value sets become enum candidates`() {
        val docs = (1..6).map { i ->
            sample("t$i.yaml", "id: T-$i\nstatus: ${if (i % 2 == 0) "open" else "closed"}\nmemo: 自由記述$i")
        }
        val schema = parseDraft(SchemaInference.infer(docs, "task", "*.yaml"))
        val status = fieldOf(schema, "status")
        assertEquals(FieldType.ENUM, status?.type)
        assertEquals(setOf("open", "closed"), status?.values?.toSet())
        // 反復しない自由記述は enum にしない
        assertEquals(FieldType.STRING, fieldOf(schema, "memo")?.type)
    }

    @Test
    fun `three samples with repetition become enum`() {
        // 3文書程度の小規模サンプルでも、反復があれば enum 候補になる（UX-27）
        val docs = listOf(
            sample("t1.yaml", "id: T-1\nstatus: open\nassignee: 佐藤"),
            sample("t2.yaml", "id: T-2\nstatus: done\nassignee: 鈴木"),
            sample("t3.yaml", "id: T-3\nstatus: open\nassignee: 田中"),
        )
        val schema = parseDraft(SchemaInference.infer(docs, "task", "*.yaml"))
        assertEquals(FieldType.ENUM, fieldOf(schema, "status")?.type)
        assertEquals(setOf("open", "done"), fieldOf(schema, "status")?.values?.toSet())
        // 3件がすべて異なる値（反復なし）は enum にしない
        assertEquals(FieldType.STRING, fieldOf(schema, "assignee")?.type)
    }

    @Test
    fun `ID値のフィールドはenumにせず参照として推測する`() {
        // 既存IDが反復すると enum の条件を満たしてしまうが、参照の値集合は開いているため
        // enum にしない。全値がサンプル中の論理IDと一致するなら ref とする（UX-27 追補）
        val docs = listOf(
            sample("t1.yaml", "id: T-1\nparent: E-1\nstatus: open"),
            sample("t2.yaml", "id: T-2\nparent: E-1\nstatus: done"),
            sample("t3.yaml", "id: T-3\nparent: E-2\nstatus: open"),
            sample("epics.yaml", "items:\n  - id: E-1\n  - id: E-2"),
        )
        val schema = parseDraft(SchemaInference.infer(docs, "task", "*.yaml"))
        assertEquals(FieldType.REF, fieldOf(schema, "parent")?.type)
        // ID でない値集合は従来どおり enum
        assertEquals(FieldType.ENUM, fieldOf(schema, "status")?.type)
    }

    @Test
    fun `ID形式の値はサンプル外のIDでもenumにしない`() {
        // 参照先がサンプル集合の外（別スキーマの文書等）にある場合は ID の実体と突合できないため、
        // ID形式（例 USR-001）の値は enum 化だけを抑止して string に留める（UX-27 追補）
        val docs = listOf(
            sample("t1.yaml", "id: T-1\nassignee: USR-001"),
            sample("t2.yaml", "id: T-2\nassignee: USR-001"),
            sample("t3.yaml", "id: T-3\nassignee: USR-002"),
        )
        val schema = parseDraft(SchemaInference.infer(docs, "task", "*.yaml"))
        assertEquals(FieldType.STRING, fieldOf(schema, "assignee")?.type)
    }

    // ---------------------------------------------------------------- 構造の推測

    @Test
    fun `nested objects arrays and mixed structures are inferred`() {
        val docs = listOf(
            sample("a.yaml", """
                id: A-1
                detail:
                  owner: 佐藤
                  size: 1
                steps:
                  - name: 手順1
                  - name: 手順2
                broken: 文字列
            """),
            sample("b.yaml", """
                id: A-2
                detail:
                  owner: 鈴木
                  size: 2
                steps:
                  - name: 手順3
                broken: [配列になっている]
            """),
        )
        val schema = parseDraft(SchemaInference.infer(docs, "doc", "*.yaml"))
        val detail = fieldOf(schema, "detail")
        assertEquals(FieldType.OBJECT, detail?.type)
        assertEquals(FieldType.STRING, detail?.fields?.firstOrNull { it.key == "owner" }?.type)
        assertEquals(FieldType.INT, detail?.fields?.firstOrNull { it.key == "size" }?.type)
        val steps = fieldOf(schema, "steps")
        assertEquals(FieldType.ARRAY, steps?.type)
        assertEquals(FieldType.OBJECT, steps?.item?.type)
        // 構造が揺れるキーは any（検証しない明示）へフォールバックする
        assertEquals(FieldType.ANY, fieldOf(schema, "broken")?.type)
    }

    @Test
    fun `document metadata is excluded from inference`() {
        val docs = listOf(sample("a.yaml", "_document: { id: doc-a, name: 文書A }\nid: A-1"))
        val schema = parseDraft(SchemaInference.infer(docs, "doc", "*.yaml"))
        assertNull(fieldOf(schema, "_document"))
        assertNotNull(fieldOf(schema, "id"))
    }

    // ---------------------------------------------------------------- ドラフトの成立性（推測→仕上げ→適用の入口）

    @Test
    fun `draft passes meta validation and generates sample back`() {
        val docs = listOf(
            sample("issues/a.yaml", "id: ISS-1\ntitle: あ\nstatus: open\nowner_ref: USR-1"),
            sample("issues/b.yaml", "id: ISS-2\ntitle: い\nstatus: open\nowner_ref: USR-2"),
            sample("issues/c.yaml", "id: ISS-3\ntitle: う\nstatus: closed\nowner_ref: USR-1"),
            sample("issues/d.yaml", "id: ISS-4\ntitle: え\nstatus: closed\nowner_ref: USR-2"),
        )
        val draft = SchemaInference.infer(docs, "issue", "issues/**/*.yaml", name = "課題")
        val schema = parseDraft(draft)
        assertEquals("issue", schema.id)
        assertEquals("課題", schema.name)
        assertTrue(schema.matches("issues/x.yaml"))
        // メタスキーマ検証（S-04）を通る形で出力される
        assertEquals(emptyList(), schema.validateDefinition())
        // サンプル生成（S-06）が逆方向の検算として動く
        val sampleTree = schema.generateSample()
        assertTrue(sampleTree is YNode.Map)
        // ドラフトには推測である旨のコメントが付く（R8-01 のコメント保持で保存後も残る）
        val dumped = YamlIO.dump(draft)
        assertTrue("推測したスキーマドラフト" in dumped, dumped)
    }

    @Test
    fun `draft document itself passes builtin meta schema validation`() {
        // 推測ドラフトの文書そのものが、組込みメタスキーマ（R11-02）の検証でエラー0になる
        val docs = listOf(
            sample("a.yaml", "id: A-1\ntitle: あ\ncount: 1"),
            sample("b.yaml", "id: A-2\ntitle: い\ncount: 2"),
        )
        val draft = SchemaInference.infer(docs, "doc", "*.yaml")
        val meta = BuiltinSchemas.all.first { it.id == BuiltinSchemas.META_SCHEMA_ID }
        val issues = Validator.validate(
            YamlIO.parse(YamlIO.dump(draft)), null, DocumentId("_schemas/doc.yaml"), meta, IdIndex(),
        )
        assertEquals(emptyList(), issues.filter { it.severity == Severity.ERROR })
    }

    @Test
    fun `inference without matching documents fails with clear message`() {
        assertFailsWith<IllegalArgumentException> {
            SchemaInference.infer(emptyList(), "doc", "*.yaml")
        }
    }

    @Test
    fun `cli infer-schema previews and applies a draft`() {
        val dir = kotlin.io.path.createTempDirectory("yde-infer")
        try {
            fun write(rel: String, content: String) {
                val f = dir.resolve(rel)
                java.nio.file.Files.createDirectories(f.parent)
                java.nio.file.Files.writeString(f, content + "\n")
            }
            write("issues/a.yaml", "id: ISS-1\ntitle: あ")
            write("issues/b.yaml", "id: ISS-2\ntitle: い")
            // 既定は標準出力へのドラフト表示
            val preview = runCli(arrayOf("infer-schema", dir.toString(), "issues/**/*.yaml"))
            assertTrue("type: id" in preview, preview)
            assertTrue(!java.nio.file.Files.exists(dir.resolve("_schemas/issues.yaml")))
            // --apply で _schemas/<id>.yaml へ保存され、以後の検証に効く
            runCli(arrayOf("infer-schema", dir.toString(), "issues/**/*.yaml", "--apply"))
            assertTrue(java.nio.file.Files.exists(dir.resolve("_schemas/issues.yaml")))
            val ws = Workspace.open(dir)
            assertEquals("issues", ws.schemaFor(dir.resolve("issues/a.yaml"))?.id)
            // 既存ファイルは上書きしない
            assertFailsWith<IllegalArgumentException> {
                runCli(arrayOf("infer-schema", dir.toString(), "issues/**/*.yaml", "--apply"))
            }
        } finally {
            java.nio.file.Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
        }
    }

    @Test
    fun `enum values keep first seen order`() {
        val docs = listOf(
            sample("a.yaml", "s: draft"), sample("b.yaml", "s: review"),
            sample("c.yaml", "s: draft"), sample("d.yaml", "s: done"),
            sample("e.yaml", "s: review"), sample("f.yaml", "s: draft"),
        )
        val draft = SchemaInference.infer(docs, "doc", "*.yaml")
        val fields = getAt(draft, listOf("root", "fields")) as YNode.Seq
        val statusField = fields.items.filterIsInstance<YNode.Map>().first { scalarText(it.entries["key"]) == "s" }
        val values = (statusField.entries["values"] as YNode.Seq).items.map { scalarText(it) }
        assertEquals(listOf("draft", "review", "done"), values)
    }
}
