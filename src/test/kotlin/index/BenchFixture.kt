package index

import java.nio.file.Files
import java.nio.file.Path

/**
 * 旧索引（IdIndex）増分テスト用のワークスペース生成・計測フィクスチャ。
 * 旧 core/Bench.kt（R8-02）を B-1（RB-01）の本体削除に伴いテスト側へ移設した——
 * 利用者向けの計測は新CLI（workspace bench）が担い、これは IdIndexIncrementalTest 専用（B-2 で本体と共に削除）。
 */
internal object Bench {

    /**
     * ベンチマーク用ワークスペースを生成する。
     * スキーマ1件＋文書 documents 件（各文書に itemsPerDocument 件の要素と前方文書への参照）。
     * 既存ファイルがあるディレクトリには生成しない（誤上書き防止）。
     */
    fun generate(dir: Path, documents: Int, itemsPerDocument: Int = 8): Int {
        require(documents > 0) { "documents は 1 以上を指定してください" }
        require(itemsPerDocument > 0) { "items は 1 以上を指定してください" }
        if (Files.exists(dir)) {
            require(Files.list(dir).use { it.findFirst().isEmpty }) { "空でないディレクトリには生成できません: $dir" }
        }
        Files.createDirectories(dir.resolve("_schemas"))
        Files.createDirectories(dir.resolve("docs"))
        Files.writeString(
            dir.resolve("_schemas/bench-item.yaml"),
            """
            id: bench-item
            name: ベンチマーク文書
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
              - key: id
                label: 文書ID
                type: id
                required: true
              - key: title
                label: タイトル
                type: string
              - key: items
                label: 要素一覧
                type: array
                item:
                  type: object
                  fields:
                  - key: id
                    label: 要素ID
                    type: id
                    required: true
                  - key: title
                    label: 名称
                    type: string
                  - key: depends_on
                    label: 依存先
                    type: array
                    item:
                      type: ref
            """.trimIndent() + "\n",
        )
        for (d in 1..documents) {
            val sb = StringBuilder()
            val docId = "DOC-%04d".format(d)
            sb.append("_document:\n  id: bench-$docId\n  name: ベンチマーク文書 $d\n")
            sb.append("id: $docId\ntitle: ベンチマーク文書 $d\nitems:\n")
            for (i in 1..itemsPerDocument) {
                val itemId = "ITEM-%04d-%02d".format(d, i)
                sb.append("  - id: $itemId\n    title: 要素 $d-$i\n")
                if (d > 1) {
                    // 前方の文書の要素へ参照を張る（参照解決・バックリンクの負荷を再現する）
                    val ref = "ITEM-%04d-%02d".format(d - 1, ((i - 1) % itemsPerDocument) + 1)
                    sb.append("    depends_on:\n      - $ref\n")
                }
            }
            Files.writeString(dir.resolve("docs/doc-%04d.yaml".format(d)), sb.toString())
        }
        return documents
    }

    data class Result(
        val documents: Int,
        val ids: Int,
        val refs: Int,
        val fullRebuildMillis: Double,
        val incrementalUpdateMillis: Double,
        /** 導出グラフ（EntityGraph。R20-04）の構築コスト。 */
        val graphDeriveMillis: Double = 0.0,
    ) {
        val speedup: Double get() = if (incrementalUpdateMillis > 0) fullRebuildMillis / incrementalUpdateMillis else 0.0
    }

    /**
     * 索引のフル再構築と 1 ファイル増分更新を計測する（各 iterations 回の最小値）。
     * 増分更新のコストが変更ファイル数に比例する（総文書数に比例しない）ことの確認に使う。
     */
    fun measure(dir: Path, iterations: Int = 3): Result {
        val ws = core.Workspace.open(dir)
        val target = ws.files.firstOrNull { !ws.relPath(it).startsWith("_") }
            ?: error("計測対象の文書がありません: $dir")
        fun best(block: () -> Unit): Double {
            var min = Long.MAX_VALUE
            repeat(iterations) {
                val start = System.nanoTime()
                block()
                min = minOf(min, System.nanoTime() - start)
            }
            return min / 1_000_000.0
        }
        // RB-09: 供給はセッション派生に一本化されたため、本番経路（refresh / noteDiskChanges）で計る
        val full = best { ws.refresh() }
        val incremental = best { ws.noteDiskChanges(listOf(target)) }
        // 導出グラフ（R20-04）: キャッシュを捨てて再導出するコストを計る
        val graphDerive = best {
            ws.index.dropGraphCache()
            ws.index.graph
        }
        return Result(
            documents = ws.files.size,
            ids = ws.index.entries.size,
            refs = ws.index.refs.size,
            fullRebuildMillis = full,
            incrementalUpdateMillis = incremental,
            graphDeriveMillis = graphDerive,
        )
    }
}
