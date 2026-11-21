package dev.toros.yde.app.core

import dev.toros.yde.yaml.YamlIO
import java.nio.file.Path

/** 同じ読み取り状態で複数のレポートを作る。内容の組み立てと保存は Reports に委ねる。 */
object ReportBatch {
    data class Request(val report: String, val params: Map<String, Any?> = emptyMap(), val destination: String? = null)
    data class Written(val report: String, val written: String, val file: Path)

    /** CLI 等から受け取る実行リスト。YAML と JSON は同じ形で読み、値の型を維持する。 */
    fun parse(text: String): List<Request> {
        val root = YamlIO.parseToRaw(text) as? Map<*, *> ?: error("一括レポートのルートは object で書きます")
        require(root.keys == setOf("outputs")) { "一括レポートは outputs の配列で書きます" }
        val outputs = root["outputs"] as? List<*> ?: error("outputs は配列で書きます")
        return outputs.mapIndexed { index, value ->
            val row = value as? Map<*, *> ?: error("outputs[$index] は object で書きます")
            require(row.keys.all { it in setOf("report", "params", "destination") }) { "outputs[$index] に不明な項目があります" }
            val report = row["report"] as? String
            require(!report.isNullOrBlank()) { "outputs[$index].report はレポート ID で書きます" }
            val params = if ("params" in row) row["params"] as? Map<*, *> ?: error("outputs[$index].params は object で書きます") else emptyMap<Any?, Any?>()
            val destination = row["destination"]
            require(destination == null || destination is String && destination.isNotBlank()) { "outputs[$index].destination は出力先の文字で書きます" }
            Request(report, params.entries.associate { (key, parameter) ->
                require(key is String) { "outputs[$index].params のキーは文字で書きます" }
                key to parameter
            }, destination as String?)
        }
    }

    /** 全件の定義・値・出力先を確かめてから保存する。保存中の IO エラーはロールバックしない。 */
    fun write(workspace: Workspace, requests: List<Request>): List<Written> {
        val loaded = Reports.load(workspace.root)
        require(loaded.errors.isEmpty()) { "レポート定義の誤り: ${loaded.errors.joinToString(" ／ ")}" }
        val catalog = Views.load(workspace.root, workspace.schemas)
        require(catalog.errors.isEmpty()) { "ビュー定義の誤り: ${catalog.errors.joinToString(" ／ ")}" }
        val targets = mutableSetOf<Path>()
        val results = requests.map { request ->
            val report = loaded.report(request.report) ?: error("レポート「${request.report}」が見つかりません")
            val result = Reports.run(report, request.params, catalog, workspace.index, workspace::treeOf, workspace::schemaFor)
            require(result.missing.isEmpty()) { "レポート「${request.report}」の埋まらなかった穴: ${result.missing.joinToString("・")}" }
            val rel = request.destination ?: result.outputRel ?: error("レポート「${request.report}」の出力先がありません")
            val target = Reports.outputPath(workspace.root, rel)
            require(targets.none { it.startsWith(target) || target.startsWith(it) }) { "生成先が重複しています: $rel" }
            targets.add(target)
            Triple(request, rel, result.text)
        }
        return results.map { (request, rel, text) -> Written(request.report, rel, Reports.write(workspace.root, rel, text)) }
    }
}
