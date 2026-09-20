package validate

import core.Workspace
import schema.currentPhaseIndex
import java.nio.file.Path

/**
 * 準備完了レポート（R28-05b。ARCHITECTURE §4.32）: ワークスペース全文書の検証結果を
 * エンティティ単位に集約し、「準備完了（そのエンティティに帰属するエラーが0）」を機械判定する。
 *
 * - 指摘の帰属: 指摘のパスを含む最も内側（パスが最長）のID出現のエンティティ（sourceEntryOf と同じ規則）。
 *   どの出現にも含まれない指摘は文書レベル（unassigned）として別掲する
 * - 準備完了 = 帰属するエラー0。警告は判定に使わず件数で示す（意図的警告の運用を塞がないため）
 * - フェーズ = 定義出現のある文書の現在フェーズ（phases 宣言のあるスキーマのみ）
 * - INFO（他出現への案内等）は数えない（定義側の指摘と二重になるため）。エラー級の検査が他の出現へ出す同格の案内
 *   （配り方 NOTICE）も、報告先の指摘の写しなので数えない（P27 段A・意図的差分 D-26 ②）
 * - 保留 = 判定を保留した検査の件数（データの不備で、充足とも違反とも決められなかった検査。BACKLOG UX-113）。
 *   準備完了の判定には使わず、件数で示す——「エラー0」が「すべて確かめて問題なし」なのかを読み手が見分けられるようにする
 * - ヘッドレス経路（CLI）のディスク基準で検証する。決定性: 一覧はID昇順・集計はキー昇順
 */
object Readiness {

    data class EntityRow(
        val id: String,
        val entityType: String?,
        val title: String?,
        /** 定義出現のあるファイル（ワークスペース相対。定義がなければ先頭出現）。 */
        val file: String?,
        /** 定義文書の現在フェーズ（phases 宣言がなければ null）。 */
        val phase: String?,
        val errors: Int,
        val warnings: Int,
        /** 判定を保留した検査の件数。 */
        val withheld: Int = 0,
    ) {
        val ready: Boolean get() = errors == 0
    }

    data class FileRow(val file: String, val errors: Int, val warnings: Int)

    data class GroupRow(val key: String, val total: Int, val ready: Int)

    data class Report(
        val entities: List<EntityRow>,
        /** エンティティに帰属しない文書レベルの指摘があるファイル。 */
        val unassigned: List<FileRow>,
        val byType: List<GroupRow>,
        val byPhase: List<GroupRow>,
        /** 判定を保留した検査（ファイル → 内訳）。 */
        val withheld: List<Pair<String, WithheldCheck>> = emptyList(),
    )

    fun report(ws: Workspace): Report {
        val entriesByFile = ws.index.entries.values.flatten().groupBy { it.file }
        val errorsById = mutableMapOf<String, Int>()
        val warningsById = mutableMapOf<String, Int>()
        val unassigned = mutableListOf<FileRow>()
        val withheld = mutableListOf<Pair<String, WithheldCheck>>()

        for (file in ws.files) {
            val rel = ws.relPath(file)
            val result = ws.validateDetailed(rel)
            withheld += result.withheld.map { rel to it }
            val issues = result.issues.filter { it.severity != Severity.INFO && it.source?.delivery != IssueDelivery.NOTICE }
            if (issues.isEmpty()) continue
            val entries = entriesByFile[file].orEmpty()
            var docErrors = 0
            var docWarnings = 0
            for (issue in issues) {
                // 指摘のパスを含む最も内側のID出現へ帰属させる
                val owner = entries
                    .filter { it.path.size <= issue.path.size && issue.path.subList(0, it.path.size) == it.path }
                    .maxByOrNull { it.path.size }
                if (owner == null) {
                    if (issue.severity == Severity.ERROR) docErrors++ else docWarnings++
                } else if (issue.severity == Severity.ERROR) {
                    errorsById.merge(owner.id, 1, Int::plus)
                } else {
                    warningsById.merge(owner.id, 1, Int::plus)
                }
            }
            if (docErrors > 0 || docWarnings > 0) unassigned += FileRow(rel, docErrors, docWarnings)
        }

        // フェーズは定義文書ごとに1回だけ解決する
        val phaseByFile = mutableMapOf<Path, String?>()
        fun phaseOf(file: Path): String? = phaseByFile.getOrPut(file) {
            runCatching {
                val doc = ws.load(ws.relPath(file))
                val schema = doc.schema ?: return@getOrPut null
                val order = schema.phases?.order ?: return@getOrPut null
                schema.currentPhaseIndex(doc.root)?.let { order.getOrNull(it) }
            }.getOrNull()
        }

        val withheldById = withheld.groupingBy { it.second.subjectId }.eachCount()
        val entities = ws.index.graph.entities.values
            .sortedBy { it.id }
            .map { node ->
                val anchor = node.defining ?: node.occurrences.firstOrNull()
                EntityRow(
                    id = node.id,
                    entityType = node.entityType,
                    title = node.title,
                    file = anchor?.let { ws.relPath(it.file) },
                    phase = anchor?.let { phaseOf(it.file) },
                    errors = errorsById[node.id] ?: 0,
                    warnings = warningsById[node.id] ?: 0,
                    withheld = withheldById[node.id] ?: 0,
                )
            }

        fun groups(keyOf: (EntityRow) -> String?): List<GroupRow> = entities
            .groupBy { keyOf(it) ?: "-" }
            .toSortedMap()
            .map { (key, rows) -> GroupRow(key, rows.size, rows.count { it.ready }) }

        return Report(
            entities = entities,
            unassigned = unassigned.sortedBy { it.file },
            byType = groups { it.entityType },
            byPhase = groups { it.phase },
            withheld = withheld.sortedWith(compareBy({ it.first }, { it.second.subjectId })),
        )
    }
}
