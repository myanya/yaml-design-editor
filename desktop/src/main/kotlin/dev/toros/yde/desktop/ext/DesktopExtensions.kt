package dev.toros.yde.desktop.ext

import dev.toros.yde.app.ext.ExtensionRegistry
import dev.toros.yde.format.docx.DocxFormat
import dev.toros.yde.format.text.CsvFormat
import dev.toros.yde.format.text.HtmlFormat
import dev.toros.yde.format.text.JsonFormat
import dev.toros.yde.format.text.MarkdownFormat
import dev.toros.yde.format.xlsx.XlsxFormat
import dev.toros.yde.view.chart.ChartViewKind
import dev.toros.yde.view.flow.FlowViewKind

/**
 * デスクトップ（と、cli モジュールへ出すまでの root の CLI）が使う拡張の一覧（ADR-0013「窓口の形」）。
 * 拡張を足したらここに 1 行足す。自動発見はしない（どこで何が足されたかをこの 1 ファイルで読めるようにする）。
 */
object DesktopExtensions {
    val registry = ExtensionRegistry(
        formats = listOf(CsvFormat, JsonFormat, MarkdownFormat, HtmlFormat, XlsxFormat, DocxFormat),
        // ビューの種類（ADR-0014）。描画は ui/view/ViewRenderers.kt の一覧に 1 行足す
        views = listOf(FlowViewKind, ChartViewKind),
    )
}
