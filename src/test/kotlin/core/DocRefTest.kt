package core

import index.IdIndex
import schema.FieldType
import schema.RefTarget
import schema.SchemaLoader
import validate.Severity
import validate.Validator
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 文書参照とファイル移動（R21-03。ARCHITECTURE §4.31）のテスト。
 * - `target: document` の宣言・解決（documentId ＝ `_document.id` ＞ 相対パス）・台帳外の INFO 降格
 * - エンティティ名前空間との分離（文書参照はエンティティIDに解決されない）
 * - 移動（B-1 でカーネル MoveDocument へ移行）: パスID参照の書き換え・スキーマ match の警告・
 *   `_document.id` の安定性・ビュー定義の警告（RefactorImpact.moveWarnings）
 */
class DocRefTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-docref")
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

    private fun writeSchemas() {
        write("_schemas/notes.yaml", """
            id: notes
            match: "notes/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - key: spec
                  type: ref
                  target: document
        """)
        write("_schemas/specs.yaml", """
            id: specs
            match: "specs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: title, type: string }
        """)
    }

    // ---------------------------------------------------------------- 宣言

    @Test
    fun `targetはrefフィールドでのみ宣言できdocumentがパースされる`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            match: "a/*.yaml"
            root:
              type: object
              fields:
                - { key: doc, type: ref, target: document }
                - { key: plain, type: ref }
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        val doc = schema.root.fields.first { it.key == "doc" }
        assertEquals(RefTarget.DOCUMENT, doc.refTarget)
        assertEquals(RefTarget.ENTITY, schema.root.fields.first { it.key == "plain" }.refTarget)

        assertFailsWith<IllegalArgumentException> {
            SchemaLoader.parseSchemaText(
                "id: s\nmatch: \"a/*.yaml\"\nroot: { type: object, fields: [ { key: x, type: string, target: document } ] }",
                Path.of("_schemas/s.yaml"),
            )
        }
        assertFailsWith<IllegalStateException> {
            SchemaLoader.parseSchemaText(
                "id: s\nmatch: \"a/*.yaml\"\nroot: { type: object, fields: [ { key: x, type: ref, target: unknown } ] }",
                Path.of("_schemas/s.yaml"),
            )
        }
    }

    // ---------------------------------------------------------------- 解決

    @Test
    fun `文書参照は_document_idと相対パスに解決され未解決はERRORになる`() {
        writeSchemas()
        write("specs/login.yaml", """
            _document: { id: spec-login, name: ログイン仕様 }
            title: ログイン
        """)
        write("specs/misc.yaml", "title: その他")
        write("notes/a.yaml", """
            id: NOTE-1
            spec: spec-login
        """)
        write("notes/b.yaml", """
            id: NOTE-2
            spec: specs/misc.yaml
        """)
        write("notes/c.yaml", """
            id: NOTE-3
            spec: specs/gone.yaml
        """)
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("notes/a.yaml").none { "文書参照" in it.message }, ws.validate("notes/a.yaml").toString())
        assertTrue(ws.validate("notes/b.yaml").none { "文書参照" in it.message }, ws.validate("notes/b.yaml").toString())
        val unresolved = ws.validate("notes/c.yaml")
        assertTrue(
            unresolved.any { it.severity == Severity.ERROR && "文書参照「specs/gone.yaml」を解決できません" in it.message },
            unresolved.toString(),
        )
        // 索引の RefEntry には targetKind が付与される
        assertTrue(ws.index.refs.filter { it.viaKey == "spec" }.all { it.targetKind == RefTarget.DOCUMENT })
    }

    @Test
    fun `文書参照はエンティティIDには解決されず台帳外ではINFOへ降格する`() {
        writeSchemas()
        // NOTE-9 というIDのエンティティは存在するが、文書ノードではない → 文書参照としては未解決
        write("notes/target.yaml", "id: NOTE-9")
        write("notes/a.yaml", """
            id: NOTE-1
            spec: NOTE-9
        """)
        val ws = Workspace.open(dir)
        val issues = ws.validate("notes/a.yaml")
        assertTrue(issues.any { it.severity == Severity.ERROR && "文書参照「NOTE-9」" in it.message }, issues.toString())

        // 台帳外（indexed=false）は INFO 降格（既存 R13-03 と同じ流儀）
        val doc = ws.load("notes/a.yaml")
        val demoted = Validator.validate(
            doc.root, null, doc.file, doc.schema, ws.index,
            indexed = false, workspace = ws.root, allSchemas = ws.schemas,
        )
        assertTrue(
            demoted.any { it.severity == Severity.INFO && "文書参照「NOTE-9」は台帳外" in it.message },
            demoted.toString(),
        )
    }

    // ---------------------------------------------------------------- 移動（B-1 でカーネル MoveDocument へ移行。ビュー警告は RefactorImpact）

    @Test
    fun `moveはパスID参照を書き換えてスキーマの警告を出す`() {
        writeSchemas()
        write("specs/misc.yaml", "title: その他")
        write("notes/a.yaml", """
            id: NOTE-1
            spec: specs/misc.yaml
        """)
        val session = workspace.WorkspaceSession(workspace.FileSystemDocumentStore(dir))
        val plan = session.planRefactoring(
            semantic.RefactoringRequest.MoveDocument(
                model.DocumentId("specs/misc.yaml"), model.DocumentId("archive/misc.yaml"),
            )
        )
        // パスID参照 1 件の書き換え＋スキーマ match が外れる警告
        assertEquals(1, plan.changes.sumOf { it.operations.size })
        assertTrue(plan.warnings.any { "スキーマの適用が変わります" in it && "specs" in it }, plan.warnings.toString())

        assertTrue(session.applyRefactoring(plan).ok)
        assertTrue(session.save().ok)
        assertTrue(Files.exists(dir.resolve("archive/misc.yaml")))
        assertTrue(!Files.exists(dir.resolve("specs/misc.yaml")))
        assertTrue("spec: archive/misc.yaml" in read("notes/a.yaml"), read("notes/a.yaml"))
        // 移動後は参照が解決される
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("notes/a.yaml").none { "文書参照" in it.message })
    }

    @Test
    fun `ビューのfileGlobが外れる移動は警告される`() {
        writeSchemas()
        write("_views/note-list.yaml", """
            id: note-list
            name: ノート一覧
            rows: { idPattern: "NOTE-*" }
            columns:
              - { label: 仕様, path: spec, fileGlob: "notes/**/*.yaml" }
        """)
        write("notes/a.yaml", "id: NOTE-1")
        val ws = Workspace.open(dir)
        val warnings = RefactorImpact.moveWarnings(ws, "notes/a.yaml", "memo/a.yaml")
        assertTrue(
            warnings.any { "ビュー「note-list」" in it && "fileGlob" in it },
            warnings.toString(),
        )
    }

    @Test
    fun `_document_id宣言済みの文書の移動は参照書き換え不要になる`() {
        writeSchemas()
        write("specs/login.yaml", """
            _document: { id: spec-login }
            title: ログイン
        """)
        write("notes/a.yaml", """
            id: NOTE-1
            spec: spec-login
        """)
        val session = workspace.WorkspaceSession(workspace.FileSystemDocumentStore(dir))
        val plan = session.planRefactoring(
            semantic.RefactoringRequest.MoveDocument(
                model.DocumentId("specs/login.yaml"), model.DocumentId("archive/login.yaml"),
            )
        )
        assertEquals(0, plan.changes.size)
        assertTrue(plan.summary.any { "_document.id" in it && "安定" in it }, plan.summary.toString())
        assertTrue(session.applyRefactoring(plan).ok)
        assertTrue(session.save().ok)
        // 移動後も documentId で解決される
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("notes/a.yaml").none { "文書参照" in it.message })
    }
}
