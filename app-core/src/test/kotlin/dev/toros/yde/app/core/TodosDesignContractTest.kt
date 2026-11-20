package dev.toros.yde.app.core

import dev.toros.yde.workspace.BusinessModel
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.LoadedWorkspace
import dev.toros.yde.workspace.WorkspaceCheckRunner
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pretty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 合成処理に先立ち、候補と確定値の保存形式・索引・入れ子・未解決の境界を確認する。 */
class TodosDesignContractTest {
    private val root = Path.of("samples/todos-sysdev")
    private val libraries = listOf("basic-design-values", "basic-derived-model", "basic-design-contract")
        .associate { name -> ".yde/schemas/$name.yaml" to Files.readString(root.resolve(TodosSysdevFiles.definition(root, "schemas", name))) }

    private fun load(vararg docs: Triple<String, String, String>): LoadedWorkspace {
        val files = linkedMapOf(".yde/workspace.yaml" to "ledger: [design]\n") + libraries + docs.flatMapIndexed { index, (name, type, text) ->
            listOf(
                ".yde/schemas/probe-$index.yaml" to "id: probe-$index\nmatch: design/$name.yaml\nroot: {type: $type}\n",
                "design/$name.yaml" to text.trimIndent(),
            )
        }.toMap()
        return WorkspaceReader.load(InMemoryDocumentStore(files)).also { assertEquals(emptyList(), it.schemaErrors) }
    }

    private fun errors(ws: LoadedWorkspace): List<String> = WorkspaceCheckRunner.validateAll(ws)
        .flatMap { (doc, validation) -> validation.issues.filter { it.severity.name == "ERROR" }.map { "${doc.value}: ${it.path.pretty()}: ${it.message}" } }

    private fun ids(ws: LoadedWorkspace) = ws.derived.semantic.ids.values.flatten().filter { it.document.value.startsWith("design/") }
    private fun contract(name: String, type: String, text: String) = Triple(name, "basic-design-contract#$type", text)

    @Test
    fun `設計入力の再帰型を含むモデル全体も有限に導出し 導出記録の保存形式で検証できる`() {
        val derived = BusinessModel.of(load())
        val text = YamlIO.dumpPlain(derived)
        val types = (YamlIO.toAny(derived) as Map<*, *>)["types"] as List<*>
        val candidate = types.map { it as Map<*, *> }.single { it["name"] == "basic-design-contract#候補の値" }
        val item = (candidate["fields"] as List<*>).map { it as Map<*, *> }.single { it["key"] == "item" }
        assertEquals("basic-design-contract#候補の値", item["recursiveType"])
        assertFalse(item.containsKey("fields"), "共通の子は型の参照で保持する")
        val checked = load(Triple("recursive-model", "basic-derived-model#モデル", text))
        assertEquals(emptyList(), errors(checked))
    }

    private val addition = """
        additions:
          - id: ADD-001
            reason: 版番号方式の楽観ロック
            kind: field
            field:
              designId: FLD-todo.version
              owner: ENT-todo
              key: version
              type: TYP-int
              base: int
              default: 0
    """.trimIndent()

    @Test
    fun `新しい候補への補正と利用指定は 前回の確定結果が無くても記録できる`() {
        val ws = load(
            contract("additions", "追加一覧", addition),
            contract("corrections", "補正と指定一覧", """
                corrections:
                  - {id: COR-001, target: FLD-todo.version, set: {label: 版}, reason: 用語をそろえる}
                specifications:
                  - id: SPC-001
                    target: ENT-todo
                    rule: optimistic-lock
                    settings: {method: revision}
                    uses: {revision: FLD-todo.version}
                    reason: 既存の版を利用する
            """),
        )
        assertEquals(emptyList(), errors(ws))
        assertEquals(setOf("ADD-001", "COR-001", "SPC-001"), ids(ws).map { it.id }.toSet())
        assertTrue(ws.derived.semantic.refs.none { it.document.value.startsWith("design/") }, "生成中の対象を通常の索引で解決しない")
    }

    @Test
    fun `規則の追加と既定値の候補と成立条件を 出どころ付きで別々に保持できる`() {
        val expanded = """
            additions:
              - id: ADD-001
                rule: optimistic-lock
                reason: 更新の競合を検出する
                kind: field
                field: {designId: FLD-todo.version, owner: ENT-todo, key: version, base: int, default: 0}
            changes:
              - {id: CHG-001, rule: rule-a, target: FLD-todo.version, set: {label: 版}, reason: 規則Aの既定値}
              - {id: CHG-002, rule: rule-b, target: FLD-todo.version, set: {label: 更新回数}, reason: 規則Bの既定値}
            conditions:
              - rule: optimistic-lock
                target: FLD-todo.version
                check: [{eq: [{path: base}, int]}]
                message: 版は整数である必要があります
        """.trimIndent()
        val ws = load(contract("changes", "展開した候補", expanded))
        assertEquals(emptyList(), errors(ws))
        assertEquals(setOf("ADD-001", "CHG-001", "CHG-002"), ids(ws).map { it.id }.toSet())
        // 競合を含む候補を失わず記録できることを検査する。採用値の決定は次の合成処理が行う。
        val missingOrigin = expanded.replace("    rule: optimistic-lock\n", "")
        assertTrue(errors(load(contract("changes", "展開した候補", missingOrigin))).isNotEmpty())
    }

