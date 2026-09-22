package dev.toros.yde.app.schema

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 派生スキーマ（R11-03）のテスト。
 * 「テーブル定義書 → レコード検証スキーマ」の導出と、導出元文書の変更が再検証に反映される
 * 往復（導出の往復）を検証する（ARCHITECTURE §4.23）。
 */
class DerivedSchemasTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-derive")
        write("_schemas/record-template.yaml", """
            id: record-template
            name: レコード検証
            description: テーブル定義書から導出されるレコードスキーマ
            derive:
              from: "tables/**/*.{yaml,yml}"
              id: "record-{id}"
              name: "{title} レコード"
              match: "records/{id}/**/*.{yaml,yml}"
              fields:
                source: columns
                key: name
                label: label
                description: note
                required: required
                type:
                  path: dataType
                  map: { varchar: string, bool: boolean, number: int }
                  default: string
            root:
              type: object
              fields:
                - key: id
                  type: id
                  required: true
        """)
        write("tables/tbl-task.yaml", """
            id: TBL-TASK
            title: タスク
            columns:
              - { name: task_name, label: タスク名, dataType: varchar, required: true }
              - { name: done, dataType: bool }
              - { name: point, dataType: number, note: 見積りポイント }
        """)
        write("records/TBL-TASK/r1.yaml", """
            id: R-1
            task_name: 設計レビューを行う
            done: true
            point: 3
        """)
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

    // ---------------------------------------------------------------- 導出（第3段階）

    @Test
    fun `table definition document compiles into a record schema`() {
        val loaded = SchemaLoader.loadAllWithErrors(dir)
        assertEquals(emptyList(), loaded.errors, loaded.errors.toString())
        val derived = loaded.schemas.first { it.id == "record-TBL-TASK" }
        assertEquals("タスク レコード", derived.name)
        // 適用先は derive.match のプレースホルダ置換で決まる
        assertTrue(derived.matches("records/TBL-TASK/r1.yaml"))
        assertTrue(!derived.matches("records/TBL-OTHER/r1.yaml"))
        // 共通フィールド（テンプレートの root）＋ columns からの導出フィールド
        val keys = derived.root.fields.map { it.key }
        assertEquals(listOf("id", "task_name", "done", "point"), keys)
        val taskName = derived.root.fields.first { it.key == "task_name" }
        assertEquals(FieldType.STRING, taskName.type)
        assertEquals("タスク名", taskName.label)
        assertTrue(taskName.required)
        assertEquals(FieldType.BOOLEAN, derived.root.fields.first { it.key == "done" }.type)
        val point = derived.root.fields.first { it.key == "point" }
        assertEquals(FieldType.INT, point.type)
        assertEquals("見積りポイント", point.description)
        // source は導出元文書を指す（ファイルとしては存在しない読み取り専用の成果物）
        assertTrue(derived.source.toString().replace('\\', '/').endsWith("tables/tbl-task.yaml"))
        // 宣言スキーマ（テンプレート）自身はどの文書にもマッチしない
        val template = loaded.schemas.first { it.id == "record-template" }
        assertTrue(!template.matches("tables/tbl-task.yaml"))
        assertTrue(!template.matches("records/TBL-TASK/r1.yaml"))
    }

    @Test
    fun `records are validated against the derived schema`() {
        val ws = Workspace.open(dir)
        assertEquals("record-TBL-TASK", ws.schemaFor(dir.resolve("records/TBL-TASK/r1.yaml"))?.id)
        assertEquals(
            emptyList(),
            ws.validate("records/TBL-TASK/r1.yaml").filter { it.severity == Severity.ERROR },
        )
        // 必須カラム（task_name。表示は label「タスク名」）を欠くレコードはエラーになる
        write("records/TBL-TASK/r2.yaml", "id: R-2\ndone: false")
        ws.noteDiskChanges(listOf(dir.resolve("records/TBL-TASK/r2.yaml")))
        val issues = ws.validate("records/TBL-TASK/r2.yaml")
        assertTrue(issues.any { it.severity == Severity.ERROR && "タスク名" in it.message }, issues.toString())
    }

    @Test
    fun `editing the source document changes validation rules on reload`() {
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.validate("records/TBL-TASK/r1.yaml").filter { it.severity == Severity.ERROR })
        // 導出元のテーブル定義に必須カラムを追加すると、既存レコードが新ルールで検証される
        write("tables/tbl-task.yaml", """
            id: TBL-TASK
            title: タスク
            columns:
              - { name: task_name, label: タスク名, dataType: varchar, required: true }
              - { name: done, dataType: bool }
              - { name: point, dataType: number }
              - { name: owner, dataType: varchar, required: true }
        """)
        // 導出元文書の変更はスキーマ変更と同じ扱い（noteDiskChanges が全再構築へ振り分ける。§4.23）
        ws.noteDiskChanges(listOf(dir.resolve("tables/tbl-task.yaml")))
        val issues = ws.validate("records/TBL-TASK/r1.yaml")
        assertTrue(issues.any { it.severity == Severity.ERROR && "owner" in it.message }, issues.toString())
    }

    @Test
    fun `each derives multiple schemas from one document`() {
        write("_schemas/entity-template.yaml", """
            id: entity-template
            name: エンティティ別
            derive:
              from: "models/**/*.yaml"
              each: entities
              id: "data-{code}"
              match: "data/{code}/**/*.yaml"
              fields:
                source: attrs
                key: name
            root:
              type: object
              fields:
                - key: id
                  type: id
        """)
        write("models/model.yaml", """
            entities:
              - code: user
                attrs: [ { name: email }, { name: age } ]
              - code: org
                attrs: [ { name: title } ]
        """)
        val loaded = SchemaLoader.loadAllWithErrors(dir)
        assertEquals(emptyList(), loaded.errors, loaded.errors.toString())
        val user = loaded.schemas.first { it.id == "data-user" }
        assertEquals(listOf("id", "email", "age"), user.root.fields.map { it.key })
        assertNotNull(loaded.schemas.firstOrNull { it.id == "data-org" })
    }

    // ---------------------------------------------------------------- 宣言の制約とエラー報告

    @Test
    fun `derive declaration constraints are enforced at parse time`() {
        // match との併用は不可（適用先は derive.match が決める）
        assertFailsWith<IllegalArgumentException> {
            SchemaLoader.parseSchemaText(
                """
                id: bad
                match: "docs/**/*.yaml"
                derive:
                  from: "tables/**/*.yaml"
                  id: "x-{id}"
                  match: "records/{id}/*.yaml"
                  fields: { source: columns, key: name }
                """.trimIndent(),
                Path.of("_schemas/bad.yaml"),
            )
        }
        // 予約ディレクトリからの導出は不可
        assertFailsWith<IllegalArgumentException> {
            SchemaLoader.parseSchemaText(
                """
                id: bad2
                derive:
                  from: "_runs/**/*.yaml"
                  id: "x-{id}"
                  match: "records/{id}/*.yaml"
                  fields: { source: columns, key: name }
                """.trimIndent(),
                Path.of("_schemas/bad2.yaml"),
            )
        }
        // 未知の型を値辞書に書くと定義エラー
        assertFailsWith<IllegalArgumentException> {
            SchemaLoader.parseSchemaText(
                """
                id: bad3
                derive:
                  from: "tables/**/*.yaml"
                  id: "x-{id}"
                  match: "records/{id}/*.yaml"
                  fields:
                    source: columns
                    key: name
                    type: { path: dataType, map: { varchar: nonsense } }
                """.trimIndent(),
                Path.of("_schemas/bad3.yaml"),
            )
        }
    }

    @Test
    fun `derivation problems are reported as schema errors`() {
        // プレースホルダ値の欠落（id が無いテーブル定義）とキー欠落は理由付きでエラーになる
        write("tables/broken.yaml", """
            title: ID なし
            columns:
              - { label: 名前だけ }
        """)
        val loaded = SchemaLoader.loadAllWithErrors(dir)
        assertTrue(loaded.errors.any { "broken.yaml" in it || "{id}" in it }, loaded.errors.toString())
        // 既存の導出（正常な文書）は影響を受けない
        assertNotNull(loaded.schemas.firstOrNull { it.id == "record-TBL-TASK" })
    }

    @Test
    fun `duplicate derived ids are reported and skipped`() {
        write("tables/dup.yaml", """
            id: TBL-TASK
            title: 重複
            columns: []
        """)
        val loaded = SchemaLoader.loadAllWithErrors(dir)
        assertTrue(loaded.errors.any { "record-TBL-TASK" in it && "重複" in it }, loaded.errors.toString())
        assertEquals(1, loaded.schemas.count { it.id == "record-TBL-TASK" })
    }

    @Test
    fun `unknown source type falls back to default with error`() {
        write("tables/tbl-misc.yaml", """
            id: TBL-MISC
            title: 雑多
            columns:
              - { name: field_a, dataType: unknown-kind }
        """)
        val loaded = SchemaLoader.loadAllWithErrors(dir)
        assertTrue(loaded.errors.any { "unknown-kind" in it }, loaded.errors.toString())
        val derived = loaded.schemas.first { it.id == "record-TBL-MISC" }
        assertEquals(FieldType.STRING, derived.root.fields.first { it.key == "field_a" }.type)
    }
}
