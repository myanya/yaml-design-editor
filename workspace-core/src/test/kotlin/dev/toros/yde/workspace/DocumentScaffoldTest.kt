package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.SemanticExpression
import dev.toros.yde.semantic.schema.ExpressionValue
import dev.toros.yde.semantic.schema.SchemaScaffold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 新しい文書のひな型と、スキーマからの初期値・サンプルの生成（P27 段D の D-c で root から移した）。
 * アプリ側の細かなケースは、root の ViewsTest・SchemaLoaderTest が、今までどおり保証する（同じ部品を通る）。
 */
class DocumentScaffoldTest {

    private val screen = """
        id: screen-detail
        match: "screens/detail/*.yaml"
        root:
          type: object
          fields:
            - { key: code, type: id, required: true }
            - { key: layout, type: enum, values: [single-column, two-column], required: true, default: single-column }
            - { key: title, type: string, required: true }
            - { key: memo, type: text }
            - key: kind
              type: enum
              required: true
              values: [form, list]
              cases:
                list:
                  - { key: pageSize, type: int, required: true }
            - key: items
              type: array
              item: { type: string }
    """.trimIndent()

    private fun schemas() = WorkspaceReader.load(InMemoryDocumentStore(mapOf("_schemas/screen.yaml" to screen))).schemas

    @Test
    fun `ひな型は 作り先に合うスキーマの必須の項目と ID を持つ`() {
        val scaffold = DocumentScaffolds.scaffold("screens/detail/{id}.yaml", "SCR-NEW", schemas())
        assertEquals("screens/detail/SCR-NEW.yaml", scaffold.rel)
        val map = scaffold.root as YNode.Map
        // ID は、ルート直下の ID の項目（ここでは code）へ入る。default を尊重する。任意の項目は作らない
        assertEquals(listOf("code", "layout", "title", "kind"), map.entries.keys.toList())
        assertEquals("SCR-NEW", scalarText(map.entries["code"]))
        assertEquals("single-column", scalarText(map.entries["layout"]))
        // 合うスキーマが無ければ、id だけの文書
        val plain = DocumentScaffolds.scaffold("notes/{id}.yaml", "N-1", schemas()).root as YNode.Map
        assertEquals(mapOf("id" to "N-1"), plain.entries.mapValues { scalarText(it.value) })
        // ファイル名にできない ID は拒否する
        assertFailsWith<IllegalArgumentException> { DocumentScaffolds.scaffold("screens/detail/{id}.yaml", "../escape", schemas()) }
    }

    @Test
    fun `詳細スキーマの match から 作り先のパターンを導く`() {
        assertEquals("details/{id}.yaml", DocumentScaffolds.detailPatternFromMatch("details/*.yaml"))
        assertEquals("a/b/{id}.yaml", DocumentScaffolds.detailPatternFromMatch("a/b/*.{yaml,yml}"))
        assertEquals("{id}.yml", DocumentScaffolds.detailPatternFromMatch("*.yml"))
        assertNull(DocumentScaffolds.detailPatternFromMatch("a/**/*.yaml"))
        assertNull(DocumentScaffolds.detailPatternFromMatch("a/x-*.yaml"))
    }

    @Test
    fun `サンプルは 任意の項目も含めて 型ごとの例の値を入れる`() {
        val tree = schemas().single { it.id == "screen-detail" }.tree
        val sample = SchemaScaffold.sample(tree) as YNode.Map
        assertEquals(listOf("code", "layout", "title", "memo", "kind", "items"), sample.entries.keys.toList())
        assertEquals("SAMPLE-001", scalarText(sample.entries["code"]))
        assertEquals("サンプル", scalarText(sample.entries["title"]))
        assertEquals("", (sample.entries["memo"] as YNode.Scalar).value)
        assertEquals("form", scalarText(sample.entries["kind"]))
        assertTrue((sample.entries["items"] as YNode.Seq).items.size == 1)
    }

    // ---------------------------------------------------------------- ファイル名に式（P31・R31-02）とひな型の中身（R31-03）

    private val listDoc = DocumentId("screens/list.yaml")
    private val listTree = YamlIO.parse(
        """
        screens:
          - id: SCR-ROOM-LIST
            name: 空き状況照会画面
            domain: 予約/受付
            summary: 一覧表示
        """.trimIndent()
    )

