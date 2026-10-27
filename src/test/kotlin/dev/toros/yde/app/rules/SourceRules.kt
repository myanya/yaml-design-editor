package dev.toros.yde.app.rules

/**
 * ソースの規則の表。CLAUDE.md「落とし穴」のうち機械で検査できるもので、説明は各規則の doc が指す設計文書の見出しにある。
 * 規則を足すときはここに 1 つ足し、SourceRulesTest に対応するテストを 1 つ足す。
 * 今は直せていない違反は known に理由つきで書く。直したら消す（残っているとテストが「不要」と知らせる）。
 */
class Rule(
    val number: Int,
    val name: String,
    /** 守る理由（落とし穴の文） */
    val why: String,
    /** 説明のある文書の見出し */
    val doc: String,
    val known: List<Known> = emptyList(),
    /** 当てるソース。テストの書き方の規則だけ TEST */
    val scope: Scope = Scope.MAIN,
    val check: (List<SourceFile>) -> List<Hit>,
) {
    enum class Scope { MAIN, TEST }
}

/** 既知の違反。key は Hit.key（既定はファイルのパス）と突き合わせる。 */
class Known(val key: String, val reason: String)

object SourceRules {
    private const val APP = "dev.toros.yde.app"
    private const val DESKTOP = "dev.toros.yde.desktop"
    private const val AGENT = "dev.toros.yde.agent"
    private const val UI = "src/main/kotlin/dev/toros/yde/desktop/ui"

    /** 大きなファイルの目安（行）。超えても失敗にしない（文書の字数と同じ扱い）。SourceRulesTest が報告し、pre-commit が表示する */
    const val LARGE_FILE_LINES = 800

    /** app の中の層（下から上）。同じ層の中は互いに使ってよく、上の層を import してはいけない。 */
    val appLayers: List<List<String>> = listOf(
        listOf("$APP.index", "$APP.schema", "$APP.validate", "$APP.text"),
        listOf("$APP.core"),
        listOf("$APP.session"),
        listOf("$APP.tools"),
        listOf("$APP.ext"),
        listOf("$APP.skill"),
        listOf("$AGENT.llm"),
        listOf(AGENT), // agent の根＝定義（Definitions・Evals・ForeachDef）
        listOf("$AGENT.run"),
        listOf("$AGENT.auto"),
        listOf("$AGENT.eval"),
        listOf(DESKTOP), // desktop の根（AppState・Main・ScreenMemory・拡張の登録 ext）
        listOf("$DESKTOP.ui"),
    )

    /** Compose 非依存の純粋ロジック層。 */
    private val purePackages = arrayOf("$APP.core", "$APP.index", "$APP.schema", "$APP.validate", "$APP.text", "$APP.session", "$APP.tools", "$APP.ext", "$APP.skill")
    private val coreModules = setOf("yaml-core", "semantic-core", "workspace-core", "cli", "app-core", "agent")

    /** 外部の UI ライブラリの import の接頭辞と、それを包む 1 ファイル。 */
    private val wrappedLibraries = mapOf(
        "io.github.vinceglb." to "$UI/FilePickers.kt",
        "sh.calvin.reorderable." to "$UI/Reorder.kt",
        "com.mikepenz." to "$UI/MarkdownView.kt",
        "com.patrykandpatrick.vico." to "$UI/Charts.kt",
        "org.eclipse.elk." to "$UI/GraphLayouts.kt",
    )

    /** FieldType の値（基底の種類だけ）。足すときは architecture/schema.md「型の連鎖と狭める規則」を読んでからここも更新する。 */
    val fieldTypes = setOf(
        "STRING", "INT", "NUMBER", "BOOLEAN", "ENUM", "DATE", "TIME", "DATETIME", "FILE", "ID", "REF", "ARRAY", "MAP", "OBJECT", "ANY",
    )

