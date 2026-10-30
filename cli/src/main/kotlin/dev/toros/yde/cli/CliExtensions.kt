package dev.toros.yde.cli

import dev.toros.yde.app.ext.ExtensionRegistry
import dev.toros.yde.format.office.DocxFormat
import dev.toros.yde.format.office.XlsxFormat

/**
 * CLI が使う拡張の一覧（ADR-0013「窓口の形」）。文字の形式とビューの種類（chart・flow）は app-core の組込みで、
 * ここに並べるのは別モジュールの拡張だけ（ADR-0015）。入口ごとに 1 ファイルで、拡張を足したらここに 1 行足す。
 * 自動発見はしない（どこで何が足されたかをこの 1 ファイルで読めるようにする）。desktop の一覧は desktop/ext/DesktopExtensions.kt。
 */
object CliExtensions {
    val registry = ExtensionRegistry(
        formats = listOf(XlsxFormat, DocxFormat),
    )
}