    /** 一覧側の定義出現を元にする（アプリの scaffoldSource と同じ形）。 */
    private fun source(id: String = "SCR-ROOM-LIST", title: String? = "空き状況照会画面") = ScaffoldSource(
        id = id, title = title,
        occurrence = SemanticExpression.Occurrence(listDoc, parseYPath("screens[0]")),
        tree = { d -> listTree.takeIf { d == listDoc } },
    )

    @Test
    fun `ファイル名の式は元の要素の値で穴を埋め 穴に入った値はファイル名に使える文字に直す`() {
        val short = ExpressionValue.of("screens/detail/{id}_{name}.yaml", "filePattern")
        assertEquals("screens/detail/SCR-ROOM-LIST_空き状況照会画面.yaml", DocumentScaffolds.pathFor(short, source()))
        // 区切り文字を含む値は直す（ひな型に直接書いた / はディレクトリのまま）
        val byDomain = ExpressionValue.of("screens/{domain}/{id}.yaml", "filePattern")
        assertEquals("screens/予約-受付/SCR-ROOM-LIST.yaml", DocumentScaffolds.pathFor(byDomain, source()))
        // 完全な書き方: with で計算した値を穴に差し込む
        val full = ExpressionValue.of(
            mapOf("format" to "screens/{dir}/{id}.yaml", "with" to mapOf("dir" to mapOf("map" to mapOf("path" to "domain"), "using" to mapOf("予約/受付" to "reservation")))),
            "filePattern",
        )
        assertEquals("screens/reservation/SCR-ROOM-LIST.yaml", DocumentScaffolds.pathFor(full, source()))
        // 予約の {title} は表示の解決結果
        assertEquals("x/空き状況照会画面.yaml", DocumentScaffolds.pathFor(ExpressionValue.of("x/{title}.yaml", "p"), source()))
    }

    @Test
    fun `穴が埋まらなければ作成はエラーになり どの穴かを文面に示す`() {
        val pattern = ExpressionValue.of("screens/detail/{id}_{code}.yaml", "filePattern")
        val e = assertFailsWith<IllegalArgumentException> { DocumentScaffolds.pathFor(pattern, source()) }
        assertTrue("{code}" in e.message!!, e.message)
        // ID だけの元（newRow）では、{id} 以外の穴は埋まらない
        assertFailsWith<IllegalArgumentException> { DocumentScaffolds.pathFor(ExpressionValue.of("x/{name}.yaml", "p"), ScaffoldSource.idOnly("A-1")) }
        assertEquals("x/A-1.yaml", DocumentScaffolds.pathFor(ExpressionValue.of("x/{id}.yaml", "p"), ScaffoldSource.idOnly("A-1")))
        // 予約の {id} はファイル名に使えない文字を含むと拒否する（今までどおり）
        assertFailsWith<IllegalArgumentException> { DocumentScaffolds.pathFor(ExpressionValue.of("x/{id}.yaml", "p"), ScaffoldSource.idOnly("a/b")) }
    }

    @Test
    fun `ファイル名の決まりは 穴の無いものと YAML でないものを拒み 代表の作り先を決められる`() {
        // 式としては読める（決まった値）が、ファイル名の決まり（FileNameRules）に外れる
        assertEquals(listOf(FileNameRules.Kind.NO_HOLE), FileNameRules.formIssues(ExpressionValue.of("x/all.yaml", "p")).map { it.kind })
        assertEquals(listOf(FileNameRules.Kind.NOT_YAML), FileNameRules.formIssues(ExpressionValue.of("x/{id}.md", "p")).map { it.kind })
        assertEquals(listOf(FileNameRules.Kind.ONLY_RESERVED), FileNameRules.formIssues(ExpressionValue.of("x/{id}_{name}.yaml", "p"), idOnly = true).map { it.kind })
        assertFailsWith<IllegalArgumentException> { FileNameRules.requireWellFormed(ExpressionValue.of("x/{id}.md", "p")) }
        // 式として読めなければ、共通の文面（項目の名前つき）
        val e = assertFailsWith<IllegalArgumentException> { ExpressionValue.of(mapOf("format" to "{id}.yaml", "bogus" to 1), "filePattern", "新しい文書の置き場所") }
        assertTrue("「新しい文書の置き場所」（filePattern）の式が読めません" in e.message!!, e.message)
        val pattern = ExpressionValue.of("screens/{domain}/{id}_{name}.yaml", "p")
        assertEquals(listOf("domain", "id", "name"), pattern.reads)
        assertTrue(pattern.usesId)
        assertEquals("screens/X/SCR-X_X.yaml", DocumentScaffolds.representativePath(pattern, "SCR-X"))
        // スキーマの置き場所: filePattern があればそれ・無ければ match から
        val schema = schemas().single { it.id == "screen-detail" }
        assertEquals("screens/detail/{id}.yaml", DocumentScaffolds.filePatternOf(schema)?.text)
    }