    /** Files.exists / notExists を直接呼んでよいファイル（ストア・配置・初期化・実行記録など、ファイルそのものを扱う所）。減らす方向。 */
    private val fileExistsAllowed = setOf(
        "src/main/kotlin/dev/toros/yde/desktop/AppState.kt",
        "app-core/src/main/kotlin/dev/toros/yde/app/session/DocumentSession.kt",
        "cli/src/main/kotlin/dev/toros/yde/cli/commands/QueryCommands.kt",
        "cli/src/main/kotlin/dev/toros/yde/cli/commands/InitCommands.kt",
        "cli/src/main/kotlin/dev/toros/yde/cli/commands/SchemaCommands.kt",
        "agent/src/main/kotlin/dev/toros/yde/agent/eval/EvalRunner.kt",
        "agent/src/main/kotlin/dev/toros/yde/agent/run/RunLog.kt",
        "app-core/src/main/kotlin/dev/toros/yde/app/tools/WorkingCopy.kt",
        "$UI/InitWizard.kt",
        "$UI/MarkdownPane.kt",
        "workspace-core/src/main/kotlin/dev/toros/yde/workspace/DocumentStore.kt",
        "workspace-core/src/main/kotlin/dev/toros/yde/workspace/FileArrangement.kt",
        "workspace-core/src/main/kotlin/dev/toros/yde/workspace/FileArrangementPlanner.kt",
        "workspace-core/src/main/kotlin/dev/toros/yde/workspace/WorkspaceInit.kt",
        "workspace-core/src/main/kotlin/dev/toros/yde/workspace/WorkspacePaths.kt",
    )