    @Test
    fun `候補と同じ識別子があっても 確定項目だけを通常の参照先として登録する`() {
        val ws = load(
            contract("additions", "追加一覧", addition),
            contract("entities", "確定エンティティ一覧", """
                entities:
                  - id: ENT-todo
                    items:
                      - {id: FLD-todo.version, key: version, type: TYP-int, base: int, required: false, default: 1}
                    operations:
                      - id: OPS-todo.edit
                        writes:
                          - {id: CRUD-todo.edit.version, entity: ENT-todo, operation: OPS-todo.edit, item: FLD-todo.version, crud: U}
            """),
            contract("types", "確定型一覧", """
                types: [{id: TYP-int, name: int, base: int, required: false}]
            """),
        )
        assertEquals(emptyList(), errors(ws))
        assertEquals(listOf("design/entities.yaml"), ids(ws).filter { it.id == "FLD-todo.version" }.map { it.document.value })
        assertEquals(1, ws.derived.semantic.refs.count { it.document.value == "design/entities.yaml" && it.targetId == "FLD-todo.version" })
    }

    private val nested = """
        entities:
          - id: ENT-todo
            items:
              - id: FLD-todo.grid
                key: grid
                base: array
                required: false
                default: []
                item:
                  base: array
                  required: false
                  item:
                    base: map
                    required: false
                    value:
                      base: object
                      required: false
                      fields:
                        - {id: FLD-todo.grid.name, key: name, base: string, required: false, maxLength: 100, default: ''}
              - {id: FLD-todo.done, key: done, base: boolean, required: false, default: false}
              - {id: FLD-todo.qty, key: qty, base: int, required: false, default: 0}
              - {id: FLD-todo.memo, key: memo, base: string, required: false}
    """.trimIndent()

    @Test
    fun `配列 マップ object を区別し false ゼロ 空の値と未指定を保存できる`() {
        val ws = load(contract("entities", "確定エンティティ一覧", nested))
        assertEquals(emptyList(), errors(ws))
        val parsed = YamlIO.toAny(YamlIO.parseFirst(nested)!!) as Map<*, *>
        val entity = (parsed["entities"] as List<*>).single() as Map<*, *>
        val fields = (entity["items"] as List<*>).map { it as Map<*, *> }.associateBy { it["key"] }
        assertEquals(false, fields.getValue("done")["default"])
        assertEquals(0, fields.getValue("qty")["default"])
        assertEquals(emptyList<Any?>(), fields.getValue("grid")["default"])
        assertFalse(fields.getValue("memo").containsKey("default"))
        val value = ((fields.getValue("grid")["item"] as Map<*, *>)["item"] as Map<*, *>)["value"] as Map<*, *>
        assertEquals("", ((value["fields"] as List<*>).single() as Map<*, *>)["default"])
        val invalid = load(contract("entities", "確定エンティティ一覧", nested.replace("maxLength: 100", "maxLength: 長い")))
        assertTrue(errors(invalid).any { "item.item.value.fields[0].maxLength" in it }, errors(invalid).toString())
        val typo = load(contract("entities", "確定エンティティ一覧", nested.replace("maxLength: 100", "maxLenght: 100")))
        assertTrue(errors(typo).any { "maxLenght" in it }, errors(typo).toString())
    }

    @Test
    fun `深い入れ子と再帰する型を参照で保存でき 参照切れは検出する`() {
        var value: Map<String, Any?> = mapOf("base" to "string", "required" to false, "maxLength" to 100)
        repeat(16) { value = mapOf("base" to "array", "required" to false, "item" to value) }
        val text = YamlIO.dumpPlain(YamlIO.fromAny(mapOf("types" to listOf(value + mapOf("id" to "TYP-grid", "name" to "sample#Grid")))))
        val recursive = """
            types:
              - id: TYP-node
                name: sample#Node
                base: object
                required: false
                fields:
                  - {id: FLD-node.next, key: next, type: TYP-node, base: object, required: false}
        """.trimIndent()
        val ws = load(contract("grid", "確定型一覧", text), contract("node", "確定型一覧", recursive))
        assertEquals(emptyList(), errors(ws))
        assertEquals(setOf("TYP-grid", "TYP-node", "FLD-node.next"), ids(ws).map { it.id }.toSet())
        val missing = load(contract("node", "確定型一覧", recursive.replace("type: TYP-node", "type: TYP-missing")))
        assertTrue(errors(missing).any { "TYP-missing" in it }, errors(missing).toString())
    }

