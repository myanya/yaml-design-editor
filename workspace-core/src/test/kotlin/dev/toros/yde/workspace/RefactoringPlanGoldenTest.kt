package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.RefactoringPlan
import dev.toros.yde.semantic.RefactoringRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * リファクタリング計画のゴールデンテスト（P29 Phase 4・R29-07 の安全網）。
 * 全操作（エンティティ4種＋スキーマ12種）と代表的な拒否ケースについて、計画の全内容
 * （文書ごとの操作列とその**順序**・警告・サマリ・物理移動）または拒否メッセージを正準テキストへ写し、
 * 保存済みの期待値と突き合わせる。個別テスト（RefactoringTest）は適用結果の文書を見るため、
 * 「計画生成の内部構造を変えても計画が1文字も変わらない」ことはこのテストが固定する。
 * 期待値の更新は意図した挙動変更のときだけ: `-DupdateGolden=true` を付けて実行し、差分をレビューする
 * （既存挙動の変更は実装より先に INTENTIONAL-DIFFS へ登録する規約）。
 */
class RefactoringPlanGoldenTest {

    private fun store() = InMemoryDocumentStore(
        mapOf(
            "_schemas/lib.yaml" to """
                id: fx-lib
                kind: library
                definitions:
                  money:
                    type: object
                    fields:
                      - key: amount
                        type: string
                      - key: currency
                        type: enum
                        values: [JPY, USD]
                        default: JPY
            """.trimIndent(),
            "_schemas/rel.yaml" to """
                id: fx-rel
                kind: relations
                relations:
                  - name: belongs
                    from: item
                    to: item
                  - name: blocks
                    from: item
                    to: item
            """.trimIndent(),
            "_schemas/item.yaml" to """
                id: fx-item
                match: "items/**/*.yaml"
                entity:
                  type: item
                  idPattern: "IT-*"
                  defines: true
                  owns: [memo, status]
                phases:
                  key: status
                  order: [draft, review, approved]
                coverage:
                  - detailIn:
                      schemaIds: [fx-detail]
                root:
                  type: object
                  fields:
                    - key: id
                      type: id
                    - key: status
                      type: enum
                      values: [draft, review, approved]
                      default: draft
                      cases:
                        review:
                          - key: reviewer
                            type: string
                    - key: memo
                      type: string
                    - key: score
                      type: string
                    - key: tag
                      type: string
                    - key: labels
                      type: array
                      item:
                        type: string
                    - key: parent
                      type: ref
                      relation: belongs
                    - key: price
                      ${'$'}ref: fx-lib#money
                    - key: details
                      type: object
                      fields:
                        - key: note
                          type: string
                          requiredFrom: review
                    - key: tasks
                      type: array
                      item:
                        type: object
                        fields:
                          - key: name
                            type: string
                          - key: cost
                            ${'$'}ref: fx-lib#money
                          - key: sub
                            type: object
                            fields:
                              - key: hint
                                type: string
            """.trimIndent(),
            "_schemas/detail.yaml" to """
                id: fx-detail
                match: "details/**/*.yaml"
                entity:
                  type: item
                  idPattern: "IT-*"
                root:
                  type: object
                  fields:
                    - key: id
                      type: id
                    - key: budget
                      ${'$'}ref: fx-lib#money
            """.trimIndent(),
            "items/a.yaml" to """
                id: IT-001
                status: review
                reviewer: 佐藤
                memo: ""
                score: "10"
                labels: [x]
                price:
                  amount: "100"
                  currency: JPY
                tasks:
                  - name: t1
                    cost:
                      amount: "5"
                      currency: USD
                    sub:
                      hint: h
                  - name: t2
                  - cost:
                      amount: "1"
                details:
                  note: n
            """.trimIndent() + "\n",
            "items/b.yaml" to "id: IT-002\nstatus: draft\nparent: IT-001\ntag: z\nunknown: 1\nmemo: x\n",
            "items/c.yaml" to """
                id: IT-003
                status: approved
                parent: IT-001
                score: "7"
                labels: []
                tasks:
                  - cost:
                      currency: JPY
                    name: t3
                details: {}
            """.trimIndent() + "\n",
            "details/IT-001.yaml" to "id: IT-001\nbudget:\n  amount: \"9\"\n  currency: JPY\n",
        )
    )

