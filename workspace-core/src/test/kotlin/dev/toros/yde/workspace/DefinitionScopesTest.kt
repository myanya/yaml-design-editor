package dev.toros.yde.workspace

import dev.toros.yde.workspace.layout.DefinitionScopes
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 定義体のスコープ（workspace.yaml の scopes。ADR-0032）: スコープは名前のある定義で、置き場所は中身を指定する書き方の 1 つ。
 * 生成は scope に書いたスコープの定義体だけを業務モデルとして読み、設計書独自のエンティティ型を業務モデルと取り違えない。
 * 使う向きは uses の一方向で、業務モデルから設計を使ったら誤り。
 */
class DefinitionScopesTest {
    private val config = """
        ledger: [tasks, design]
        scopes:
          - id: business
            name: 業務モデル
            include: [schemas/business/**]
          - id: design
            name: 設計
            include: [schemas/design/**, transforms/**]
            uses: [business]
    """.trimIndent()

    private val task = """
        id: task
        match: "tasks/*.yaml"
        entity: { type: task, defines: true }
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
            - { key: memo, type: string }
        actions:
          - id: note-task
            target: { entity: task }
            do: [{ set: { path: memo, value: x, of: self } }]
    """.trimIndent()

    // 設計書独自のエンティティ型（アクター）。業務モデルの task を参照してよい（design は business を使う）
    private val actor = """
        id: actor
        match: "design/actors.yaml"
        entity: { type: actor, defines: true }
        root:
          type: object
          fields:
            - key: actors
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: handles, type: ref, scope: { schemaIds: [task] } }
    """.trimIndent()

    private val generator = """
        id: mappers
        scope: business
        each: entities
        output: "design/{id}.yaml"
        build:
          mapper: { ${'$'}format: "{name}Mapper", ${'$'}with: { name: { ${'$'}case: { ${'$'}copy: id }, ${'$'}as: pascal } } }
    """.trimIndent()

    private fun session(extra: Map<String, String> = emptyMap(), workspace: String = config) = WorkspaceSession(
        InMemoryDocumentStore(
            mapOf(
                ".yde/workspace.yaml" to workspace,
                ".yde/schemas/business/task.yaml" to task,
                ".yde/schemas/design/actor.yaml" to actor,
                ".yde/transforms/mappers.yaml" to generator,
                "design/actors.yaml" to "actors:\n  - { id: user, handles: T-1 }\n",
            ) + extra,
        ),
    )

    @Test
    fun `生成は業務モデルのスコープだけを読み 設計書独自の型から設計書を作らない`() {
        val s = session()
        assertEquals(emptyList(), s.schemaErrors)
        assertEquals("business", s.layout.scopeOf(".yde/schemas/business/task.yaml")?.id)
        assertEquals("design", s.layout.scopeOf(".yde/schemas/design/actor.yaml")?.id)
        assertEquals(null, s.layout.scopeOf("tasks/T-1.yaml"), "台帳の文書はスコープに入らない")
        val plan = Generators.plan(s)
        assertEquals(emptyList(), plan.errors)
        assertEquals(listOf("design/task.yaml"), plan.created.map { it.value }, "actor の Mapper は作らない")
        // スコープを書かない生成（従来どおり）は、全部のスキーマから読む＝取り違える
        val all = session(mapOf(".yde/transforms/mappers.yaml" to generator.replace("scope: business\n", "")))
        assertEquals(listOf("design/task.yaml", "design/actor.yaml"), Generators.plan(all).created.map { it.value })
        val entities = (YamlIO.toAny(BusinessModel.of(s, "business")) as Map<*, *>)["entities"] as List<*>
        assertEquals(listOf("task"), entities.map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun `業務モデルから設計を使ったら誤り 逆向きはよい`() {
        val misuse = task.replace("- { key: memo, type: string }", "- { key: memo, type: string }\n    - { key: owner, type: ref, scope: { schemaIds: [actor] } }")
        val s = session(mapOf(".yde/schemas/business/task.yaml" to misuse))
        val errors = s.schemaErrors
        assertEquals(1, errors.size, errors.toString())
        assertTrue("business/task.yaml" in errors[0] && "スコープ「業務モデル」" in errors[0] && "スコープ「設計」のスキーマ「actor」" in errors[0], errors[0])
        // 型の名前（スキーマ#型）で使っても同じ
        val lib = """
            id: design-types
            kind: library
            definitions:
              アクター名: { type: string, maxLength: 20 }
        """.trimIndent()
        val byType = session(
            mapOf(
                ".yde/schemas/design/types.yaml" to lib,
                ".yde/schemas/business/task.yaml" to task.replace("- { key: memo, type: string }", "- { key: memo, type: design-types#アクター名 }"),
            ),
        )
        assertTrue(byType.schemaErrors.any { "スキーマ「design-types」" in it }, byType.schemaErrors.toString())
    }

    @Test
    fun `スコープの宣言の誤り`() {
        fun errorsOf(yaml: String) = DefinitionScopes.parse(YamlIO.parseFirst(yaml) as YNode.Map).errors
        assertTrue(errorsOf("scopes:\n  - { id: a, include: [schemas/a/**], uses: [b] }").any { "uses のスコープ「b」がありません" in it })
        assertTrue(errorsOf("scopes:\n  - { id: a, include: [docs/**] }").any { "定義の置き場の名前" in it })
        assertTrue(errorsOf("scopes:\n  - { id: a, include: [runs/**] }").any { "定義の置き場の名前" in it }, "記録の置き場は入れない")
        assertTrue(errorsOf("scopes:\n  - { id: a }").any { "include" in it })
        assertTrue(errorsOf("scopes:\n  - { id: a, include: [schemas/**], name: x, label: y }").any { "未知のキーです: label" in it })
        val loop = errorsOf("scopes:\n  - { id: a, include: [schemas/a/**], uses: [b] }\n  - { id: b, include: [schemas/b/**], uses: [a] }")
        assertEquals(1, loop.count { "たどって戻ります" in it }, loop.toString())
        // 2 つのスコープに入るスキーマ
        val overlap = session(workspace = config.replace("include: [schemas/design/**, transforms/**]", "include: [schemas/**, transforms/**]"))
        assertTrue(overlap.schemaErrors.any { "スキーマ「task」が 2 つのスコープ" in it }, overlap.schemaErrors.toString())
        // 生成の定義が無いスコープを読む
        val unknown = session(mapOf(".yde/transforms/mappers.yaml" to generator.replace("scope: business", "scope: nope")))
        assertTrue(Generators.plan(unknown).errors.any { "スコープ「nope」がありません" in it })
    }
}
