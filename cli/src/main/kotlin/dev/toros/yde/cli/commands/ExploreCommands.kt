package dev.toros.yde.cli.commands

import dev.toros.yde.app.core.Disclosure
import dev.toros.yde.app.core.Search
import dev.toros.yde.app.core.SearchQuery
import dev.toros.yde.app.core.Traverse
import dev.toros.yde.app.core.TraverseSpec
import dev.toros.yde.app.core.TraverseStep
import dev.toros.yde.cli.dumpYaml
import dev.toros.yde.cli.intFlag
import dev.toros.yde.cli.stringFlag
import dev.toros.yde.cli.stringFlags
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.parseYPath
import java.nio.file.Files

/**
 * 探索の命令: catalog・outline・read・bundle・traverse・search・hash。引数を app-core の読み出し（Disclosure・Search・Traverse）に写して YAML を返す。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun catalogCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    dumpYaml(Disclosure.catalog(ws).map { entry ->
        linkedMapOf<String, Any?>(
            "file" to entry.file,
            "documentId" to entry.documentId,
            "name" to entry.name,
            "description" to entry.description,
            "schema" to entry.schemaId,
            "ids" to entry.idCount,
            "phase" to entry.phase,
        ).filterValues { it != null }
    })
}

internal fun outlineCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val rel = rest.getOrNull(1) ?: error("ファイルを指定してください")
    val doc = ws.load(rel)
    val root = doc.root ?: error("YAML パースエラー: ${doc.parseError}")
    Disclosure.outline(root) + "\n"
}

internal fun readCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val rel = rest.getOrNull(1) ?: error("ファイルを指定してください")
    val pathArg = rest.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: ""
    val maxChars = intFlag(rest, "--max-chars") ?: 20_000
    val doc = ws.load(rel)
    val root = doc.root ?: error("YAML パースエラー: ${doc.parseError}")
    Disclosure.nodeYaml(root, parseYPath(pathArg), maxChars)
}

internal fun bundleCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val rel = rest.getOrNull(1) ?: error("ファイルを指定してください")
    val pathArg = rest.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: ""
    val budget = intFlag(rest, "--budget") ?: 8_000
    Disclosure.bundle(ws, rel, parseYPath(pathArg), budget)
}

internal fun traverseCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val start = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("起点の論理IDを指定してください")
    val steps = stringFlag(rest, "--steps")?.let { raw ->
        val list = dev.toros.yde.yaml.YamlIO.parseToRaw(raw) as? List<*> ?: error("--steps はYAMLの配列である必要があります")
        list.mapIndexed { i, e -> TraverseStep.parse(e, "steps[$i]") }
    } ?: TraverseSpec.DEFAULT_STEPS
    val budget = intFlag(rest, "--budget") ?: 8_000
    Traverse.run(
        ws.index,
        loader = { d -> runCatching { ws.load(d.value).root }.getOrNull() },
        spec = TraverseSpec(start = start, steps = steps, budgetChars = budget),
    ) + "\n"
}

internal fun searchCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val query = SearchQuery(
        text = stringFlag(rest, "--text"),
        schemaIds = stringFlags(rest, "--schema"),
        fileGlob = stringFlag(rest, "--file"),
        pathGlob = stringFlag(rest, "--path"),
        refsTo = stringFlag(rest, "--ref-to"),
        unresolvedRefs = "--unresolved" in rest,
        limit = intFlag(rest, "--limit") ?: 50,
    )
    dumpYaml(Search.run(ws, query).map { hit ->
        linkedMapOf(
            "file" to hit.file,
            "path" to hit.path,
            "kind" to hit.kind,
            "excerpt" to hit.excerpt,
        )
    })
}

internal fun hashCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val rel = rest.getOrNull(1) ?: error("ファイルを指定してください")
    val text = Files.readString(ws.resolve(rel))
    dumpYaml(linkedMapOf("file" to rel, "base" to ChangeSets.contentHash(text)))
}