    private val requests: List<RefactoringRequest> = listOf(
        // エンティティ操作
        RefactoringRequest.RenameEntity("IT-001", "IT-100"),
        RefactoringRequest.RenameEntity("IT-001", "IT-002"),
        RefactoringRequest.MergeEntity("IT-002", "IT-003", deleteOccurrences = false),
        RefactoringRequest.MergeEntity("IT-002", "IT-003", deleteOccurrences = true),
        RefactoringRequest.DeleteEntity("IT-003"),
        RefactoringRequest.DeleteEntity("IT-001"),
        RefactoringRequest.MoveDocument(DocumentId("items/b.yaml"), DocumentId("items/sub/b.yaml")),
        // フィールド改名（単純・共有定義・配列内・フェーズキー・拒否2種）
        RefactoringRequest.RenameField("fx-item", "memo", "note2"),
        RefactoringRequest.RenameField("fx-item", "price.amount", "value"),
        RefactoringRequest.RenameField("fx-item", "tasks.name", "title"),
        RefactoringRequest.RenameField("fx-item", "status", "state"),
        RefactoringRequest.RenameField("fx-item", "tag", "memo"),
        RefactoringRequest.RenameField("fx-item", "tag", "unknown"),
        // フィールド移動（中間生成・空親の削除・配列要素内・拒否）
        RefactoringRequest.MoveField("fx-item", "memo", "details"),
        RefactoringRequest.MoveField("fx-item", "details.note", "."),
        RefactoringRequest.MoveField("fx-item", "tasks.sub.hint", "tasks"),
        RefactoringRequest.MoveField("fx-item", "tasks.sub.hint", "tasks", deleteEmptyParents = false),
        RefactoringRequest.MoveField("fx-item", "memo", "tasks"),
        RefactoringRequest.MoveField("fx-item", "status", "details"),
        // 整列
        RefactoringRequest.AlignOrder("fx-item"),
        RefactoringRequest.AlignOrder("fx-lib"),
        // enum 選択肢の改名（フェーズ追随・共有定義）
        RefactoringRequest.RenameEnumValue("fx-item", "status", "review", "checking"),
        RefactoringRequest.RenameEnumValue("fx-item", "price.currency", "JPY", "YEN"),
        RefactoringRequest.RenameEnumValue("fx-item", "status", "review", "draft"),
        // フィールド削除
        RefactoringRequest.DeleteField("fx-item", "memo"),
        RefactoringRequest.DeleteField("fx-item", "tasks.cost"),
        RefactoringRequest.DeleteField("fx-item", "id"),
        RefactoringRequest.DeleteField("fx-item", "status"),
        // 必須化バックフィル（常時・フェーズ以降・配列要素内・拒否）
        RefactoringRequest.RequireField("fx-item", "memo", default = "未記入"),
        RefactoringRequest.RequireField("fx-item", "details.note", default = "-", fromPhase = "review"),
        RefactoringRequest.RequireField("fx-item", "tasks.name", default = "無名"),
        RefactoringRequest.RequireField("fx-item", "price.currency", default = "EUR"),
        // 型変換
        RefactoringRequest.ConvertFieldType("fx-item", "score", "int"),
        RefactoringRequest.ConvertFieldType("fx-item", "score", "array"),
        RefactoringRequest.ConvertFieldType("fx-item", "labels", "string"),
        RefactoringRequest.ConvertFieldType("fx-item", "tag", "enum", listOf("z")),
        RefactoringRequest.ConvertFieldType("fx-item", "tag", "enum", listOf("q")),
        RefactoringRequest.ConvertFieldType("fx-item", "status", "string"),
        RefactoringRequest.ConvertFieldType("fx-item", "price", "string"),
        // 定義・関係・スキーマID
        RefactoringRequest.RenameDefinition("fx-lib", "money", "cash"),
        RefactoringRequest.ExtractDefinition("fx-item", "score", "scoreDef"),
        RefactoringRequest.InlineDefinition("fx-item", "price"),
        RefactoringRequest.InlineDefinition("fx-item", "score"),
        RefactoringRequest.RenameRelation("belongs", "linked"),
        RefactoringRequest.RenameRelation("belongs", "blocks"),
        RefactoringRequest.RenameRelation("nothing", "linked"),
        RefactoringRequest.RenameSchemaId("fx-detail", "fx-spec"),
        RefactoringRequest.RenameSchemaId("fx-lib", "fx-common"),
        RefactoringRequest.RenameSchemaId("fx-item", "fx-lib"),
    )

