package dev.toros.yde.semantic.schema

/**
 * 接頭辞を決めた系列の採番（skill の取り込みと、操作の insert が共有する 1 実装）。
 * existing のうち「接頭辞＋数字だけ」の ID の最大番号 + 1 を、既存の桁幅（無ければ digits）でゼロ埋めして返す。
 * 系列に 1 件も無ければ接頭辞＋1（digits 桁）。決定的。
 */
object IdSeries {
    fun next(existing: Collection<String>, prefix: String, digits: Int): String {
        val numbers = existing.asSequence()
            .filter { it.length > prefix.length && it.startsWith(prefix) }
            .map { it.substring(prefix.length) }
            .filter { rest -> rest.all { it.isDigit() } }
            .toList()
        val max = numbers.maxByOrNull { it.toLongOrNull() ?: -1L }
        val next = (max?.toLongOrNull() ?: 0L) + 1
        return prefix + next.toString().padStart(maxOf(digits, max?.length ?: 0), '0')
    }
}
