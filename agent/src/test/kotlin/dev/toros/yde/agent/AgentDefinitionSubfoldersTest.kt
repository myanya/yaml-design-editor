package dev.toros.yde.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 定義ゾーンの下のフォルダ: エージェント・アクティビティ・評価の定義を、ゾーンの下のフォルダに置いても読む。
 * ID はゾーン全体で 1 つで、別のフォルダに同じ ID があれば誤り（ゾーンから見た場所を示す）。
 */
class AgentDefinitionSubfoldersTest {
    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-agent-subfolders")
        write(".yde/workspace.yaml", "ledger: [docs]")
        write(".yde/agents/team/reviewer.yaml", "id: reviewer\npersona: 慎重に。")
        write(".yde/activities/review/check.yaml", """
            id: check
            agent: reviewer
            target: { match: "docs/*.yaml" }
            instruction: 確かめる。
        """)
        write(".yde/evals/review/check-eval.yaml", """
            id: check-eval
            activity: check
            cases:
              - id: c1
                expect: { status: submitted }
        """)
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `下のフォルダの定義も読む`() {
        val defs = Definitions.load(ws)
        assertEquals(emptyList(), defs.errors)
        assertEquals(listOf("reviewer"), defs.agents.map { it.id })
        assertEquals(listOf("check"), defs.activities.map { it.id })
        val evals = Evals.load(ws)
        assertEquals(emptyList(), evals.errors)
        assertEquals(listOf("check-eval"), evals.evals.map { it.id })
    }

    @Test
    fun `別のフォルダに同じ ID があれば誤り`() {
        write(".yde/agents/other/reviewer.yaml", "id: reviewer\npersona: 別の人。")
        write(".yde/evals/other/check-eval.yaml", "id: check-eval\nactivity: check\ncases:\n  - id: c1\n    expect: { status: submitted }")
        val defs = Definitions.load(ws)
        assertTrue(defs.errors.any { "エージェントID「reviewer」が重複しています" in it && "team/reviewer.yaml" in it && "other/reviewer.yaml" in it }, defs.errors.toString())
        val evals = Evals.load(ws)
        assertTrue(evals.errors.any { "評価ID「check-eval」が重複しています" in it }, evals.errors.toString())
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }
}