    private fun nodeText(node: YNode): String = YamlIO.dumpPlain(node).trimEnd().replace("\n", "⏎")

    private fun opText(op: YamlOperation): String = when (op) {
        is YamlOperation.SetScalar -> "SetScalar ${op.path.pretty()} = ${op.value}（${op.value?.let { it::class.simpleName } ?: "null"}）"
        is YamlOperation.Replace -> "Replace ${op.path.pretty()} = ${nodeText(op.value)}"
        is YamlOperation.Insert -> "Insert ${op.path.pretty()} = ${nodeText(op.value)}"
        is YamlOperation.Remove -> "Remove ${op.path.pretty()}"
        is YamlOperation.Move -> "Move ${op.from.pretty()} -> ${op.to.pretty()}"
        is YamlOperation.RenameKey -> "RenameKey ${op.path.pretty()} -> ${op.newName}"
        is YamlOperation.ReorderKeys -> "ReorderKeys ${op.path.pretty()} ${op.order}"
    }

    private fun planText(plan: RefactoringPlan): String = buildString {
        plan.moves.forEach { (from, to) -> appendLine("  move: ${from.value} -> ${to.value}") }
        plan.changes.forEach { change ->
            appendLine("  change: ${change.document.value}")
            change.operations.forEach { appendLine("    ${opText(it)}") }
        }
        plan.warnings.forEach { appendLine("  warning: $it") }
        plan.summary.forEach { appendLine("  summary: $it") }
    }

    private fun render(): String = buildString {
        for (request in requests) {
            appendLine("### $request")
            // plan は workspace を変えない（RefactoringTest が固定）ため、要求ごとに新しいセッションで独立に計画する
            val session = WorkspaceSession(store()) { Instant.EPOCH }
            val outcome = runCatching { session.planRefactoring(request) }
            outcome.fold(
                onSuccess = { append(planText(it)) },
                onFailure = { appendLine("  rejected（${it::class.simpleName}）: ${it.message}") },
            )
            appendLine()
        }
    }

    @Test
    fun `全操作の計画は保存済みの期待値と一致する`() {
        val actual = render().replace("\r\n", "\n")
        val resource = "refactoring-plans.golden.txt"
        assertGolden(resource, actual)
    }

    @Test
    fun `フローの追随と投影の要確認箇所を含む計画が期待値と一致する`() {
        val requests = listOf(
            RefactoringRequest.RenameEntity("diagram", "renamed"),
            RefactoringRequest.RenameEntity("ACT-1", "ACT-9"),
            RefactoringRequest.RenameField("process", "activities.title", "name"),
            RefactoringRequest.RenameField("process", "activities", "steps"),
            RefactoringRequest.RenameRelation("next", "follows"),
            RefactoringRequest.RenameSchemaId("process", "procedure"),
            RefactoringRequest.MoveDocument(DocumentId("flows/a.yaml"), DocumentId("elsewhere/a.yaml")),
        )
        val actual = buildString {
            for (request in requests) {
                appendLine("### $request")
                append(planText(FlowRefactoringFixture.session(extraRule = true).planRefactoring(request)))
                appendLine()
            }
        }
        assertGolden("flow-refactoring-plans.golden.txt", actual.replace("\r\n", "\n"))
    }

    private fun assertGolden(resource: String, actual: String) {
        if (System.getProperty("updateGolden") == "true") {
            val target = Path.of("src/test/resources", resource)
            Files.createDirectories(target.parent)
            Files.writeString(target, actual)
            return
        }
        val expected = javaClass.classLoader.getResourceAsStream(resource)
            ?.use { it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n") }
            ?: error("期待値がありません: src/test/resources/$resource（-DupdateGolden=true で生成してください）")
        assertEquals(expected, actual, "リファクタリング計画が期待値と異なります（意図した変更なら -DupdateGolden=true で更新）")
    }
}
