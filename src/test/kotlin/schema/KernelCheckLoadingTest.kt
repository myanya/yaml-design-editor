package schema

import core.Bench
import core.Workspace
import workspace.BuiltinSchemaResources
import workspace.FileSystemDocumentStore
import workspace.WorkspaceReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 検査の定義の読み込みを、アプリ側（root の SchemaLoader）とカーネル側（WorkspaceReader）が共有していること（P27 段B-2）。
 * 以前はカーネル側が constraints・functions・vocabulary・checkers と組込み定義体を読まず、定義体で書かれた検査を知らなかった
 * （BACKLOG UX-115）。利用者の語彙のキーが「スキーマに定義されていない項目」と警告される問題（UX-105）も同じ根だった。
 */
class KernelCheckLoadingTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-kernel-checks")
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

    private fun loadKernel() = WorkspaceReader.load(
        FileSystemDocumentStore(dir), builtins = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.of(dir)),
    )

    /** カーネルが文書へ配る指摘か（定義体の検査と、コードで実装された検査のうち、エンティティ型の点検とテンプレートの健全性）。 */
    private fun deliveredByKernel(issue: validate.Issue): Boolean {
        val source = issue.source ?: return false
        return !source.coded || source.rule?.at in setOf(validate.CodedChecks.ENTITY, validate.CodedChecks.TEMPLATE)
    }

    @Test
    fun `カーネルのスキーマ読み込みは アプリ側と同じ検査を持つ`() {
        Bench.generate(dir, modules = 1)
        val root = Workspace.open(dir)
        val kernel = loadKernel()
        assertTrue(kernel.schemaErrors.isEmpty(), kernel.schemaErrors.toString())
        val userSchemas = root.schemas.filter { !it.id.startsWith("builtin-") }
        assertTrue(userSchemas.sumOf { it.constraints.size } > 20, "語彙（多重度・対象型・非循環・coverage・matrix）が展開されていること")
        for (schema in userSchemas) {
            val k = kernel.schemas.single { it.id == schema.id }
            // 検査の中身と出どころ（どの書き方から展開された・何番目の検査か）まで一致する
            assertEquals(schema.constraints, k.constraints, "constraints: ${schema.id}")
            assertEquals(schema.checkers, k.checkers, "checkers: ${schema.id}")
            assertEquals(schema.expandedChecks, k.expandedChecks, "expandedChecks: ${schema.id}")
        }
    }

    @Test
    fun `カーネル側で検査を実行した結果は アプリ側と一致する`() {
        Bench.generate(dir, modules = 3)
        // 違反・他の出現への案内・直せる場所への案内・参照の場所への指摘・保留がひととおり出るように壊す
        fun rewrite(rel: String, transform: (String) -> String) =
            Files.writeString(dir.resolve(rel), transform(Files.readString(dir.resolve(rel))))
        rewrite("m001/tests.yaml") { it.lines().filterNot { l -> "TI-1-1-1," in l }.joinToString("\n") }
        rewrite("m001/screens/SCR-1-1.yaml") { it.replace(Regex("""max_length: \d+ }"""), "max_length: 9999 }") }
        Files.delete(dir.resolve("m002/screens/SCR-2-6.yaml"))
        rewrite("m002/processes/PROC-2-6.yaml") { it.replace("calls: []", "calls: [PROC-2-1]") }
        rewrite("m003/screens/SCR-3-1.yaml") { it.replaceFirst(Regex("""dtype: DT-\d+"""), "dtype: DT-GONE") }
        rewrite("m003/transitions.yaml") { it.lines().filterNot { l -> "from: SCR-3-4, to: SCR-3-5" in l }.joinToString("\n") }

        val root = Workspace.open(dir)
        val kernel = workspace.WorkspaceCheckRunner.deliverAll(loadKernel())
        var compared = 0
        for (file in root.files) {
            val rel = root.relPath(file)
            val result = root.validateDetailed(rel)
            // アプリ側の指摘のうち、カーネルが文書へ配るもの（定義体の検査と、コードで実装された検査のうちワークスペースの他の文書も見るもの。
            // 構造の検証・参照の解決などは、カーネルでは別の区分で出る）
            val app = result.issues.filter { deliveredByKernel(it) }.map { listOf(it.severity, it.message, it.path, it.source) }
            val delivery = kernel[model.DocumentId(rel)]
            val core = delivery?.issues.orEmpty().map { listOf(it.severity, it.message, it.path, it.source) }.distinct()
            assertEquals(app, core, "指摘: $rel")
            assertEquals(result.withheld, delivery?.withheld.orEmpty(), "保留: $rel")
            compared += app.size
        }
        assertTrue(compared > 20, "壊した内容の指摘を比べていること（$compared 件）")
        assertTrue(kernel.values.any { it.withheld.isNotEmpty() }, "保留が出ていること")
    }

    @Test
    fun `利用者の語彙のキーは カーネル側でも 定義されていない項目と警告されない`() {
        write(
            "_schemas/lib.yaml",
            """
            id: lib
            kind: library
            functions:
              - id: has-owner
                params: [schema]
                checks:
                  - match:
                      - node: { schema: ${'$'}schema, bind: a }
                    check:
                      - ne: [{ of: a, path: owner }, ""]
            vocabulary:
              - word: ownership
                in: "ownership[]"
                uses:
                  - use: has-owner
                    with: { schema: { schema: id } }
            """,
        )
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            match: "docs/*.yaml"
            entity: { type: doc, idPattern: "D-*", defines: true }
            ownership:
              - {}
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: owner, type: string }
            """,
        )
        write("docs/D-1.yaml", "id: D-1\nowner: 山田")
        val kernel = loadKernel()
        assertTrue(kernel.schemaErrors.isEmpty(), kernel.schemaErrors.toString())
        // 語彙 ownership が、基本の検査へ展開されている
        assertEquals(1, kernel.schemas.single { it.id == "doc" }.constraints.size)
        // スキーマ文書の構造の検証で、語彙のキー ownership が「定義されていない項目」にならない
        val schemaDoc = kernel.derived.structuralIssues.entries.filter { it.key.value.endsWith("_schemas/doc.yaml") }
        assertTrue(schemaDoc.flatMap { it.value }.none { "ownership" in it.message }, schemaDoc.toString())
    }

    @Test
    fun `スキーマ文書の読み取りは アプリ側とカーネル側で同じ誤りを同じように報告する`() {
        // P27 段C の C-3a（意図的差分 D-31）: 読み取りと関係レジストリの集約は workspace-core の SchemaDocuments の1実装。
        // 以前のカーネル側は、書ける場所の決まりの誤りを見逃し、レジストリの重複は黙って後から読んだほうを採っていた
        write(
            "_schemas/a-relations.yaml",
            """
            id: a-relations
            kind: relations
            relations:
              - { name: uses, label: 利用する }
            """,
        )
        write(
            "_schemas/b-relations.yaml",
            """
            id: b-relations
            kind: relations
            relations:
              - { name: uses, label: 重ねて宣言した関係 }
            """,
        )
        write(
            "_schemas/lib.yaml",
            """
            id: lib
            kind: library
            entity: { type: doc, idPattern: "D-*" }
            """,
        )
        write("_schemas/doc.yaml", "id: doc\nmatch: \"docs/*.yaml\"\nroot:\n  type: object\n  fields:\n    - { key: id, type: id }")
        val kernel = loadKernel()
        val app = Workspace.open(dir)
        for (expected in listOf("関係「uses」が複数のレジストリで宣言されています", "entity は kind: library には定義できません")) {
            assertTrue(kernel.schemaErrors.any { expected in it }, "カーネル側: $expected / ${kernel.schemaErrors}")
            assertTrue(app.schemaErrors.any { expected in it }, "アプリ側: $expected / ${app.schemaErrors}")
        }
        // 重ねて宣言された関係は、どちらの側も、先に読んだほう（a-relations）を採る
        assertEquals("利用する", kernel.declarations.relations.getValue("uses").label)
        assertEquals("利用する", app.relations.getValue("uses").label)
    }

    @Test
    fun `アプリのスキーマは カーネルが読んだスキーマを包むだけ`() {
        // P27 段C の C-3b（意図的差分 D-32）: スキーマを読むのはカーネルの経路だけ。SchemaDef は、カーネルの型に、ファイルの場所を足す薄い型
        write("_schemas/doc.yaml", "id: doc\nname: 文書\nmatch: \"docs/*.yaml\"\nroot:\n  type: object\n  fields:\n    - { key: id, type: id }")
        // スキーマ置き場の下の階層のファイルも読む（カーネルと同じ）
        write("_schemas/sub/note.yaml", "id: note\nmatch: \"notes/*.yaml\"\nroot:\n  type: object\n  fields:\n    - { key: id, type: id }")
        write("_schemas/dup.yaml", "id: doc\nmatch: \"other/*.yaml\"\nroot:\n  type: object")
        val app = Workspace.open(dir)
        val doc = app.schemas.first { it.id == "doc" }
        assertEquals("文書", doc.name)
        assertEquals(dir.resolve("_schemas/doc.yaml").toAbsolutePath().normalize(), doc.source.toAbsolutePath().normalize())
        assertEquals("_schemas/doc.yaml", doc.kernel.source.value)
        // 項目の木も、導出した構造のスキーマも、カーネルの型のものをそのまま使う（二重に持たない）
        assertTrue(doc.tree === doc.kernel.tree && doc.structures === doc.kernel.structures)
        assertTrue(app.schemas.any { it.id == "note" }, "下の階層のスキーマ: ${app.schemas.map { it.id }}")
        // 組込みスキーマも、カーネルの1経路から（アプリとカーネルで同じ中身）
        val kernel = loadKernel()
        assertEquals(kernel.schemas.map { it.id }, app.schemas.map { it.id })
        // 同じ ID のスキーマは、どちらの側でも誤り。文面は、ワークスペース相対のパスか、ファイル名の並び
        for (errors in listOf(app.schemaErrors, kernel.schemaErrors)) {
            assertTrue(errors.any { "スキーマID「doc」が重複しています" in it && "doc.yaml" in it && "dup.yaml" in it }, errors.toString())
        }
    }

    @Test
    fun `スキーマ置き場が無くても 組込みスキーマは適用される`() {
        write("_agents/a.yaml", "id: agent-a\nname: エージェント\nunknown_key: 1")
        val app = Workspace.open(dir)
        assertTrue(app.schemas.any { it.id == "builtin-agent" }, app.schemas.map { it.id }.toString())
        assertTrue(app.validate("_agents/a.yaml").any { "unknown_key" in it.message })
    }

    @Test
    fun `定義の誤りは カーネル側でもスキーマの読み込みエラーになる`() {
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            match: "docs/*.yaml"
            constraints:
              - use: no-such-definition
            root:
              type: object
              fields:
                - { key: id, type: id }
            """,
        )
        val kernel = loadKernel()
        assertTrue(kernel.schemaErrors.any { "no-such-definition" in it }, kernel.schemaErrors.toString())
    }
    /** スキーマの定義の点検・関係名の点検・ID のパターン・重複所有・テンプレートの健全性がひととおり出るワークスペース。 */
    private fun writeLintWorkspace(withSchemaErrors: Boolean) {
        write("_schemas/relations.yaml", "id: relations\nkind: relations\nrelations:\n  - { name: typed-as, label: 値型 }")
        val extra = if (withSchemaErrors) {
            "\n          - { key: owner, type: ref, relation: owned-by }\n          - { key: note, type: int, maxLength: 5 }"
        } else ""
        write(
            "_schemas/concepts.yaml",
            """
            id: concepts
            name: 概念
            match: "concepts.yaml"
            entity:
              - { type: vtype, idPattern: "VT-*", defines: true }
              - { type: field, idPattern: "FLD-*", defines: true }
            root:
              type: object
              fields:
                - key: types
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: maxLength, type: int }
                - key: fields
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: vtype, type: ref, idPattern: "VT-*", relation: typed-as }
            """.trimIndent() + extra,
        )
        write(
            "_schemas/mapping.yaml",
            """
            id: mapping
            name: 型写像
            match: "mapping.yaml"
            root:
              type: object
              fields:
                - key: mappings
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: to, type: string }
            """,
        )
        write(
            "_schemas/impl.yaml",
            """
            id: impl
            name: 実装設計
            match: "impl.yaml"
            consistency:
              - description: 実装型は型写像から導出した値と一致すること
                idPattern: "FLD-*"
                path: type
                expected:
                  lookup: { schemaId: concepts, via: vtype }
                  source: { schemaId: mapping }
                  path: to
                  values:
                    - schemaId: concepts
            root:
              type: object
              fields:
                - key: rows
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: type, type: string }
            """,
        )
        for ((id, entity) in listOf(
            "design" to "{ type: screen, idPattern: \"SCR-*\", aspect: 設計, owns: [layout, memo] }",
            "memo" to "{ type: screen, idPattern: \"SCR-*\", defines: true }",
        )) {
            write(
                "_schemas/$id.yaml",
                """
                id: $id
                name: 画面（$id）
                match: "details-$id/*.yaml"
                entity: $entity
                root:
                  type: object
                  fields:
                    - { key: id, type: id, required: true }
                    - { key: memo, type: string }
                """,
            )
        }
        // ID のパターンからの逸脱（BAD-1）・相手の見つからない参照（VT-GONE）・同じ ID の重複（VT-TITLE）
        write("concepts.yaml", "types:\n  - id: VT-TITLE\n    maxLength: 100\n  - id: VT-TITLE\n  - id: BAD-1\nfields:\n  - id: FLD-TITLE\n    vtype: VT-TITLE\n  - id: FLD-X\n    vtype: VT-GONE")
        write("mapping.yaml", "mappings:\n  - id: VT-TITLE\n    to: varchar({maxlen})\nextra: 1")
        write("impl.yaml", "rows:\n  - id: FLD-TITLE\n    type: varchar(100)")
        write("details-design/scr-001.yaml", "id: SCR-001\nmemo: 設計のメモ")
        write("details-memo/scr-001.yaml", "id: SCR-001\nmemo: 補足メモ")
    }

    @Test
    fun `スキーマの定義の点検と 宣言のない関係名の点検は カーネルの読み込みが行い アプリはそのまま使う`() {
        // P27 段D の D-a（意図的差分 D-33）: 以前は root の SchemaLoader だけが行っていた
        writeLintWorkspace(withSchemaErrors = true)
        val kernel = loadKernel()
        val app = Workspace.open(dir)
        assertEquals(
            listOf(
                "concepts.yaml [root.fields.item.note]: minLength / maxLength は string / text 型でのみ使用できます",
                "concepts.yaml: 関係「owned-by」は関係レジストリに宣言されていません",
            ),
            kernel.schemaErrors,
        )
        assertEquals(kernel.schemaErrors, app.schemaErrors)
        // 1件だけを読んだ場面の窓口（スキーマ編集画面のプレビューなど）も、カーネルの同じ実装
        val concepts = app.schemas.first { it.id == "concepts" }
        assertEquals(workspace.SchemaDefinitionChecks.issuesOf(concepts.kernel), concepts.validateDefinition())
    }

    @Test
    fun `カーネルだけで検証した結果は アプリの検証の結果と 文書ごとに一致する`() {
        // P27 段D の D-a（意図的差分 D-33）: ID のパターンからの逸脱・重複所有・テンプレートの健全性も、カーネルが文書へ配る。
        // 構造の検証・意味の検証（ID の重複・参照の解決）と合わせて、カーネル側の CLI（workspace validate）が出す指摘の全体が、アプリと揃う
        writeLintWorkspace(withSchemaErrors = false)
        val app = Workspace.open(dir)
        val kernel = loadKernel()
        assertTrue(kernel.schemaErrors.isEmpty(), kernel.schemaErrors.toString())
        val delivered = workspace.WorkspaceCheckRunner.deliverAll(kernel)
        val seen = mutableSetOf<String>()
        for (file in app.files) {
            val rel = app.relPath(file)
            val id = model.DocumentId(rel)
            val fromApp = app.validateDetailed(rel).issues.map { Triple(it.severity, it.message, it.path) }
            val fromKernel = kernel.derived.structuralIssues[id].orEmpty().map { Triple(it.severity, it.message, it.path) } +
                kernel.derived.semantic.issues.filter { it.document == id }.map { Triple(it.severity, it.message, it.path) } +
                delivered[id]?.issues.orEmpty().map { Triple(it.severity, it.message, it.path) }
            assertEquals(fromApp.map { it.toString() }.sorted(), fromKernel.distinct().map { it.toString() }.sorted(), "指摘: $rel")
            // カーネルが文書へ配る分は、並び順と出どころまで一致する
            assertEquals(
                app.validateDetailed(rel).issues.filter { deliveredByKernel(it) }.map { listOf(it.message, it.path, it.source) },
                delivered[id]?.issues.orEmpty().map { listOf(it.message, it.path, it.source) }.distinct(),
                "文書へ配る指摘: $rel",
            )
            seen += fromApp.map { it.second }
        }
        for (expected in listOf("いずれにも一致しません", "重複所有", "プレースホルダ {maxlen}", "VT-GONE", "重複", "スキーマに定義されていない項目")) {
            assertTrue(seen.any { expected in it }, "$expected が出ていること: $seen")
        }
    }
}
