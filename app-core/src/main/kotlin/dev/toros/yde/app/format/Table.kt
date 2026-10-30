package dev.toros.yde.app.format

/**
 * 表形式データの共通抽象（R5-02 / R5-03）。形式（xlsx / csv / json / markdown / docx / html）の差は `FormatCodec` の実装が吸収し、
 * インポータ・エクスポータは形式に依らず「ヘッダ＋行」を扱う。セル値は型を保持する（String / Long / Double / Boolean / null）。
 */
class Table(val headers: List<String>, val rows: List<List<Any?>>) {
    fun columnIndex(name: String): Int = headers.indexOf(name)
}
