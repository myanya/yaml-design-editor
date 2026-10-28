package dev.toros.yde.cli

import dev.toros.yde.app.ext.ExtensionRegistry
import dev.toros.yde.format.docx.DocxFormat
import dev.toros.yde.format.text.CsvFormat
import dev.toros.yde.format.text.HtmlFormat
import dev.toros.yde.format.text.JsonFormat
import dev.toros.yde.format.text.MarkdownFormat
import dev.toros.yde.format.xlsx.XlsxFormat
import dev.toros.yde.view.chart.ChartViewKind

/**
 * CLI が使う拡張の一覧（ADR-0013「窓口の形」）。入口ごとに 1 ファイルで、拡張を足したらここに 1 行足す。
 * 自動発見はしない（どこで何が足されたかをこの 1 ファイルで読めるようにする）。desktop の一覧は desktop/ext/DesktopExtensions.kt。
 */
object CliExtensions {
    val registry = ExtensionRegistry(
        formats = listOf(CsvFormat, JsonFormat, MarkdownFormat, HtmlFormat, XlsxFormat, DocxFormat),
        views = listOf(ChartViewKind),
    )
}
