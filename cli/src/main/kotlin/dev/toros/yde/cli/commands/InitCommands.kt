package dev.toros.yde.cli.commands

import dev.toros.yde.cli.dumpYaml
import dev.toros.yde.cli.pruneNulls
import dev.toros.yde.cli.stringFlag
import dev.toros.yde.cli.stringFlags
import dev.toros.yde.workspace.InitRequest
import dev.toros.yde.workspace.WorkspaceInit
import dev.toros.yde.workspace.WorkspaceTemplates
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 初期化の命令: init（新しいワークスペースを作る。計画はカーネルの WorkspaceInit で、ここは見せて書くだけ）・migrate（レガシー構成を .yde 構成へ）。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun initCommand(rest: List<String>): String = run {
    // ワークスペースの初期化（architecture/workspace.md「ワークスペースの初期化」）: 計画はカーネルの WorkspaceInit が作り、ここは見せて書くだけ
    val dir = Path.of(rest.firstOrNull()?.takeIf { !it.startsWith("--") } ?: error("フォルダを指定してください"))
    val name = stringFlag(rest, "--template")
    val adopt = "--adopt" in rest
    if (name == null && !adopt) {
        return dumpYaml(
            linkedMapOf(
                "templates" to WorkspaceTemplates.all.map { t ->
                    linkedMapOf(
                        "name" to t.id,
                        "label" to t.name,
                        "description" to t.description,
                        "params" to t.params.map { p ->
                            pruneNulls(linkedMapOf("key" to p.key, "label" to p.label, "type" to (p.typeName ?: p.type.id), "required" to p.required.takeIf { it }))
                        }.ifEmpty { null },
                        "files" to t.files.size,
                    ).filterValues { it != null }
                },
                "usage" to listOf(
                    "init <dir> --template issues [--no-sample-data]",
                    "init <dir> --template ${WorkspaceTemplates.CUSTOM} --answers answers.yaml [--param 名前=値]...",
                    "init <dir> --adopt [--ledger dir]...",
                ),
            )
        )
    }
    require(!WorkspaceInit.isInitialized(dir)) { "初期化済みです（.yde/ か _schemas/ があります）: $dir" }
    val request: InitRequest = if (adopt) {
        require(name == null) { "--adopt と --template は一緒に書けません" }
        InitRequest.Adopt(stringFlags(rest, "--ledger"))
    } else {
        val template = WorkspaceTemplates.get(name!!)
            ?: error("テンプレート「$name」が見つかりません（利用可能: ${WorkspaceTemplates.all.joinToString(", ") { it.id }}）")
        val fromFile = stringFlag(rest, "--answers")?.let { file ->
            (YamlIO.parseToRaw(Files.readString(Path.of(file))) as? Map<*, *> ?: error("--answers の YAML は {問いの名前: 答え} のobjectで書きます"))
                .entries.associate { (k, v) -> k.toString() to v }
        }.orEmpty()
        val fromFlags = stringFlags(rest, "--param").associate { raw ->
            val at = raw.indexOf('=').takeIf { it > 0 } ?: error("--param は 名前=値 で書きます: $raw")
            raw.substring(0, at) to YamlIO.parseToRaw(raw.substring(at + 1))
        }
        InitRequest.FromTemplate(template, fromFile + fromFlags, includeSampleData = "--no-sample-data" !in rest)
    }
    val plan = WorkspaceInit.plan(dir, request)
    if ("--dry-run" in rest) {
        dumpYaml(
            linkedMapOf(
                "dir" to dir.toString(),
                "files" to plan.files.map { f -> linkedMapOf("path" to f.path, "exists" to f.exists.takeIf { it }, "sample" to f.sample.takeIf { it }).filterValues { it != null } },
                "directories" to plan.directories.ifEmpty { null },
                "notes" to plan.notes.ifEmpty { null },
            ).filterValues { it != null }
        )
    } else {
        val result = WorkspaceInit.apply(dir, plan)
        dumpYaml(
            linkedMapOf(
                "template" to (request as? InitRequest.FromTemplate)?.template?.id,
                "dir" to dir.toString(),
                "written" to result.written,
                "skipped" to result.skipped.ifEmpty { null },
                "directories" to plan.directories.ifEmpty { null },
                "notes" to plan.notes.ifEmpty { null },
            ).filterValues { it != null }
        )
    }
}

internal fun migrateCommand(rest: List<String>): String = run {
    // レガシー構成 → .yde 構成への移行（R13-01）。ディレクトリ移動と構成ファイル作成のみで、
    // 文書・定義の中身は書き換えない（match 等の glob はワークスペース相対のため影響しない）
    val root = Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください")).toAbsolutePath().normalize()
    require(Files.isDirectory(root)) { "ワークスペースが存在しません: $root" }
    val configDir = root.resolve(WorkspaceLayout.CONFIG_DIR)
    require(!Files.exists(configDir)) { "既に ${WorkspaceLayout.CONFIG_DIR}/ が存在します（移行済み）" }
    val ledger = stringFlag(rest, "--ledger")?.replace('\\', '/')?.trim('/')
    if (ledger != null) {
        require(!ledger.contains("..") && ledger.isNotBlank()) { "--ledger に使えないパスです: $ledger" }
        require(Files.isDirectory(root.resolve(ledger))) { "--ledger のディレクトリが存在しません: $ledger" }
    }
    Files.createDirectories(configDir)
    val moved = mutableListOf<String>()
    WorkspaceLayout.Zone.entries.forEach { zone ->
        val from = root.resolve(zone.legacyDir)
        if (Files.isDirectory(from)) {
            val to = configDir.resolve(zone.ydeName)
            Files.move(from, to)
            moved += "${zone.legacyDir} -> ${WorkspaceLayout.CONFIG_DIR}/${zone.ydeName}"
        }
    }
    val configText = buildString {
        appendLine("# ワークスペース構成（R13-02。architecture/workspace.md「ワークスペース構造と layout」）")
        appendLine("# ledger: 台帳ディレクトリの宣言（文字列またはリスト）。未宣言ならワークスペース全体が台帳。")
        if (ledger != null) appendLine("ledger: $ledger") else appendLine("# ledger: doc")
    }
    Files.writeString(configDir.resolve(WorkspaceLayout.CONFIG_FILE), configText)
    dumpYaml(
        linkedMapOf(
            "configDir" to WorkspaceLayout.CONFIG_DIR,
            "moved" to moved,
            "ledger" to (ledger ?: "(未宣言: 全体が台帳)"),
        )
    )
}