    @Test
    fun `スキーマの filePattern と scaffold で 作り先と中身が元の要素から決まる`() {
        val detail = """
            id: screen-detail
            match: "screens/detail/**/*.yaml"
            filePattern: "screens/detail/{domain}/{id}.yaml"
            scaffold:
              title: { ${'$'}copy: name }
              memo: { ${'$'}format: "{id}（一覧から作成: {summary}）" }
              missing: { ${'$'}copy: nothing }
            root:
              type: object
              fields:
                - { key: code, type: id, required: true }
                - { key: title, type: string, required: true }
                - { key: memo, type: text }
                - { key: layout, type: enum, values: [a, b], required: true, default: a }
        """.trimIndent()
        val loaded = WorkspaceReader.load(InMemoryDocumentStore(mapOf("_schemas/screen.yaml" to detail)))
        assertTrue(loaded.schemaErrors.isEmpty(), loaded.schemaErrors.toString())
        val schema = loaded.schemas.single { it.id == "screen-detail" }
        val scaffold = DocumentScaffolds.scaffold(DocumentScaffolds.filePatternOf(schema)!!, source(), loaded.schemas)
        assertEquals("screens/detail/予約-受付/SCR-ROOM-LIST.yaml", scaffold.rel)
        val map = scaffold.root as YNode.Map
        // 必須の初期値（layout の default）の上に、ひな型の出力（title・memo）を重ね、ID の項目には ID
        assertEquals("SCR-ROOM-LIST", scalarText(map.entries["code"]))
        assertEquals("空き状況照会画面", scalarText(map.entries["title"]))
        assertEquals("SCR-ROOM-LIST（一覧から作成: 一覧表示）", scalarText(map.entries["memo"]))
        assertEquals("a", scalarText(map.entries["layout"]))
        assertNull(map.entries["missing"], "値が得られない指示は出力から落ちる")
        // 元の要素が無ければ（ID だけ）、ひな型は使わず初期値だけ
        val plain = DocumentScaffolds.scaffold(ExpressionValue.of("screens/detail/x/{id}.yaml", "p"), ScaffoldSource.idOnly("SCR-X"), loaded.schemas)
        assertNull((plain.root as YNode.Map).entries["memo"])
    }

    @Test
    fun `filePattern の定義の点検 作り先が match に合わない と {id} が無い`() {
        fun errorsOf(filePattern: String) = WorkspaceReader.load(
            InMemoryDocumentStore(
                mapOf(
                    "_schemas/screen.yaml" to """
                        id: screen-detail
                        match: "screens/detail/*.yaml"
                        filePattern: "$filePattern"
                        entity: { type: screen, idPattern: "SCR-*" }
                        root: { type: object, fields: [ { key: id, type: id } ] }
                    """.trimIndent(),
                )
            )
        ).schemaErrors
        assertTrue(errorsOf("screens/detail/{id}_{name}.yaml").isEmpty())
        assertTrue(errorsOf("elsewhere/{id}.yaml").any { "match（screens/detail/*.yaml）に合いません" in it })
        assertTrue(errorsOf("screens/detail/{name}.yaml").any { "{id} がありません" in it })
        // library には書けない
        val lib = WorkspaceReader.load(InMemoryDocumentStore(mapOf("_schemas/lib.yaml" to "id: lib\nkind: library\nfilePattern: \"x/{id}.yaml\"\n")))
        assertTrue(lib.schemaErrors.any { "文書のスキーマ" in it }, lib.schemaErrors.toString())
    }
}
