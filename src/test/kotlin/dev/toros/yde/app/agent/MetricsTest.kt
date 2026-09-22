package dev.toros.yde.app.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 実行メトリクス（R4-01）のテスト。
 * `_runs` の実行記録（採否・自動適用・巻き戻しの追記を含む）だけから集計できることを検証する。
 */
class MetricsTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-metrics")
        Files.createDirectories(dir.resolve("_runs"))
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun writeRun(name: String, content: String) {
        Files.writeString(dir.resolve("_runs/$name"), content.trimIndent() + "\n")
    }

    @Test
    fun `aggregates runs per activity`() {
        // 提出→全採用
        writeRun(
            "20260719-100000-review.yaml",
            """
            activity: review
            status: SUBMITTED
            turns: 5
            usage: { inputTokens: 1000, outputTokens: 200 }
            validation: { errors: 0, warnings: 1 }
            adoption:
              applied: true
              adoptedFiles: [docs/a.yaml, docs/b.yaml]
              rejectedFiles: []
            """,
        )
        // 提出→部分採用（1ファイル却下）
        writeRun(
            "20260719-110000-review.yaml",
            """
            activity: review
            status: SUBMITTED
            turns: 7
            usage: { inputTokens: 2000, outputTokens: 400 }
            adoption:
              applied: true
              adoptedFiles: [docs/a.yaml]
              rejectedFiles: [docs/b.yaml]
            """,
        )
        // 失敗（上限到達）。キャッシュ読取（R19-01）の報告あり
        writeRun(
            "20260719-120000-review.yaml",
            """
            activity: review
            status: LIMIT
            turns: 24
            usage: { inputTokens: 9000, outputTokens: 900, cachedInputTokens: 800 }
            """,
        )
        // 別アクティビティ: 自動適用→巻き戻し
        writeRun(
            "20260719-130000-fixer.yaml",
            """
            activity: fixer
            status: SUBMITTED
            turns: 3
            usage: { inputTokens: 500, outputTokens: 100 }
            autoApply: { appliedAt: "2026-07-19T13:00:00+09:00" }
            reverted: { revertedAt: "2026-07-19T14:00:00+09:00" }
            """,
        )
        // 付随ファイルは集計対象外
        writeRun("20260719-110000-review-changeset.yaml", "changes: []")
        writeRun("eval-20260719-150000-review-eval.yaml", "eval: review-eval\nactivity: review\nvariants: []")

        val summaries = RunMetrics.summarize(RunMetrics.collect(dir))
        assertEquals(listOf("fixer", "review"), summaries.map { it.activityId })

        val review = summaries.first { it.activityId == "review" }
        assertEquals(3, review.runs)
        assertEquals(2, review.submitted)
        assertEquals(1, review.failed)
        assertEquals(0.667, review.submitRate)
        assertEquals(2, review.decided)
        assertEquals(2, review.adopted)
        assertEquals(1.0, review.adoptionRate)
        assertEquals(3, review.adoptedFiles)
        assertEquals(1, review.rejectedFiles)
        assertEquals(0.25, review.rejectedFileRate)  // 却下1 / (採用3+却下1)
        assertEquals(12_000L, review.inputTokens)
        assertEquals(1_500L, review.outputTokens)
        assertEquals(800L, review.cachedInputTokens)   // 報告のない実行は0として合算
        assertEquals(12.0, review.avgTurns)

        val fixer = summaries.first { it.activityId == "fixer" }
        assertEquals(1, fixer.autoApplied)
        assertEquals(1, fixer.reverted)
        assertEquals(1.0, fixer.revertRate)
    }

    @Test
    fun `empty or missing runs dir yields empty metrics`() {
        assertTrue(RunMetrics.collect(dir.resolve("nowhere")).isEmpty())
        assertTrue(RunMetrics.summarize(emptyList()).isEmpty())
    }
}
