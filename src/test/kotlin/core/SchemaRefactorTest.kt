package core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * スキーマ・リファクタリング（R25-01。ARCHITECTURE §4.35）のテスト。
 * フィールド改名がスキーマ定義・全該当文書のデータキー（順序・コメント保持）・ビュー通常列を
 * 1つの変更セットへ束ね、既存の Workspace.apply で適用できること。cases・共有 $ref 定義・
 * phases.key の追随と、衝突・対象外（library 直指定）の拒否をケース化する。
 */
class SchemaRefactorTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-schemarefactor")
        writeWorkspace()
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun read(rel: String): String = Files.readString(dir.resolve(rel))

    private fun writeWorkspace() {
        write("_schemas/task.yaml", """
            id: task-schema
            match: "tasks/**/*.yaml"
            phases:
              key: status
              order: [open, done]
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: title, type: string }
                - key: status
                  type: enum
                  values: [open, done]
                  cases:
                    done:
                      - { key: note, type: string }
                - key: details
                  type: object
                  fields:
                    - { key: priority, type: string, requiredFrom: done }
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: label, type: string }
                - { key: points, type: string }
                - { key: shared, ref: "lib#common" }
        """)
        write("_schemas/rel.yaml", """
            kind: relations
            relations:
              - { name: uses, label: 使用 }
        """)
        write("_schemas/lib.yaml", """
            id: lib
            kind: library
            definitions:
              common:
                type: object
                fields:
                  - { key: tag, type: string }
        """)
        write("_schemas/other.yaml", """
            id: other-schema
            match: "other/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: shared, ref: "lib#common" }
                - { key: parent, type: ref, relation: uses, idPatterns: "TASK-*" }
        """)
        write("_views/tasks.yaml", """
            id: task-list
            name: タスク一覧
            rows: { idPattern: "TASK-*" }
            columns:
              - { label: タイトル, path: title }
              - { label: 優先度, path: details.priority }
        """)
        write("tasks/t1.yaml", """
            id: TASK-001
            # タイトルの前のコメント
            title: 最初のタスク  # 行内コメント
            status: done
            note: 完了メモ
            details:
              priority: high
            items:
              - { label: a }
              - { label: b }
            points: "3"
            shared:
              tag: v1
        """)
        write("tasks/t2.yaml", """
            id: TASK-002
            title: 二番目
            status: open
            note: まだ有効でないケースのキー
        """)
        write("other/o1.yaml", """
            id: OTH-001
            shared:
              tag: v2
            parent: TASK-001
        """)
    }

    @Test
    fun `renames field across schema documents and views`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.renameField(ws, "task-schema", "title", "name")
        assertTrue(plan.summary.any { "title" in it && "name" in it })

        val result = ws.apply(plan.changeSet)
        assertTrue(result.ok, result.results.joinToString { "${it.file}: ${it.message}" })

        val schemaText = read("_schemas/task.yaml")
        assertTrue("key: name" in schemaText && "key: title" !in schemaText, schemaText)
        val t1 = read("tasks/t1.yaml")
        assertTrue("name: 最初のタスク" in t1, t1)
        assertTrue("タイトルの前のコメント" in t1 && "行内コメント" in t1, "コメントが保持される: $t1")
        // キー順の保持: id の次に name（旧 title の位置）
        assertTrue(t1.indexOf("id:") < t1.indexOf("name:") && t1.indexOf("name:") < t1.indexOf("status:"), t1)
        assertTrue("name: 二番目" in read("tasks/t2.yaml"))
        // ビューの通常列も追随する
        assertTrue("path: name" in read("_views/tasks.yaml"))
    }

    @Test
    fun `renames nested field and rewrites view path`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.renameField(ws, "task-schema", "details.priority", "urgency")
        assertTrue(ws.apply(plan.changeSet).ok)
        assertTrue("urgency: high" in read("tasks/t1.yaml"))
        assertTrue("path: details.urgency" in read("_views/tasks.yaml"))
    }

    @Test
    fun `renames case field only where the case is active`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.renameField(ws, "task-schema", "note", "remark")
        assertTrue(ws.apply(plan.changeSet).ok)
        // スキーマは cases 配下のキーが書き換わる
        assertTrue("key: remark" in read("_schemas/task.yaml"))
        // status: done の文書だけ改名され、case が有効でない文書のキーはデータとして触らない
        assertTrue("remark: 完了メモ" in read("tasks/t1.yaml"))
        assertTrue("note: まだ有効でないケースのキー" in read("tasks/t2.yaml"))
    }

    @Test
    fun `renames shared definition field across using schemas`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.renameField(ws, "task-schema", "shared.tag", "version")
        assertTrue(plan.summary.any { "影響スキーマ" in it && "other-schema" in it }, plan.summary.toString())
        assertTrue(ws.apply(plan.changeSet).ok)
        // 書き換わるのは定義側（lib）で、使用側の全スキーマの文書が追随する
        assertTrue("key: version" in read("_schemas/lib.yaml"))
        assertTrue("key: tag" !in read("_schemas/lib.yaml"))
        assertTrue("version: v1" in read("tasks/t1.yaml"))
        assertTrue("version: v2" in read("other/o1.yaml"))
    }

    @Test
    fun `renaming phase field also updates phases key`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.renameField(ws, "task-schema", "status", "state")
        assertTrue(ws.apply(plan.changeSet).ok)
        val schemaText = read("_schemas/task.yaml")
        assertTrue("key: state" in schemaText, schemaText)
        assertTrue("key: status" !in schemaText, schemaText)
        assertTrue(Regex("phases:\\s*\\n\\s*key: state").containsMatchIn(schemaText), schemaText)
        assertTrue("state: done" in read("tasks/t1.yaml"))
    }

    @Test
    fun `refuses when new key already exists in a document`() {
        write("tasks/t3.yaml", """
            id: TASK-003
            title: 旧名
            name: 既にある
            status: open
        """)
        val ws = Workspace.open(dir)
        val e = assertFailsWith<IllegalStateException> {
            SchemaRefactor.renameField(ws, "task-schema", "title", "name")
        }
        assertTrue("t3.yaml" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `refuses library schema as direct target and unknown fields`() {
        val ws = Workspace.open(dir)
        assertFailsWith<IllegalArgumentException> {
            SchemaRefactor.renameField(ws, "lib", "tag", "version")
        }
        val e = assertFailsWith<IllegalArgumentException> {
            SchemaRefactor.renameField(ws, "task-schema", "nope", "x")
        }
        assertTrue("nope" in (e.message ?: ""))
        assertFailsWith<IllegalArgumentException> {
            SchemaRefactor.renameField(ws, "task-schema", "title", "title")
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaRefactor.renameField(ws, "task-schema", "title", "id")  // スキーマ側の兄弟キー衝突
        }
    }

    // ---------------------------------------------------------------- R25-02: 移動・整列

    @Test
    fun `moves field into nested object`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.moveField(ws, "task-schema", "title", "details")
        // ビューの title 列は自動追随の対象外＝警告として提示される
        assertTrue(plan.warnings.any { "tasks.yaml" in it }, plan.warnings.toString())
        assertTrue(ws.apply(plan.changeSet).ok)
        val t1 = read("tasks/t1.yaml")
        assertTrue("  title: 最初のタスク" in t1, t1)
        assertTrue("行内コメント" in t1, "コメントが移動先でも保持される: $t1")
        assertTrue(!Regex("^title:", RegexOption.MULTILINE).containsMatchIn(t1), t1)
        // details を持たない文書では中間 object が生成される
        val t2 = read("tasks/t2.yaml")
        assertTrue("details:" in t2 && "  title: 二番目" in t2, t2)
        // スキーマ側も details 配下へ移動している
        val ws2 = Workspace.open(dir)
        val schema = ws2.schemas.first { it.id == "task-schema" }
        val root = schema.resolve(schema.root)!!
        assertTrue(root.fields.none { it.key == "title" })
        val details = schema.resolve(root.fields.first { it.key == "details" })!!
        assertTrue(details.fields.any { it.key == "title" })
    }

    @Test
    fun `move to root deletes emptied parent`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.moveField(ws, "task-schema", "details.priority", ".")
        assertTrue(ws.apply(plan.changeSet).ok)
        val t1 = read("tasks/t1.yaml")
        assertTrue(Regex("^priority: high", RegexOption.MULTILINE).containsMatchIn(t1), t1)
        assertTrue("details:" !in t1, "空になった親 object は削除される: $t1")
    }

    @Test
    fun `rejects moves across array boundary`() {
        val ws = Workspace.open(dir)
        assertFailsWith<IllegalArgumentException> {
            SchemaRefactor.moveField(ws, "task-schema", "items.label", ".")
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaRefactor.moveField(ws, "task-schema", "title", "items")
        }
    }

    @Test
    fun `aligns document keys to schema order`() {
        write("tasks/t4.yaml", """
            title: 順序テスト
            id: TASK-004
            status: open
        """)
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.alignOrder(ws, "task-schema")
        assertTrue(ws.apply(plan.changeSet).ok)
        val t4 = read("tasks/t4.yaml")
        assertTrue(t4.indexOf("id:") < t4.indexOf("title:") && t4.indexOf("title:") < t4.indexOf("status:"), t4)
    }

    // ---------------------------------------------------------------- R25-03: 語彙拡大

    @Test
    fun `renames enum value across schema phases and documents`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.renameEnumValue(ws, "task-schema", "status", "done", "completed")
        assertTrue(ws.apply(plan.changeSet).ok)
        val schemaText = read("_schemas/task.yaml")
        assertTrue("values: [open, completed]" in schemaText, schemaText)
        assertTrue("completed:" in schemaText && "done:" !in schemaText, "cases のキーが追随する: $schemaText")
        assertTrue("order: [open, completed]" in schemaText, "phases.order が追随する: $schemaText")
        assertTrue("requiredFrom: completed" in schemaText, "requiredFrom が追随する: $schemaText")
        assertTrue("status: completed" in read("tasks/t1.yaml"))
        assertTrue("status: open" in read("tasks/t2.yaml"))
    }

    @Test
    fun `deletes field with data cleanup`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.deleteField(ws, "task-schema", "points")
        assertTrue(plan.summary.any { "値あり 1" in it }, plan.summary.toString())
        assertTrue(ws.apply(plan.changeSet).ok)
        assertTrue("points" !in read("_schemas/task.yaml"))
        assertTrue("points" !in read("tasks/t1.yaml"))
    }

    @Test
    fun `backfills required field with default`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.requireField(ws, "task-schema", "points", "0")
        assertTrue(ws.apply(plan.changeSet).ok)
        assertTrue("required: true" in read("_schemas/task.yaml"))
        assertTrue("points: \"3\"" in read("tasks/t1.yaml") || "points: '3'" in read("tasks/t1.yaml"), "既存値は上書きしない")
        assertTrue(Regex("points: ['\"]?0['\"]?").containsMatchIn(read("tasks/t2.yaml")), "欠落値が充填される: ${read("tasks/t2.yaml")}")
    }

    @Test
    fun `converts scalar to array and back`() {
        val ws = Workspace.open(dir)
        assertTrue(ws.apply(SchemaRefactor.convertFieldType(ws, "task-schema", "points", "array").changeSet).ok)
        val schemaText = read("_schemas/task.yaml")
        assertTrue("type: array" in schemaText, schemaText)
        val wrapped = yaml.YamlIO.parseFirst(read("tasks/t1.yaml")) as model.YNode.Map
        val seq = wrapped.entries["points"] as? model.YNode.Seq
        assertEquals(1, seq?.items?.size, "現在値が1要素の配列に包まれる")
        // 解除（unwrap）: 1要素の配列は値を引き継ぐ
        val ws2 = Workspace.open(dir)
        assertTrue(ws2.apply(SchemaRefactor.convertFieldType(ws2, "task-schema", "points", "string").changeSet).ok)
        val unwrapped = yaml.YamlIO.parseFirst(read("tasks/t1.yaml")) as model.YNode.Map
        assertTrue(unwrapped.entries["points"] is model.YNode.Scalar)
    }

    // ---------------------------------------------------------------- R25-04: 定義・関係の改名

    @Test
    fun `renames definition across modules`() {
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.renameDefinition(ws, "lib", "common", "base")
        assertTrue(ws.apply(plan.changeSet).ok)
        assertTrue("base:" in read("_schemas/lib.yaml") && "common:" !in read("_schemas/lib.yaml"))
        assertTrue("lib#base" in read("_schemas/task.yaml"))
        assertTrue("lib#base" in read("_schemas/other.yaml"))
    }

    @Test
    fun `extracts and inlines definition`() {
        val ws = Workspace.open(dir)
        assertTrue(ws.apply(SchemaRefactor.extractDefinition(ws, "task-schema", "details", "detailsDef").changeSet).ok)
        val extracted = read("_schemas/task.yaml")
        assertTrue("detailsDef:" in extracted, extracted)
        assertTrue("\$ref: detailsDef" in extracted, extracted)
        // 文書は変わらない
        assertTrue("priority: high" in read("tasks/t1.yaml"))
        // インライン化で元へ戻り、参照が残らない定義は削除される
        val ws2 = Workspace.open(dir)
        assertTrue(ws2.apply(SchemaRefactor.inlineDefinition(ws2, "task-schema", "details").changeSet).ok)
        val inlined = read("_schemas/task.yaml")
        assertTrue("detailsDef" !in inlined, inlined)
        val ws3 = Workspace.open(dir)
        val schema = ws3.schemas.first { it.id == "task-schema" }
        val details = schema.resolve((schema.resolve(schema.root)!!).fields.first { it.key == "details" })!!
        assertTrue(details.fields.any { it.key == "priority" }, "インライン化後も構造が保たれる")
    }

    @Test
    fun `renames relation and schema id`() {
        val ws = Workspace.open(dir)
        assertTrue(ws.apply(SchemaRefactor.renameRelation(ws, "uses", "depends").changeSet).ok)
        assertTrue("name: depends" in read("_schemas/rel.yaml"))
        assertTrue("relation: depends" in read("_schemas/other.yaml"))
        val ws2 = Workspace.open(dir)
        assertTrue(ws2.apply(SchemaRefactor.renameSchemaId(ws2, "other-schema", "other2").changeSet).ok)
        assertTrue("id: other2" in read("_schemas/other.yaml"))
    }

    @Test
    fun `warns about non-rewritten mentions in declarations`() {
        write("_activities/fill-title.yaml", """
            id: fill-title
            name: タイトル補完
            instruction: 各タスクの title を補完する
        """)
        val ws = Workspace.open(dir)
        val plan = SchemaRefactor.renameField(ws, "task-schema", "title", "name")
        assertTrue(plan.warnings.any { "fill-title.yaml" in it }, plan.warnings.toString())
        // 文書側の変更内容は警告の有無に影響されない
        assertFalse(plan.changeSet.changes.any { it.file.startsWith("_activities/") })
    }
}
