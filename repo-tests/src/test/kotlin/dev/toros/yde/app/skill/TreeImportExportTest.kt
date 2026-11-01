package dev.toros.yde.app.skill

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JSON を木のまま取り込み・書き出す skill（input.tree・export の build・output の mode / index / fill / style / finalNewline・
 * 書き出し先を実行のときに選ぶ）。題材は、ゲームエンジンのデータのような「番号＝配列の位置・先頭は null・要素ごとに 1 行」の
 * 架空の JSON（コードによって dataId の意味が変わる効果の配列・能力値の並び・小数と整数の混在・別の設定と同居する種別の一覧）。
 */
class TreeImportExportTest {

    private lateinit var dir: Path
    private lateinit var outside: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-tree")
        outside = createTempDirectory("yde-tree-out")
        write(".yde/workspace.yaml", "ledger: db\n")
        write(".yde/skills/import-items.yaml", IMPORT_ITEMS)
        write(".yde/skills/export-items.yaml", EXPORT_ITEMS)
        write(".yde/skills/import-elements.yaml", IMPORT_ELEMENTS)
        write(".yde/skills/export-system.yaml", EXPORT_SYSTEM)
    }

    @AfterTest
    fun cleanup() {
        listOf(dir, outside).forEach { root -> Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    private fun write(rel: String, text: String, root: Path = dir): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return p
    }

    private fun ws() = Workspace.open(dir)

    private fun skill(id: String): SkillDef {
        val loaded = Skills.load(dir)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        return loaded.skill(id) ?: error("skill $id がありません")
    }

    private fun expected(yaml: String): Any? = YamlIO.parseToRaw(yaml.trimIndent())

    private fun importItems() {
        val preview = Importer.build(ws(), skill("import-items"), write("in/Items.json", ITEMS_JSON, outside))
        assertTrue(preview.ok, preview.errors.toString())
        assertTrue(ws().apply(preview.changeSet).ok)
    }

    @Test
    fun `木のまま取り込むと null を飛ばし コードごとの規則で番号を参照の ID に直す`() {
        val preview = Importer.build(ws(), skill("import-items"), write("in/Items.json", ITEMS_JSON, outside))
        assertTrue(preview.ok, preview.errors.toString())
        assertEquals(1, preview.skippedNulls, "先頭の null は空きとして飛ばす")
        assertEquals(listOf("db/items/ITM-0001.yaml", "db/items/ITM-0002.yaml"), preview.created)
        assertTrue(ws().apply(preview.changeSet).ok)

        val item = YamlIO.toAny(YamlIO.parse(Files.readString(dir.resolve("db/items/ITM-0001.yaml"))))
        assertEquals(
            expected(
                """
                id: ITM-0001
                name: 清めの塩
                price: 50
                rate: 1
                params: { mhp: 0, atk: 0 }
                effects:
                  - { code: 21, dataId: STA-0003, value1: 0.5 }
                  - { code: 11, dataId: 0, value1: 100 }
                note: ""
                """
            ),
            item,
        )
    }

    @Test
    fun `取り込んで書き出すと元の JSON とバイト単位で同じになり 書き出しを取り込み直すと変更はない`() {
        importItems()
        val result = Exporter.run(ws(), skill("export-items"))
        assertEquals("out/Items.json", result.rel)
        assertEquals(2, result.rows)
        assertEquals(ITEMS_JSON, Files.readString(dir.resolve("out/Items.json")))

        // 同じ中身なら書かない（ファイルはそのまま）
        assertFalse(Exporter.run(ws(), skill("export-items")).changed)
        // 書き出した JSON を取り込み直しても、原本は変わらない
        val again = Importer.build(ws(), skill("import-items"), dir.resolve("out/Items.json"))
        assertTrue(again.ok, again.errors.toString())
        assertTrue(again.changeSet.changes.isEmpty(), again.changeSet.changes.toString())
    }

    @Test
    fun `書き出し先を実行のときに選ぶと ワークスペースの外へ同じファイル名で書く`() {
        importItems()
        val plan = Exporter.plan(ws(), skill("export-items"), outside.resolve("in"))
        assertEquals(outside.resolve("in/Items.json").toAbsolutePath().normalize(), plan.file)
        assertFalse(plan.changed, "取り込んだ元のファイルと同じ中身")
        Files.writeString(outside.resolve("in/Items.json"), ITEMS_JSON.replace("\"price\":50", "\"price\":60"))
        val diff = Exporter.plan(ws(), skill("export-items"), outside.resolve("in")).lineDiff()!!
        assertEquals(1, diff.size)
        assertEquals(2, diff.single().start, "変わるのは 3 行目（1 番の要素）だけ")
        val written = Exporter.run(ws(), skill("export-items"), outside.resolve("in"))
        assertTrue(written.changed)
        assertEquals(ITEMS_JSON, Files.readString(outside.resolve("in/Items.json")))
    }

    @Test
    fun `番号の空きは fill で埋め 無ければ null`() {
        importItems()
        write("db/items/ITM-0004.yaml", "id: ITM-0004\nname: 懐中電灯\nprice: 300\nrate: 1\nparams: { atk: 3 }\neffects: []\nnote: \"\"\n")
        Exporter.run(ws(), skill("export-items"))
        // 1 行目は "["。番号 n の値は n + 1 行目（0 始まりの lines[n + 1]）
        val lines = Files.readString(dir.resolve("out/Items.json")).split("\n")
        assertEquals("null,", lines[4], "3 番は空き")
        assertTrue(lines[5].startsWith("{\"id\":4,"), lines[5])

        write(".yde/skills/export-items.yaml", EXPORT_ITEMS.replace("finalNewline: false", "finalNewline: false\n  fill: { id: \$index, name: \"\" }"))
        Exporter.run(ws(), skill("export-items"))
        val filled = Files.readString(dir.resolve("out/Items.json")).split("\n")
        assertEquals("{\"id\":0,\"name\":\"\"},", filled[1], "0 番も空きなので fill")
        assertEquals("{\"id\":3,\"name\":\"\"},", filled[4])
    }

    @Test
    fun `文字の配列は index で ID を付けて 1 つの文書の配列へ取り込み 既存の object へ書き戻す`() {
        val system = write("in/System.json", SYSTEM_JSON, outside)
        val preview = Importer.build(ws(), skill("import-elements"), system)
        assertTrue(preview.ok, preview.errors.toString())
        assertEquals(1, preview.skippedRows, "先頭の空文字は飛ばす")
        assertTrue(ws().apply(preview.changeSet).ok)
        assertEquals(
            expected("elements: [ { id: ELM-01, name: 物理 }, { id: ELM-02, name: 炎 } ]"),
            YamlIO.toAny(YamlIO.parse(Files.readString(dir.resolve("db/system.yaml")))),
        )

        // 種別の名前を変えて、既存の System.json の elements だけを書き換える（ほかの項目・キーの順・書式はそのまま）
        Files.writeString(dir.resolve("db/system.yaml"), "elements: [ { id: ELM-01, name: 物理 }, { id: ELM-02, name: 火炎 } ]\n")
        val result = Exporter.run(ws(), skill("export-system"), outside.resolve("in"))
        assertTrue(result.changed)
        assertEquals(SYSTEM_JSON.replace("\"炎\"", "\"火炎\""), Files.readString(system))
    }

    @Test
    fun `mode merge は書き出し先に既存の JSON が無ければ誤り`() {
        write("db/system.yaml", "elements: [ { id: ELM-01, name: 物理 } ]\n")
        val error = runCatching { Exporter.run(ws(), skill("export-system")) }.exceptionOrNull()
        assertTrue("既存の JSON に書き込みます" in error?.message.orEmpty(), error?.message)
    }

    /** 書き出し先のフォルダ（別のアプリのデータのフォルダに見立てる）に元の JSON を置き、そこから取り込んでおく。 */
    private fun prepareBundle(): Path {
        val data = outside.resolve("data")
        write("data/Items.json", ITEMS_JSON, outside)
        write("data/System.json", SYSTEM_JSON, outside)
        write(".yde/skills/export-all.yaml", "id: export-all\nkind: export\ninclude: [export-items, export-system]\n")
        assertTrue(ws().apply(Importer.build(ws(), skill("import-items"), data.resolve("Items.json")).changeSet).ok)
        assertTrue(ws().apply(Importer.build(ws(), skill("import-elements"), data.resolve("System.json")).changeSet).ok)
        return data
    }

    @Test
    fun `まとめて書き出すと全部を計画してから変わるファイルだけを書く`() {
        val data = prepareBundle()
        val unchanged = Exporter.plans(ws(), skill("export-all"), data)
        assertEquals(listOf("Items.json", "System.json"), unchanged.map { it.file.fileName.toString() })
        assertTrue(unchanged.none { it.changed }, "取り込んだ元と同じ")

        Files.writeString(dir.resolve("db/items/ITM-0001.yaml"), Files.readString(dir.resolve("db/items/ITM-0001.yaml")).replace("price: 50", "price: 60"))
        val plans = Exporter.plans(ws(), skill("export-all"), data)
        assertEquals(listOf(true, false), plans.map { it.changed })
        assertEquals(ITEMS_JSON, Files.readString(data.resolve("Items.json")), "計画しただけでは書かない")
        val results = Exporter.write(plans)
        assertEquals(listOf(true, false), results.map { it.changed })
        assertEquals(ITEMS_JSON.replace("\"price\":50", "\"price\":60"), Files.readString(data.resolve("Items.json")))
        assertEquals(SYSTEM_JSON, Files.readString(data.resolve("System.json")))
        Files.list(data).use { files -> assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") }, "一時ファイルを残さない") }
    }

    @Test
    fun `まとめて書き出すとき 1 つでも誤りがあれば何も書かない`() {
        prepareBundle()
        val empty = Files.createDirectories(outside.resolve("empty"))
        // System.json は既存の JSON に書き込む（merge）ので、空のフォルダでは誤り。先に計画できる Items.json も書かない
        val error = runCatching { Exporter.plans(ws(), skill("export-all"), empty) }.exceptionOrNull()
        assertTrue("export-system" in error?.message.orEmpty(), error?.message)
        Files.list(empty).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `確認している間に書き出し先が変わったら何も書かない`() {
        val data = prepareBundle()
        Files.writeString(dir.resolve("db/system.yaml"), "elements: [ { id: ELM-01, name: 物理 }, { id: ELM-02, name: 火炎 } ]\n")
        Files.writeString(dir.resolve("db/items/ITM-0002.yaml"), Files.readString(dir.resolve("db/items/ITM-0002.yaml")).replace("price: 1200", "price: 1500"))
        val plans = Exporter.plans(ws(), skill("export-all"), data)
        assertTrue(plans.all { it.changed })
        val edited = ITEMS_JSON.replace("\"name\":\"清めの塩\"", "\"name\":\"粗塩\"")
        Files.writeString(data.resolve("Items.json"), edited)   // 別のアプリが書き換えた
        val error = runCatching { Exporter.write(plans) }.exceptionOrNull()
        assertTrue("確認の後に変わりました" in error?.message.orEmpty(), error?.message)
        assertEquals(edited, Files.readString(data.resolve("Items.json")))
        assertEquals(SYSTEM_JSON, Files.readString(data.resolve("System.json")), "変わっていない方も書かない")
    }

    @Test
    fun `まとめて書き出す定義の誤り`() {
        write(".yde/skills/a.yaml", "id: a\nkind: export\ninclude: [export-items, nothing]\n")
        write(".yde/skills/b.yaml", "id: b\nkind: export\ninclude: [import-items]\n")
        write(".yde/skills/c.yaml", "id: c\nkind: export\ninclude: [b]\n")
        write(".yde/skills/d.yaml", "id: d\nkind: export\ninclude: [export-items]\noutput: { file: out/x.json }\n")
        val loaded = Skills.load(dir)
        val errors = loaded.errors.joinToString(" / ")
        assertTrue("「nothing」が見つかりません" in errors, errors)
        assertTrue("「import-items」は書き出し（kind: export）ではありません" in errors, errors)
        assertTrue("output を書きません" in errors, errors)
        assertTrue("「b」もまとめて書き出す定義です" in errors, errors)
        assertTrue(listOf("a", "b", "c", "d").none { loaded.skill(it) != null }, "誤りのある定義は外す")
        listOf("a", "b", "c", "d").forEach { Files.delete(dir.resolve(".yde/skills/$it.yaml")) }

        // 書き出し先が重なる束ね方は、計画で誤り
        write(".yde/skills/export-items-2.yaml", EXPORT_ITEMS.replace("id: export-items", "id: export-items-2"))
        write(".yde/skills/e.yaml", "id: e\nkind: export\ninclude: [export-items, export-items-2]\n")
        importItems()
        val error = runCatching { Exporter.plans(ws(), Skills.load(dir).skill("e")!!) }.exceptionOrNull()
        assertTrue("書き出し先が重なっています" in error?.message.orEmpty(), error?.message)
    }

    @Test
    fun `定義の誤りは読み込みで分かる`() {
        fun loadError(yaml: String): String {
            write(".yde/skills/bad.yaml", yaml.trimIndent())
            return Skills.load(dir).errors.joinToString(" / ").also { Files.delete(dir.resolve(".yde/skills/bad.yaml")) }
        }
        assertTrue("build（ひな型）で組み立てます" in loadError(
            """
            id: bad
            kind: import
            input: { format: json, tree: true }
            target: { file: "db/{ID}.yaml" }
            mapping: [ { column: ID, path: id } ]
            """
        ))
        assertTrue("木のままの取り込み" in loadError(
            """
            id: bad
            kind: import
            input: { format: csv, tree: true }
            target: { file: "db/{id}.yaml" }
            build: { id: { ${'$'}copy: ID } }
            """
        ))
        assertTrue("columns を書きません" in loadError(
            """
            id: bad
            kind: export
            source: { match: "db/**/*.yaml" }
            build: { id: { ${'$'}copy: id } }
            columns: [ { header: ID, path: id } ]
            output: { file: out/a.json }
            """
        ))
        assertTrue("木を書ける形式だけ" in loadError(
            """
            id: bad
            kind: export
            source: { match: "db/**/*.yaml" }
            build: { id: { ${'$'}copy: id } }
            output: { file: out/a.csv }
            """
        ))
        assertTrue("mode: array で書きます" in loadError(
            """
            id: bad
            kind: export
            source: { match: "db/**/*.yaml" }
            build: { id: { ${'$'}copy: id } }
            output: { file: out/a.json, mode: value, index: id }
            """
        ))
        assertTrue("build（木を組み立てる書き出し）と一緒に書きます" in loadError(
            """
            id: bad
            kind: export
            source: { match: "db/**/*.yaml" }
            columns: [ { header: ID, path: id } ]
            output: { file: out/a.json, style: lines }
            """
        ))
    }

    companion object {
        private val D = '$'

        /** 先頭は null・要素ごとに 1 行・末尾に改行なし。効果の dataId はコード 21 ならステートの番号。 */
        val ITEMS_JSON = "[\nnull,\n" +
            "{\"id\":1,\"name\":\"清めの塩\",\"price\":50,\"rate\":1,\"params\":[0,0]," +
            "\"effects\":[{\"code\":21,\"dataId\":3,\"value1\":0.5},{\"code\":11,\"dataId\":0,\"value1\":100}],\"note\":\"\"},\n" +
            "{\"id\":2,\"name\":\"古いカメラ\",\"price\":1200,\"rate\":0.95,\"params\":[10,5],\"effects\":[],\"note\":\"改行\\nあり\"}\n]"

        /** 種別の一覧（先頭は空文字）と、ほかの設定が 1 つの object に同居する。 */
        val SYSTEM_JSON = "{\"advanced\":{\"gameId\":18362392,\"screenScale\":1},\"elements\":[\"\",\"物理\",\"炎\"],\"locale\":\"ja_JP\",\"windowTone\":[0,0,0,0]}"

        val IMPORT_ITEMS = """
            id: import-items
            kind: import
            input: { format: json, tree: true }
            target: { file: "db/items/{id}.yaml" }
            build:
              id: { ${D}format: "ITM-{n}", ${D}with: { n: { ${D}number: { ${D}copy: id }, ${D}as: "0000" } } }
              name: { ${D}copy: name }
              price: { ${D}copy: price }
              rate: { ${D}copy: rate }
              params: { mhp: { ${D}copy: "params[0]" }, atk: { ${D}copy: "params[1]" } }
              effects: { ${D}each: effects, ${D}apply: effect }
              note: { ${D}copy: note }
            rules:
              - id: effect
                when: { path: code, equals: 21 }
                build:
                  code: 21
                  dataId: { ${D}format: "STA-{n}", ${D}with: { n: { ${D}number: { ${D}copy: dataId }, ${D}as: "0000" } } }
                  value1: { ${D}copy: value1 }
              - id: effect
                build: { code: { ${D}copy: code }, dataId: { ${D}copy: dataId }, value1: { ${D}copy: value1 } }
        """.trimIndent() + "\n"

        val EXPORT_ITEMS = """
            id: export-items
            kind: export
            source: { match: "db/items/*.yaml" }
            build:
              id: { ${D}number: { ${D}value: { extract: [ { path: id }, "[0-9]+${D}" ] } } }
              name: { ${D}copy: name }
              price: { ${D}copy: price }
              rate: { ${D}copy: rate }
              params: [ { ${D}first: [ { ${D}copy: params.mhp }, 0 ] }, { ${D}first: [ { ${D}copy: params.atk }, 0 ] } ]
              effects: { ${D}each: effects, ${D}apply: effect }
              note: { ${D}copy: note }
            rules:
              - id: effect
                when: { path: code, equals: 21 }
                build:
                  code: 21
                  dataId: { ${D}number: { ${D}value: { extract: [ { path: dataId }, "[0-9]+${D}" ] } } }
                  value1: { ${D}copy: value1 }
              - id: effect
                build: { code: { ${D}copy: code }, dataId: { ${D}copy: dataId }, value1: { ${D}copy: value1 } }
            output:
              file: out/Items.json
              index: id
              style: lines
              finalNewline: false
        """.trimIndent() + "\n"

        val IMPORT_ELEMENTS = """
            id: import-elements
            kind: import
            input: { format: json, tree: true, path: elements }
            target: { file: db/system.yaml, path: elements }
            build:
              ${D}when: { path: ., empty: false }
              ${D}then:
                id: { ${D}format: "ELM-{n}", ${D}with: { n: { ${D}number: ${D}index, ${D}as: "00" } } }
                name: { ${D}copy: . }
        """.trimIndent() + "\n"

        val EXPORT_SYSTEM = """
            id: export-system
            kind: export
            source: { match: db/system.yaml }
            build:
              elements: { ${D}concat: [ [ "" ], { ${D}each: elements, ${D}build: { ${D}copy: name } } ] }
            output:
              file: out/System.json
              mode: merge
              style: compact
              finalNewline: false
        """.trimIndent() + "\n"
    }
}
