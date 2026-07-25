package skill

import core.ViewCell
import core.Views
import core.Workspace
import model.YNode
import model.parseYPath
import model.getAt
import schema.globToRegex
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * エクスポート（R5-03）: 対象文書（glob）を行×列に展開し、xlsx / csv / markdown / json / html の派生成果物を生成する。
 * `source.view` 指定時は `_views` のビュー定義を行×列の源にする（R9-05。「ビューで見えている表をそのまま帳票へ」）。
 * 出力は原本からいつでも再生成できる派生物であり、YAMLへの出力は定義段階で拒否される（原本主義）。
 */
object Exporter {

    data class Result(val file: Path, val rel: String, val rows: Int)

    fun run(ws: Workspace, skill: SkillDef): Result {
        val def = skill.export ?: error("skill「${skill.id}」は export ではありません")
        def.viewId?.let { return runFromView(ws, def, it) }
        val regex = globToRegex(def.match ?: error("source.match が必要です"))
        val files = ws.files.filter { regex.matches(ws.relPath(it)) }.sortedBy { ws.relPath(it) }
        if (files.isEmpty()) error("source.match「${def.match}」に一致する文書がありません")

        val rowsPath = parseYPath(def.rowsPath)
        val rows = mutableListOf<List<Any?>>()
        files.forEach { file ->
            val root = runCatching { YamlIO.parse(Files.readString(file)) }.getOrNull() ?: return@forEach
            val rowNodes: List<YNode> =
                if (def.rowsPath.isBlank()) listOf(root)
                else (getAt(root, rowsPath) as? YNode.Seq)?.items ?: emptyList()
            rowNodes.forEach { node ->
                rows += def.columns.map { column -> cellValue(node, column) }
            }
        }
        val table = Table(def.columns.map { it.header }, rows)
        val target = ws.resolve(def.outputFile)
        Tables.write(table, target, TableFormat.fromExtension(def.outputFile), def.sheet)
        return Result(target, def.outputFile, rows.size)
    }

    private fun cellValue(row: YNode, column: ExportColumn): Any? =
        when (val node = getAt(row, parseYPath(column.path))) {
            null -> null
            is YNode.Scalar -> (node.value as? String)?.trimEnd() ?: node.value
            is YNode.Seq -> node.items.joinToString(column.join) { item ->
                // 派生成果物にはコメントを出力しない（R8-01）
                (item as? YNode.Scalar)?.value?.toString() ?: YamlIO.dumpPlain(item).trim()
            }
            is YNode.Map -> YamlIO.dumpPlain(node).trim().lineSequence().joinToString(" ")
        }

    // ---------------------------------------------------------------- ビュー参照エクスポート（R9-05）

    /** ビュー定義（`_views`）を解決し、そのままの行×列を表として書き出す。 */
    private fun runFromView(ws: Workspace, def: ExportDef, viewId: String): Result {
        val loaded = Views.load(ws.root)
        val view = loaded.view(viewId)
            ?: error("ビュー「$viewId」が見つかりません" +
                (loaded.errors.takeIf { it.isNotEmpty() }?.let { "（定義エラー: ${it.joinToString(" / ")}）" } ?: ""))
        val rows = Views.resolve(view, ws.root, ws.index)
        val headers = listOf("id") + view.columns.map { it.label }
        val table = Table(headers, rows.map { row -> listOf<Any?>(row.id) + row.cells.map(::viewCellValue) })
        val target = ws.resolve(def.outputFile)
        Tables.write(table, target, TableFormat.fromExtension(def.outputFile), def.sheet)
        return Result(target, def.outputFile, rows.size)
    }

    /** ビューのセル値を表のセルへ落とす（バックリンク列は参照元IDの列挙）。 */
    private fun viewCellValue(cell: ViewCell): Any? {
        cell.backlinks?.let { backlinks ->
            return backlinks.joinToString(", ") { it.sourceId ?: it.rel }.takeIf { it.isNotBlank() }
        }
        return when (val node = cell.value) {
            null -> null
            is YNode.Scalar -> (node.value as? String)?.trimEnd() ?: node.value
            is YNode.Seq -> node.items.joinToString(", ") { item ->
                (item as? YNode.Scalar)?.value?.toString() ?: YamlIO.dumpPlain(item).trim()
            }
            is YNode.Map -> YamlIO.dumpPlain(node).trim().lineSequence().joinToString(" ")
        }
    }
}
