package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「すべての要素について」の判定（検査の基本の書き方 forAll。SP-04。P29 Phase 6 第5段・意図的差分 D-25）のテスト。
 * 網羅マトリクス（スキーマの matrix）はこの書き方へ展開される語彙で、判定の内容は MatrixTest が従来どおり保証する。
 * ここでは、利用者が同じ書き方を自分の検査に使えること（組込みは特権を持たない）と、判定保留の規則を確かめる。
 * 例: 設定項目のそれぞれについて、すべての環境に設定値が書かれていること。
 */
class ForAllTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-for-all")
        write(
            "_schemas/config.yaml",
            """
            id: config
            match: "config.yaml"
            entity:
              - { type: setting, idPattern: "SET-*", defines: true }
              - { type: environment, idPattern: "ENV-*", defines: true }
              - { type: setting-value, idPattern: "VAL-*", defines: true }
            constraints:
              - id: value-for-every-environment
                description: 設定項目は、すべての環境に設定値を持つこと
                match:
                  - node: { entity: setting, bind: item }
                forAll:
                  node: { entity: environment, bind: env }
                  steps:
                    - edge: { dir: in, relation: value-of }
                    - node: { entity: setting-value }
                    - edge: { relation: for-environment }
                    - node: { same: env }
                message: "設定項目「{id}」（{item.title}）の設定値が無い環境があります（{count} 件）: {missing}"
                level: error
            root:
              type: object
              fields:
                - key: elements
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
                      - { key: setting, type: ref, relation: value-of }
                      - { key: env, type: ref, relation: for-environment }
            """,
        )
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

    @Test
    fun `もう1つの集まりのすべての要素について解があることを利用者のYAMLで検査できる`() {
        write(
            "config.yaml",
            """
            elements:
              - { id: SET-TIMEOUT, name: タイムアウト }
              - { id: SET-URL, name: 接続先 }
              - { id: ENV-DEV, name: 開発 }
              - { id: ENV-STG, name: 検証 }
              - { id: ENV-PRD, name: 本番 }
              - { id: VAL-1, setting: SET-TIMEOUT, env: ENV-DEV }
              - { id: VAL-2, setting: SET-TIMEOUT, env: ENV-STG }
              - { id: VAL-3, setting: SET-TIMEOUT, env: ENV-PRD }
              - { id: VAL-4, setting: SET-URL, env: ENV-DEV }
            """,
        )
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        val errors = ws.validate("config.yaml").filter { it.severity == Severity.ERROR }
        assertEquals(
            listOf("設定項目「SET-URL」（接続先）の設定値が無い環境があります（2 件）: ENV-PRD（本番）・ENV-STG（検証） — 設定項目は、すべての環境に設定値を持つこと"),
            errors.map { it.message },
        )
        assertEquals(listOf("ENV-PRD", "ENV-STG"), errors.single().relatedIds)
    }

    @Test
    fun `集まりが空なら判定せず相手の見つからない参照に出会ったセルは不足に数えない`() {
        // 環境が1つも無い: どの設定項目も指摘しない（判定保留）
        write("config.yaml", "elements:\n- { id: SET-URL, name: 接続先 }")
        assertTrue(Workspace.open(dir).validate("config.yaml").none { "設定値が無い環境" in it.message })

        // VAL-9 の環境の綴りが誤っている——それが本番を指していたかもしれないため、SET-URL の不足は指摘しない
        write(
            "config.yaml",
            """
            elements:
              - { id: SET-URL, name: 接続先 }
              - { id: ENV-DEV, name: 開発 }
              - { id: ENV-PRD, name: 本番 }
              - { id: VAL-1, setting: SET-URL, env: ENV-DEV }
              - { id: VAL-9, setting: SET-URL, env: ENV-PDR }
            """,
        )
        val ws = Workspace.open(dir)
        val issues = ws.validate("config.yaml")
        assertTrue(issues.none { "設定値が無い環境" in it.message }, issues.toString())
        assertTrue(issues.any { it.severity == Severity.ERROR && "ENV-PDR" in it.message }, issues.toString())
    }

    @Test
    fun `forAllの書き方の誤りは定義エラーになる`() {
        fun errorsWith(forAll: String, match: String = "      - node: { entity: setting, bind: item }"): List<String> {
            write(
                "_schemas/bad.yaml",
                "id: bad\nmatch: \"bad/*.yaml\"\nroot: { type: object, fields: [{ key: id, type: id }] }\nconstraints:\n" +
                    "  - match:\n$match\n    forAll:\n$forAll\n",
            )
            return Workspace.open(dir).schemaErrors.filter { "bad.yaml" in it }
        }
        val steps = "      steps:\n        - edge: { dir: in, relation: r }\n        - node: { same: env }"
        assertTrue(errorsWith("      node: { entity: environment }\n$steps").any { "node.bind（集まりの要素の束縛名）が必要です" in it })
        assertTrue(errorsWith("      node: { bind: env }\n$steps").any { "集まりを絞る必要があります" in it })
        assertTrue(
            errorsWith("      node: { entity: environment, bind: env }\n      steps:\n        - edge: { relation: r }\n        - node: { entity: x }")
                .any { "node: {same: env} として指す必要があります" in it },
        )
        assertTrue(
            errorsWith(
                "      node: { entity: environment, bind: env }\n$steps",
                match = "      - node: { entity: setting, bind: item }\n      - edge: { relation: r }\n      - node: { entity: x }",
            ).any { "match は、起点の node 1つだけにします" in it },
        )
    }
}