    @Test
    fun `複数の参照先と絞り込みの層と属性の根拠を保持できる`() {
        val text = """
            entities:
              - {id: ENT-a}
              - {id: ENT-b}
              - id: ENT-todo
                items:
                  - id: FLD-todo.related
                    key: related
                    base: ref
                    required: false
                    maxLength: 100
                    reference:
                      target: entity
                      relation: related-to
                      entities: [ENT-a, ENT-b]
                      scope: {schemaIds: [a, b], documentIds: [a-list, b-list]}
                      idPatternLayers: [['A-*', 'B-*'], ['*-001']]
                    attributeStates: {maxLength: present, minLength: unconstrained, format: not-applicable}
                    origins:
                      - {kind: derived, source: common#番号, attribute: maxLength, value: 200}
                      - {kind: correction, source: COR-001, attribute: maxLength, value: 100, reason: 外部仕様}
        """.trimIndent()
        val ws = load(contract("entities", "確定エンティティ一覧", text))
        assertEquals(emptyList(), errors(ws))
        assertEquals(setOf("ENT-a", "ENT-b"), ws.derived.semantic.refs.filter { it.document.value == "design/entities.yaml" }.map { it.targetId }.toSet())
        val parsed = YamlIO.toAny(YamlIO.parseFirst(text)!!) as Map<*, *>
        val item = (((parsed["entities"] as List<*>).last() as Map<*, *>)["items"] as List<*>).single() as Map<*, *>
        assertEquals(listOf(listOf("A-*", "B-*"), listOf("*-001")), (item["reference"] as Map<*, *>)["idPatternLayers"])
        assertEquals(2, (item["origins"] as List<*>).size)
        assertTrue(errors(load(contract("entities", "確定エンティティ一覧", text.replace("target: entity", "target: table")))).isNotEmpty())
    }

    @Test
    fun `選択肢によって現れる子項目を 候補と確定項目の両方で検査する`() {
        val text = """
            id: TYP-state
            name: common#状態
            base: enum
            required: false
            values: [{value: open}, {value: closed}]
            cases:
              open:
                - {id: FLD-state.open.note, key: note, base: string, required: false, maxLength: 100}
              closed:
                - {id: FLD-state.closed.note, key: note, base: string, required: true, maxLength: 200}
        """.trimIndent()
        for ((type, document) in listOf("候補の型" to text.replace("id:", "designId:"), "確定した型" to text)) {
            val ws = load(contract("state", type, document))
            assertEquals(emptyList(), errors(ws), type)
            assertEquals(if (type == "確定した型") setOf("TYP-state", "FLD-state.open.note", "FLD-state.closed.note") else emptySet(), ids(ws).map { it.id }.toSet())
            val invalid = load(contract("state", type, document.replace("maxLength: 200", "maxLength: 間違い")))
            assertTrue(errors(invalid).any { "cases.closed[0].maxLength" in it }, errors(invalid).toString())
        }
    }

    @Test
    fun `候補の未解決と確定値を区別し 余分な ID や CRUD 表を許さない`() {
        val unresolved = addition.replace("default: 0", "attributeStates: {maxLength: unresolved}")
        assertEquals(emptyList(), errors(load(contract("additions", "追加一覧", unresolved))))
        val wrongId = addition.replace("designId: FLD-todo.version", "id: FLD-todo.version")
        assertTrue(errors(load(contract("additions", "追加一覧", wrongId))).isNotEmpty())
        val final = nested.replace("maxLength: 100", "maxLength: 100, attributeStates: {maxLength: unresolved}")
        assertTrue(errors(load(contract("entities", "確定エンティティ一覧", final))).any { "unresolved" in it })
        val secondCrud = nested.replace("id: ENT-todo", "id: ENT-todo\n    crud: []")
        assertTrue(errors(load(contract("entities", "確定エンティティ一覧", secondCrud))).any { "crud" in it })
    }

    @Test
    fun `実際の導出記録を検査し 入れ子の誤った型を見逃さない`() {
        val raw = Files.readString(root.resolve("design/basic/derived/model.yaml"))
        val doc = Triple("model", "basic-derived-model#モデル", YamlIO.dumpPlain((YamlIO.parseFirst(raw) as YNode.Map).entries.getValue("model")))
        val ws = load(doc)
        assertEquals(emptyList(), errors(ws))
        assertTrue(ids(ws).isEmpty(), "業務の識別子は導出記録の中から索引に載せない")
        val invalid = load(doc.copy(third = doc.third.replace("maxLength: 100", "maxLength: 間違い")))
        assertTrue(errors(invalid).any { "maxLength" in it }, errors(invalid).toString())
    }
}
