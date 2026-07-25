package skill

import core.Workspace
import model.YNode
import model.parseYPath
import model.getAt
import schema.globToRegex
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * エクスポート（R5-03）: 対象文書（glob）を行×列に展開し、xlsx / csv / markdown の派生成果物を生成する。
 * 出力は原本からいつでも再生成できる派生物であり、YAMLへの出力は定義段階で拒否される（原本主義）。
 */
object Exporter {

    data class Result(val file: Path, val rel: String, val rows: Int)

    fun run(ws: Workspace, skill: SkillDef): Result {
        val def = skill.export ?: error("skill「${skill.id}」は export ではありません")
        val regex = globToRegex(def.match)
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
}
