package core

import validate.Readiness
import validate.Severity
import java.nio.file.Files
import java.nio.file.Path

/**
 * 規模の計測（ROADMAP P27。BACKLOG UX-116）: 大きさを指定して合成ワークスペースを作り、開く・全体を検証する・
 * 準備完了レポートを作る、の時間を測る。目的は、規模が大きくなったときの時間の**伸び方**（文書数に比例か、それ以上か）を、
 * 検査の実行の作りを変える（P27 段B）前後で比べられるようにすること。
 *
 * 合成ワークスペースは「モジュール」の繰り返しで、1モジュールは次を持つ（システム開発の設計情報の形を小さく写したもの）:
 * ユースケース一覧・画面一覧（定義）と画面ごとの詳細文書（入れ子の画面項目つき）・画面遷移・処理（呼び出しの連鎖）・テスト項目。
 * 検査の語彙をひととおり使う——関係の多重度・対象型・非循環、詳細文書の存在（coverage）、多段の存在の検査（exists）、
 * 値の比較（check）、網羅マトリクス（matrix）、遷移検証（flow）。生成される内容は決定的で、検証エラーは0になる。
 */
object Bench {

    const val USECASES = 6
    const val ITEMS_PER_SCREEN = 5
    const val VIEWPOINTS = 3
    const val DATA_TYPES = 10

    data class Size(val modules: Int, val files: Int, val lines: Int, val entities: Int)

    data class Result(
        val size: Size,
        val openMs: Long,
        val validateMs: Long,
        val readinessMs: Long,
        val errors: Int,
        val warnings: Int,
    )

    /** 合成ワークスペースを dir に作る（dir は空であること）。 */
    fun generate(dir: Path, modules: Int): Size {
        require(modules >= 1) { "モジュールの数は1以上で指定してください: $modules" }
        var files = 0
        var lines = 0
        fun write(rel: String, content: String) {
            val file = dir.resolve(rel)
            Files.createDirectories(file.parent)
            val text = content.trimIndent() + "\n"
            Files.writeString(file, text)
            files++
            lines += text.count { it == '\n' }
        }
        schemas().forEach { (rel, content) -> write(rel, content) }
        write(
            "common/viewpoints.yaml",
            "viewpoints:\n" + (1..VIEWPOINTS).joinToString("\n") { "  - { id: VP-$it, name: 観点$it }" },
        )
        write(
            "common/data-types.yaml",
            "types:\n" + (1..DATA_TYPES).joinToString("\n") { "  - { id: DT-$it, name: 型$it, max_length: ${it * 20} }" },
        )
        for (m in 1..modules) {
            val mm = "m%03d".format(m)
            fun uc(i: Int) = "UC-$m-$i"
            fun scr(i: Int) = "SCR-$m-$i"
            fun proc(i: Int) = "PROC-$m-$i"
            write(
                "$mm/usecases.yaml",
                "usecases:\n" + (1..USECASES).joinToString("\n") { "  - { id: ${uc(it)}, name: ユースケース$m-$it }" },
            )
            write(
                "$mm/screen-list.yaml",
                "screens:\n" + (1..USECASES).joinToString("\n") { "  - { id: ${scr(it)}, name: 画面$m-$it, realizes: [${uc(it)}] }" },
            )
            for (i in 1..USECASES) {
                val items = (1..ITEMS_PER_SCREEN).joinToString("\n") { j ->
                    val dt = (i + j) % DATA_TYPES + 1
                    "  - { id: SI-$m-$i-$j, name: 項目$j, dtype: DT-$dt, max_length: ${dt * 10} }"
                }
                write("$mm/screens/${scr(i)}.yaml", "id: ${scr(i)}\nlayout: 標準\nitems:\n$items")
            }
            // 遷移: 1 → 2 → … → 末尾（終端）と、途中から先頭へ戻る線
            val transitions = (1 until USECASES).joinToString("\n") { "  - { from: ${scr(it)}, to: ${scr(it + 1)}, trigger: 次へ }" } +
                "\n  - { from: ${scr(3)}, to: ${scr(1)}, trigger: 戻る }"
            write("$mm/transitions.yaml", "start: [${scr(1)}]\nterminal: [${scr(USECASES)}]\ntransitions:\n$transitions")
            for (i in 1..USECASES) {
                // 呼び出しは「同じモジュールの次の処理」と「1つ前のモジュールの先頭の処理」へ（循環しない）
                val calls = listOfNotNull(
                    if (i < USECASES) proc(i + 1) else null,
                    if (m > 1 && i == 1) "PROC-${m - 1}-1" else null,
                )
                write(
                    "$mm/processes/${proc(i)}.yaml",
                    "id: ${proc(i)}\nname: 処理$m-$i\nimplements: [${uc(i)}]\ncalls: [${calls.joinToString(", ")}]",
                )
            }
            val tests = (1..USECASES).flatMap { i -> (1..VIEWPOINTS).map { v -> i to v } }.joinToString("\n") { (i, v) ->
                "  - { id: TI-$m-$i-$v, name: テスト$i-$v, verifies: ${proc(i)}, viewpoint: VP-$v }"
            }
            write("$mm/tests.yaml", "items:\n$tests")
        }
        val entities = VIEWPOINTS + DATA_TYPES + modules * (USECASES * 3 + USECASES * ITEMS_PER_SCREEN + USECASES * VIEWPOINTS)
        return Size(modules, files, lines, entities)
    }