    val all: List<Rule> = listOf(
        Rule(
            1, "純粋ロジック層に Compose を持ち込まない",
            why = "コアの 3 モジュールと cli・app-core・agent・拡張（format-*・view-*。描画の view-*-ui は除く）は Compose 非依存で、単体テストはこの層に書く",
            doc = "architecture/README.md「モジュールと依存方向」",
        ) { files ->
            files.filter { it.module in coreModules || ModuleRules.isExtension(it.module) || it.inPackage(*purePackages) }
                .flatMap { it.importsStartingWith("androidx.compose.", "org.jetbrains.compose.") }
        },
        Rule(
            2, "app の層は一方向",
            why = "index・schema・validate・text、core、session、tools、ext、skill、agent.llm、agent（定義）、agent.run、agent.auto、agent.eval、desktop の根（AppState・ext）、desktop.ui の順に下から上へ重なり、上の層を import しない",
            doc = "architecture/README.md「モジュールと依存方向」",
            known = listOf(
                Known("app-core/src/main/kotlin/dev/toros/yde/app/validate/Readiness.kt", "core.Workspace を使っていて validate と core が循環している（BACKLOG）"),
                Known("src/main/kotlin/dev/toros/yde/desktop/AppState.kt", "ui の DesktopUiDispatcher を使っている（BACKLOG）"),
            ),
        ) { files -> layerViolations(files) },
        Rule(
            3, "yaml-core と semantic-core にファイルの場所（java.nio.file）を持ち込まない",
            why = "2 つのコアは I/O を持たない。場所はワークスペース相対の DocumentId で表す",
            doc = "CONVENTIONS「モジュールの規約」・architecture/index-references.md「場所は文書ID」",
        ) { files ->
            files.filter { it.module == "yaml-core" || it.module == "semantic-core" }.flatMap { it.importsStartingWith("java.nio.file.") }
        },
        Rule(
            4, "等幅の文字は AppFonts.Mono",
            why = "FontFamily.Monospace は OS 任せで日本語の幅が揃わない。同梱の Cica を使う",
            doc = "architecture/ui.md「規律」",
        ) { files -> onlyIn(files, Regex("""FontFamily\.Monospace"""), setOf("$UI/Common.kt")) },
        Rule(
            5, "外部の UI ライブラリは包む 1 ファイルの外へ出さない",
            why = "より良いライブラリが見つかったとき、その 1 ファイルだけ書き換えれば済むようにする",
            doc = "architecture/ui.md「規律」",
        ) { files ->
            files.flatMap { f ->
                wrappedLibraries.filter { (_, wrapper) -> f.path != wrapper }.flatMap { (prefix, _) -> f.importsStartingWith(prefix) }
            }
        },
        Rule(
            6, "AI の HTTP は agent/llm/Http.kt に一元化",
            why = "HttpClient の生成と送信は 1 か所（agent/llm/Http.kt）に置き、プロバイダごとに作らない",
            doc = "architecture/agents.md「AI 接続」",
        ) { files ->
            files.filter { it.path != "agent/src/main/kotlin/dev/toros/yde/agent/llm/Http.kt" }.flatMap { it.importsStartingWith("java.net.http.") }
        },
        Rule(
            7, "JFileChooser と Dispatchers.Main を使わない",
            why = "ファイル選択は OS ネイティブの FilePickers。Dispatchers.Main は提供モジュールが無く実行時例外になる（EDT へは DesktopUiDispatcher）",
            doc = "CONVENTIONS「Compose UI の書き方」",
        ) { files -> files.flatMap { it.find(Regex("""JFileChooser|Dispatchers\.Main\b""")) } },
        Rule(
            8, "生の色は Palette だけ",
            why = "Color(0x…) をビューに直接書くとテーマ切替が壊れる。色は Palette を通す",
            doc = "CONVENTIONS「Compose UI の書き方」",
        ) { files -> onlyIn(files, Regex("""\bColor\(0x"""), setOf("$UI/Common.kt")) },
        Rule(
            9, "UI は state.index でなく overlayIndex() を読む",
            why = "索引は二層で、未保存の編集を重ねたものを UI が読む",
            doc = "architecture/index-references.md「索引の二層」",
        ) { files -> files.filter { it.inPackage("$DESKTOP.ui") }.flatMap { it.find(Regex("""\bstate\.index\b""")) } },
        Rule(
            10, "FieldType は基底の種類だけ",
            why = "text / line / word のような組込みの定義体は builtin-types/ の YAML で、enum に足さない。使う側の決まりは狭めるだけ",
            doc = "architecture/schema.md「型の連鎖と狭める規則」",
        ) { files -> fieldTypeEntries(files) },
        Rule(
            11, "機能側で Files.exists を直接見ない",
            why = "file 型の値の存在と許可は CodedDocumentChecks.fileIssues と FilePolicy で判定する。直接呼んでよいのはファイルそのものを扱う所だけ",
            doc = "architecture/schema.md「規律」・architecture/checks.md「文書 1 つの検証」",
        ) { files -> onlyIn(files, Regex("""Files\.(exists|notExists)\("""), fileExistsAllowed) },
        Rule(
            12, "同じ裸の名前のファイルを複数作らない",
            why = "検索で複数当たり取り違える。Main.kt（入口）だけは例外",
            doc = "CONVENTIONS「モジュールの規約」",
            known = listOf(
                Known("Query.kt", "semantic・semantic.schema・app.index にある。改名は ROADMAP の計画"),
                Known("Transform.kt", "semantic・semantic.schema にある。同上"),
                Known("Flow.kt", "semantic・semantic.schema にある。同上"),
                Known("Aspects.kt", "semantic・app.core にある。同上"),
                Known("Shell.kt", "cli・app.ui にある。同上"),
            ),
        ) { files ->
            files.filter { it.name != "Main.kt" }.groupBy { it.name }.filter { it.value.size > 1 }
                .flatMap { (name, group) -> group.map { Hit(it, 1, "$name が ${group.size} か所", key = name) } }
        },
        Rule(
            13, "テストに OS 固有の絶対パスを書かない",
            why = "全テストは GitHub Actions（Linux）でも回る。ドライブ文字の絶対パスは Linux では相対パスになる。絶対パスは Path.of(\"\").toAbsolutePath().root から組むか一時ディレクトリを使う",
            doc = "CONVENTIONS「テスト」",
            scope = Rule.Scope.TEST,
        ) { files -> files.flatMap { it.find(drivePathLiteral) } },
        Rule(
            14, "settings.gradle.kts の全モジュールを規則テストが走査する",
            why = "新しいモジュールを走査の一覧（SourceScanner.modules）に足し忘れると、そのモジュールだけ全部の規則の外になる",
            doc = "architecture/README.md「ソース規則テスト」",
        ) { _ -> ModuleRules.unscannedModules() },
        Rule(
            15, "拡張の実装は入口の登録の一覧に載せる",
            why = "登録は入口ごとの 1 ファイル（desktop/ext/DesktopExtensions.kt・cli/CliExtensions.kt）で、自動発見はしない。載せ忘れると、その入口でだけ形式が使えない",
            doc = "architecture/agents.md「拡張ポイント」",
        ) { files -> ModuleRules.unregisteredExtensions(files) },
        Rule(
            16, "拡張は app-core とカーネルだけを使う",
            why = "拡張は閉じた箱。拡張どうし・agent・入口（desktop・cli）に依存すると、1 つの拡張を読むのに他を読む必要が出る（ADR-0013）",
            doc = "architecture/README.md「モジュールと依存方向」",
        ) { files -> ModuleRules.extensionDependencies(files) },
        Rule(
            17, "拡張は決まった形にする",
            why = "README・共通の設定の build・テスト・モジュール名どおりのパッケージ・公開は差し込み口の実装だけ、の同じ形なら、見る範囲が 1 モジュールに絞れる（ADR-0013「モジュールの中の形」）",
            doc = "architecture/agents.md「拡張ポイント」",
        ) { files -> ModuleRules.extensionShape(files) },
        Rule(
            18, "agent のファイルの間に循環を作らない",
            why = "パッケージの向き（規則 2）は同じパッケージの中のファイルの循環を見ない。共に使う小さな型は土台のファイル（run/AgentHost.kt）に置き、定義と実行器を分ける",
            doc = "architecture/agents.md「規律」",
        ) { files -> ModuleRules.agentFileCycles(files) },
    )

