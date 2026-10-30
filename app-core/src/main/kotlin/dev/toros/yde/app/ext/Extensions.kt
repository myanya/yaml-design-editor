package dev.toros.yde.app.ext

import dev.toros.yde.app.view.chart.ChartViewKind
import dev.toros.yde.app.view.flow.FlowViewKind
import dev.toros.yde.workspace.ViewDefinitions
import dev.toros.yde.workspace.ViewKind

/**
 * 形式とビューの種類の登録簿（ADR-0013「窓口の形」・ADR-0015）。app-core の組込み（ビューの種類 chart・flow）は最初から入っていて、
 * 入口（desktop・cli）は別モジュールの拡張だけを `listOf(…)` で組み立てて `Extensions.install` で渡す。
 * 自動発見（ServiceLoader）はしない: どこで何が足されたかを、入口の 1 ファイルで読めるようにするため。
 * ビューの種類の差し込み口 ViewKind はカーネルにあり、ここから引数で渡す（ADR-0014）。ツールも後でここに足す。
 */
class ExtensionRegistry(val formats: List<FormatCodec> = emptyList(), views: List<ViewKind> = emptyList()) {
    /** 組込みの種類のあとに、入口が足した種類。 */
    val views: List<ViewKind> = BUILTIN_VIEWS + views

    init {
        formats.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("形式「$it」が二重に登録されています") }
        this.views.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("ビューの種類「$it」が二重に登録されています") }
        this.views.firstOrNull { it.id in ViewDefinitions.CORE_KINDS }?.let { error("ビューの種類「${it.id}」は中核の種類と重なります") }
    }

    val formatIds: List<String> get() = formats.map { it.id }

    /** skill 定義の `format` の値から。 */
    fun format(id: String): FormatCodec? = formats.firstOrNull { it.id == id }

    /** 出力ファイルの拡張子から（書ける形式だけ）。 */
    fun formatForFile(fileName: String): FormatCodec? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return formats.firstOrNull { it.canWrite && ext in it.extensions }
    }

    /** 書ける拡張子の一覧（誤りの文に使う）。 */
    val writableExtensions: List<String> get() = formats.filter { it.canWrite }.flatMap { it.extensions }

    companion object {
        /** app-core の組込みのビューの種類（app.view）。入口が渡さなくても入っている。 */
        val BUILTIN_VIEWS: List<ViewKind> = listOf(FlowViewKind, ChartViewKind)
    }
}

/** いま入っている登録簿。入口が起動時に入れる（入れていなければ組込みだけ）。 */
object Extensions {
    @Volatile
    var registry: ExtensionRegistry = ExtensionRegistry()
        private set

    fun install(registry: ExtensionRegistry) {
        this.registry = registry
    }

    fun format(id: String): FormatCodec? = registry.format(id)
    fun formatForFile(fileName: String): FormatCodec? = registry.formatForFile(fileName)

    /** 組込みと登録したビューの種類（カーネルの読み取りとメタスキーマの合成に渡す）。 */
    val viewKinds: List<ViewKind> get() = registry.views
}