    /** 開く・全体を検証する・準備完了レポートを作る、の時間を測る。 */
    fun measure(dir: Path, size: Size): Result {
        val t0 = System.nanoTime()
        val ws = Workspace.open(dir)
        val t1 = System.nanoTime()
        require(ws.schemaErrors.isEmpty()) { "合成ワークスペースのスキーマにエラーがあります: ${ws.schemaErrors}" }
        var errors = 0
        var warnings = 0
        for (file in ws.files) {
            val issues = ws.validate(ws.relPath(file))
            errors += issues.count { it.severity == Severity.ERROR }
            warnings += issues.count { it.severity == Severity.WARNING }
        }
        val t2 = System.nanoTime()
        Readiness.report(ws)
        val t3 = System.nanoTime()
        return Result(size, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, errors, warnings)
    }

    private fun schemas(): List<Pair<String, String>> = listOf(
        "_schemas/relations.yaml" to """
            id: bench-relations
            kind: relations
            enforcement: { undeclaredRelation: error, definingUniqueness: error }
            entities:
              - { type: usecase, label: ユースケース }
              - { type: screen, label: 画面 }
              - { type: screen-item, label: 画面項目 }
              - { type: process, label: 処理 }
              - { type: test-item, label: テスト項目 }
              - { type: viewpoint, label: 試験観点 }
              - { type: data-type, label: データ型 }
            relations:
              - { name: realizes, label: 実現する, source: [screen], target: [usecase], cardinality: { outMin: 1, inMin: 1 } }
              - { name: implements, label: 実装する, source: [process], target: [usecase], cardinality: { outMin: 1 } }
              - { name: calls, label: 呼び出す, source: [process], target: [process], acyclic: true }
              - { name: verifies, label: 検証する, source: [test-item], target: [process], cardinality: { outMin: 1, outMax: 1 } }
              - { name: viewpoint, label: 試験観点, source: [test-item], target: [viewpoint], cardinality: { outMin: 1, outMax: 1 } }
              - { name: dtype, label: データ型, source: [screen-item], target: [data-type], cardinality: { outMax: 1 } }
              - { name: t-from, label: 遷移元, target: [screen] }
              - { name: t-to, label: 遷移先, target: [screen] }
              - { name: flow-start, label: 開始画面, target: [screen] }
              - { name: flow-terminal, label: 終端画面, target: [screen] }
        """,
        "_schemas/usecases.yaml" to """
            id: usecases
            match: "*/usecases.yaml"
            entity: { type: usecase, idPattern: "UC-*", defines: true }
            constraints:
              - id: uc-tested
                description: 全ユースケースは、実装する処理を経て、テスト項目まで到達していること
                match:
                  - node: { entity: usecase, bind: uc }
                  - edge: { relation: implements, dir: in }
                  - node: { entity: process }
                  - edge: { relation: verifies, dir: in }
                  - node: { entity: test-item }
                expect: exists
                level: error
            root:
              type: object
              fields:
                - key: usecases
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
        """,
        "_schemas/screen-list.yaml" to """
            id: screen-list
            match: "*/screen-list.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            coverage:
              - description: 全画面は画面の詳細文書を持つこと
                detailIn: { schemaIds: [screen-detail] }
                level: error
            flow:
              - id: screen-flow
                description: 全画面は開始画面から到達でき、行き止まりの画面は終端として宣言されていること
                nodes: { entity: screen }
                edges: { from: { relation: t-from }, to: { relation: t-to } }
                start: { relation: flow-start }
                terminal: { relation: flow-terminal }
                level: error
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
                      - key: realizes
                        type: array
                        item: { type: ref, relation: realizes, idPattern: "UC-*" }
        """,
        "_schemas/screen-detail.yaml" to """
            id: screen-detail
            match: "*/screens/*.yaml"
            entity:
              - { type: screen, idPattern: "SCR-*" }
              - { type: screen-item, idPattern: "SI-*", defines: true }
            constraints:
              - id: item-length-within-dtype
                description: 画面項目の最大文字数は、データ型の最大文字数以内であること
                match:
                  - node: { entity: screen-item, bind: item }
                  - edge: { relation: dtype }
                  - node: { entity: data-type, bind: t }
                check:
                  - lte: [{ of: item, path: max_length }, { of: t, path: max_length }]
                level: error
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: layout, type: string }
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
                      - { key: dtype, type: ref, relation: dtype, idPattern: "DT-*" }
                      - { key: max_length, type: int }
        """,
        "_schemas/transitions.yaml" to """
            id: transitions
            match: "*/transitions.yaml"
            root:
              type: object
              fields:
                - key: start
                  type: array
                  item: { type: ref, relation: flow-start, idPattern: "SCR-*" }
                - key: terminal
                  type: array
                  item: { type: ref, relation: flow-terminal, idPattern: "SCR-*" }
                - key: transitions
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: from, type: ref, relation: t-from, idPattern: "SCR-*" }
                      - { key: to, type: ref, relation: t-to, idPattern: "SCR-*" }
                      - { key: trigger, type: string }
        """,
        "_schemas/process.yaml" to """
            id: process
            match: "*/processes/*.yaml"
            entity: { type: process, idPattern: "PROC-*", defines: true }
            matrix:
              - id: proc-viewpoint-matrix
                description: 各処理は全試験観点のテスト項目を持つこと
                rows: { entity: process }
                columns: { entity: viewpoint }
                witness:
                  node: { entity: test-item }
                  row: { relation: verifies }
                  column: { relation: viewpoint }
                level: error
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - key: implements
                  type: array
                  item: { type: ref, relation: implements, idPattern: "UC-*" }
                - key: calls
                  type: array
                  item: { type: ref, relation: calls, idPattern: "PROC-*" }
        """,
        "_schemas/tests.yaml" to """
            id: tests
            match: "*/tests.yaml"
            entity: { type: test-item, idPattern: "TI-*", defines: true }
            root:
              type: object
              fields:
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
                      - { key: verifies, type: ref, relation: verifies, idPattern: "PROC-*" }
                      - { key: viewpoint, type: ref, relation: viewpoint, idPattern: "VP-*" }
        """,
        "_schemas/viewpoints.yaml" to """
            id: viewpoints
            match: "common/viewpoints.yaml"
            entity: { type: viewpoint, idPattern: "VP-*", defines: true }
            root:
              type: object
              fields:
                - key: viewpoints
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
        """,
        "_schemas/data-types.yaml" to """
            id: data-types
            match: "common/data-types.yaml"
            entity: { type: data-type, idPattern: "DT-*", defines: true }
            root:
              type: object
              fields:
                - key: types
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
                      - { key: max_length, type: int }
        """,
    )
}
