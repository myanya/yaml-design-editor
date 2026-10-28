package dev.toros.yde.app.ext

import dev.toros.yde.workspace.ViewDefinitions
import dev.toros.yde.workspace.ViewKind

/**
 * 拡張の登録簿（ADR-0013「窓口の形」）。入口（desktop・cli）が `listOf(…)` で組み立てて `Extensions.install` で渡す。
 * 自動発見（ServiceLoader）はしない: どこで何が足されたかを、入口の 1 ファイルで読めるようにするため。
 * 入出力の形式と、ビューの種類（ADR-0014。差し込み口 ViewKind はカーネルにあり、ここから引数で渡す）。ツールも後でここに足す。
 */
class ExtensionRegistry(val formats: List<FormatCodec> = emptyList(), val views: List<ViewKind> = emptyList()) {
    init {
        formats.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("形式「$it」が二重に登録されています") }
        views.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("ビューの種類「$it」が二重に登録されています") }
        views.firstOrNull { it.id in ViewDefinitions.CORE_KINDS }?.let { error("ビューの種類「${it.id}」は中核の種類と重なります") }
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
}

/** いま入っている登録簿。入口が起動時に入れる（入れていなければ形式も拡張のビューの種類も無い）。 */
object Extensions {
    @Volatile
    var registry: ExtensionRegistry = ExtensionRegistry()
        private set

    fun install(registry: ExtensionRegistry) {
        this.registry = registry
    }

    fun format(id: String): FormatCodec? = registry.format(id)
    fun formatForFile(fileName: String): FormatCodec? = registry.formatForFile(fileName)

    /** 登録したビューの種類（カーネルの読み取りとメタスキーマの合成に渡す）。 */
    val viewKinds: List<ViewKind> get() = registry.views
}