    /** `Path.of("C:/…")`・`Paths.get("C:\\…")` のようなドライブ文字の絶対パスの直書き。 */
    private val drivePathLiteral = Regex("""\b(Path\.of|Paths\.get)\(\s*"[A-Za-z]:[/\\]""")

    /** 正規表現に当たる行のうち、許したファイル以外のもの。 */
    private fun onlyIn(files: List<SourceFile>, regex: Regex, allowed: Set<String>): List<Hit> =
        files.filter { it.path !in allowed }.flatMap { it.find(regex) }

    /** パッケージの層の番号。根のパッケージは全部に前方一致するので、いちばん長く一致した接頭辞の層を採る。 */
    private fun layerOf(pkg: String): Int? {
        var best: Pair<Int, Int>? = null
        appLayers.forEachIndexed { index, layer ->
            layer.forEach { p ->
                if ((pkg == p || pkg.startsWith("$p.")) && (best == null || p.length > best!!.second)) best = index to p.length
            }
        }
        return best?.first
    }

    private fun layerViolations(files: List<SourceFile>): List<Hit> {
        val packages = files.map { it.packageName }.toSet()
        fun packageOf(importName: String): String? = packages.filter { importName.startsWith("$it.") }.maxByOrNull { it.length }
        return files.flatMap { f ->
            val from = layerOf(f.packageName) ?: return@flatMap emptyList()
            f.imports.mapNotNull { imp ->
                val to = packageOf(imp.name)?.let { layerOf(it) } ?: return@mapNotNull null
                if (to > from) Hit(f, imp.line, "import ${imp.name}（上の層）") else null
            }
        }
    }

    /** semantic.schema の FieldType の enum の値を読み、基底の種類にないものを返す。 */
    private fun fieldTypeEntries(files: List<SourceFile>): List<Hit> {
        val file = files.firstOrNull { it.path.endsWith("/semantic/schema/Fields.kt") } ?: error("semantic/schema/Fields.kt が見つからない")
        val start = file.lines.indexOfFirst { it.contains("enum class FieldType") }
        check(start >= 0) { "Fields.kt に enum class FieldType が無い" }
        val entry = Regex("""^\s*([A-Z][A-Z_]*)\(""")
        val hits = mutableListOf<Hit>()
        for (i in start + 1 until file.lines.size) {
            val line = file.lines[i]
            entry.find(line)?.let { m ->
                if (m.groupValues[1] !in fieldTypes) hits += Hit(file, i + 1, "FieldType.${m.groupValues[1]} は基底の種類にない")
            }
            if (line.contains(';')) break
        }
        return hits
    }
}
