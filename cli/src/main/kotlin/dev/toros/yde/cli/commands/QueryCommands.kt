package dev.toros.yde.cli.commands

import dev.toros.yde.cli.dumpYaml
import dev.toros.yde.cli.asText
import dev.toros.yde.cli.asData
import dev.toros.yde.cli.CliResult
import dev.toros.yde.app.core.Reports
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.Query
import dev.toros.yde.cli.stringFlag
import dev.toros.yde.cli.stringFlags
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.workspace.BuiltinTransforms
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.pretty
import java.nio.file.Files
import java.nio.file.Path

/**
 * クエリ・変換・レポートの命令（試作台）: グラフパターン照合（Query）・YAML から YAML への変換（SemanticTransform）・レポート（Reports）。出力は保存しない（report の --out を除く）。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun queryCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    // スペックはファイル（第2引数）かインライン（--spec）のどちらかで与える
    val specText = stringFlag(rest, "--spec")
        ?: rest.getOrNull(1)?.takeIf { !it.startsWith("--") }?.let { Files.readString(Path.of(it)) }
        ?: error("クエリのスペックを指定してください（query <workspace> <spec.yaml> または --spec \"<YAML>\"）")
    val raw = dev.toros.yde.yaml.YamlIO.parseToRaw(specText) as? Map<*, *> ?: error("スペックのルートはobjectである必要があります")
    val spec = QuerySpec.parse(raw, "query")
    val result = Query.run(ws.index, spec, ws::treeOf)
    asData(
        linkedMapOf<String, Any?>(
            "columns" to result.columns,
            "total" to result.total,
            "rows" to result.rows.map { row ->
                row.bindings.entries.associate { (bind, node) ->
                    bind to linkedMapOf<String, Any?>(
                        "id" to node.id,
                        "kind" to node.kind.id.takeIf { node.kind != QueryNodeKind.ENTITY },
                        "title" to node.title,
                        "entityType" to node.entityType,
                        "file" to node.document?.value,
                        "path" to node.path.takeIf { it.isNotEmpty() }?.pretty(),
                    ).filterValues { it != null }
                }
            },
        )
    )
}

internal fun transformCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val defFile = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
        ?: error("変換の定義を指定してください（transform <workspace> <定義.yaml> [--input <file>] [--param 名前=値]）")
    // 「builtin:名前」は、組込みの変換定義（例: builtin:structural-schema＝構造のスキーマの導出）
    val def = if (defFile.startsWith("builtin:")) BuiltinTransforms.parse(defFile.removePrefix("builtin:"))
    else TransformDef.parse(dev.toros.yde.yaml.YamlIO.parseToRaw(Files.readString(Path.of(defFile))), "transform")
    val params = stringFlags(rest, "--param").associate { raw ->
        val at = raw.indexOf('=').takeIf { it > 0 } ?: error("--param は 名前=値 で書きます: $raw")
        raw.substring(0, at) to dev.toros.yde.yaml.YamlIO.parseToRaw(raw.substring(at + 1))
    }
    val result = if (def.from == null) {
        val input = stringFlag(rest, "--input") ?: error("この定義は入力の文書を1つ受け取ります。--input <file> を指定してください")
        // ワークスペースの文書ならその相対パス、そうでなければファイルのパスとして読む
        val file = ws.root.resolve(input).takeIf { Files.exists(it) } ?: Path.of(input)
        val root = dev.toros.yde.yaml.YamlIO.parseFirst(Files.readString(file)) ?: error("入力を YAML として読めません: $input")
        SemanticTransform.run(def, root, DocumentId(file.toString()), params)
    } else {
        val view = ws.index
        SemanticTransform.run(
            def, view.entries, view.refs, view.graph,
            rel = { d -> d.value },
            params = params,
            loader = { d -> runCatching { ws.load(d.value).root }.getOrNull() },
        )
    }
    val text = result.term?.let { YamlIO.dumpPlain(it) } ?: "# （何も得られませんでした）\n"
    asText(if ("--origins" !in rest) text else text + "\n" + dumpYaml(
        linkedMapOf(
            "origins" to result.origins.map { o ->
                linkedMapOf(
                    "output" to o.output.pretty(),
                    "file" to o.document?.value,
                    "path" to o.path.pretty(),
                ).filterValues { it != null }
            },
        )
    ))
}

internal fun reportCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val loaded = Reports.load(ws.root)
    val id = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
    if (id == null) {
        asData(
            linkedMapOf(
                "reports" to loaded.reports.map { r ->
                    linkedMapOf(
                        "id" to r.def.id, "name" to r.def.name, "params" to r.def.params, "template" to r.def.template,
                        "output" to r.def.output, "description" to r.def.description,
                    )
                },
                "errors" to loaded.errors,
            ),
        )
    } else {
        val report = loaded.report(id) ?: error(
            "レポート「$id」が見つかりません（report <workspace> で一覧を確認できます）" +
                (if (loaded.errors.isNotEmpty()) "。定義エラー: " + loaded.errors.joinToString(" ／ ") else ""),
        )
        val params = stringFlags(rest, "--param").associate { raw ->
            val at = raw.indexOf('=').takeIf { it > 0 } ?: error("--param は 名前=値 で書きます: $raw")
            raw.substring(0, at) to dev.toros.yde.yaml.YamlIO.parseToRaw(raw.substring(at + 1))
        }
        val catalog = Views.load(ws.root, ws.schemas)
        val result = Reports.run(report, params, catalog, ws.index, ws::treeOf, ws::schemaFor)
        val out = stringFlag(rest, "--out") ?: result.outputRel
        if ("--print" in rest || out == null) {
            asText(result.text + (if (result.missing.isNotEmpty()) "\n<!-- 埋まらなかった穴: ${result.missing.joinToString("・")} -->\n" else ""))
        } else {
            val target = Reports.write(ws.root, out, result.text)
            asData(linkedMapOf("written" to out, "file" to target.toString(), "missing" to result.missing))
        }
    }
}
