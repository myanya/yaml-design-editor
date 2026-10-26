package dev.toros.yde.app.core

import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.ReportRows
import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.semantic.TextTemplate
import dev.toros.yde.workspace.ReportDef
import dev.toros.yde.workspace.ReportDefinitions
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * レポート（R30-04）: 定義（`.yde/reports` の YAML）の変換で値の一式を作り、同じ場所のひな形（Markdown など）にはめ込んで文字にする。
 * 数の計算はビュー（`$view`）に任せ、ここでは集計を書かない。出力は原本からいつでも作り直せる派生物で、
 * ワークスペースへ書くのは呼び出し側（CLI の --out・画面の「保存」）。
 */
object Reports {

    data class Loaded(val def: ReportDef, val templateText: String, val templateFile: Path)

    data class LoadResult(val reports: List<Loaded>, val errors: List<String>) {
        fun report(id: String): Loaded? = reports.singleOrNull { it.def.id == id }
    }

    /** 組み立てた文字・既定の出力先（定義の output を埋めたもの）・埋まらなかった穴。 */
    data class Result(val text: String, val outputRel: String?, val missing: List<String>)

    fun load(workspace: Path): LoadResult {
        val dir = runCatching { WorkspacePaths.resolve(workspace, WorkspaceLayout.of(workspace).reportsDir) }
            .getOrElse { return LoadResult(emptyList(), listOf(it.message ?: "レポート定義の場所を解決できません")) }
        if (!Files.isDirectory(dir)) return LoadResult(emptyList(), emptyList())
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
        val errors = mutableListOf<String>()
        val base = dir.toAbsolutePath().normalize()
        val reports = files.mapNotNull { file ->
            val rel = workspace.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')
            runCatching {
                val raw = YamlIO.parseToRaw(Files.readString(file)) as? Map<*, *> ?: error("定義のルートは object で書きます")
                val def = ReportDefinitions.parse(raw, DocumentId(rel))
                val templateFile = file.parent.resolve(def.template).toAbsolutePath().normalize()
                require(templateFile.startsWith(base)) { "template は定義と同じ場所の中を指します: ${def.template}" }
                require(Files.isRegularFile(templateFile)) { "ひな形のファイルがありません: ${def.template}" }
                val text = Files.readString(templateFile)
                TextTemplate.check(text)?.let { error("ひな形「${def.template}」: $it") }
                Loaded(def, text, templateFile)
            }.getOrElse { errors += "${file.fileName}: ${it.message}"; null }
        }
        val duplicated = reports.groupBy { it.def.id }.filterValues { it.size > 1 }.keys
        duplicated.forEach { errors += "レポートID「$it」が重複しています" }
        return LoadResult(reports.filter { it.def.id !in duplicated }, errors)
    }

    /** 値の一式を作り、ひな形にはめ込む。ビューの行（`$view`）は同じ索引・原本の読み手で解決する。 */
    fun run(
        loaded: Loaded, params: Map<String, Any?>, catalog: Views.LoadResult, index: IdIndex,
        loader: (DocumentId) -> YNode?, schemaFor: (DocumentId) -> SchemaDef? = { null },
    ): Result {
        val def = loaded.def
        def.params.filter { it !in params }.takeIf { it.isNotEmpty() }
            ?.let { error("レポート「${def.id}」は値「${it.joinToString("・")}」を受け取ります（--param 名前=値）") }
        val cache = HashMap<String, YNode?>()
        val views: (String) -> YNode? = { id ->
            if (id in cache) cache[id] else catalog.view(id)?.let { v -> ReportRows.node(Views.resolve(v, index, loader, schemaFor)) }.also { cache[id] = it }
        }
        val built = SemanticTransform.run(def.transform, YNode.Map(LinkedHashMap()), null, params, views).term ?: YNode.Map(LinkedHashMap())
        val rendered = TextTemplate.render(loaded.templateText, built)
        val output = def.output?.let { pattern ->
            val r = TextTemplate.render(pattern, built)
            require(r.missing.isEmpty()) { "出力先「$pattern」の穴が埋まりません: ${r.missing.joinToString("・")}" }
            r.text.trim()
        }
        return Result(rendered.text, output, rendered.missing)
    }

    /** 出力先へ書く（派生物。YAML と、定義・記録の場所には書かない）。 */
    fun write(workspace: Path, rel: String, text: String): Path {
        val layout = WorkspaceLayout.of(workspace)
        require(!rel.endsWith(".yaml") && !rel.endsWith(".yml")) { "レポートは YAML には書きません（原本は YAML）: $rel" }
        require(!layout.isReserved(rel)) { "定義・記録の場所には書きません: $rel" }
        val target = WorkspacePaths.resolve(workspace, rel)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return target
    }
}
