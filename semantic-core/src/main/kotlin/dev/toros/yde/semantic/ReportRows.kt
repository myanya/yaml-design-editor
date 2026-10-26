package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.YNode

/**
 * レポートの `$view`（R30-04）: 解決したビューの行を項にする。
 * 行＝`{id, title, key, cells: [{column, value}], values: {列の見出し: 値}}`。cells は列の順（同じ見出しが 2 つあってもそのまま）、
 * values は見出しで引く（同じ見出しは最初の列）。値は解決したセルの項そのもの（数は数、無いセルは落とす）。
 */
object ReportRows {
    fun node(rows: List<VirtualRow>): YNode.Seq = YNode.Seq(rows.mapTo(mutableListOf<YNode>()) { row ->
        val cells = YNode.Seq(row.cells.mapTo(mutableListOf<YNode>()) { c ->
            val m = LinkedHashMap<String, YNode>()
            m["column"] = YNode.Scalar(c.column.label)
            c.value?.let { m["value"] = it }
            YNode.Map(m)
        })
        val values = LinkedHashMap<String, YNode>()
        row.cells.forEach { c -> c.value?.let { v -> if (c.column.label !in values) values[c.column.label] = v } }
        val m = LinkedHashMap<String, YNode>()
        m["id"] = YNode.Scalar(row.id)
        row.title?.let { m["title"] = YNode.Scalar(it) }
        m["key"] = YNode.Scalar(row.key)
        m["cells"] = cells
        m["values"] = YNode.Map(values)
        YNode.Map(m)
    })
}
