package dev.toros.yde.app.rules

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * モジュールの形の規則（ソースの規則 14〜18 の中身）: 走査の漏れ・拡張の登録・拡張の依存・拡張の形・agent のファイルの循環。
 * 表（SourceRules）から呼ぶ。ソースの 1 行ではなく、モジュールの build ファイルや一覧のファイルを見るものはここに置く。
 */
internal object ModuleRules {
    private val root get() = SourceScanner.repoRoot

    /** 拡張のモジュール（形式・ビューの種類）。view-*-ui（描画）だけは desktop と同じく Compose を持てるので外す。 */
    fun isExtension(module: String): Boolean =
        (module.startsWith("format-") || module.startsWith("view-")) && !module.endsWith("-ui")

    /** 拡張のパッケージの根（format-text → dev.toros.yde.format.text）。 */
    fun extensionPackage(module: String): String = "dev.toros.yde." + module.replace('-', '.')

    /** 入口ごとの拡張の登録の一覧。 */
    private val entryLists = listOf(
        "src/main/kotlin/dev/toros/yde/desktop/ext/DesktopExtensions.kt",
        "cli/src/main/kotlin/dev/toros/yde/cli/CliExtensions.kt",
    )

    /** 拡張が使ってよいモジュール（Gradle の project 依存）。 */
    private val extensionMayUse = setOf("app-core", "workspace-core", "semantic-core", "yaml-core")

    /** 拡張が import してはいけないパッケージ（応用の上の層と入口）。拡張どうしは別に見る。 */
    private val extensionMustNotImport = listOf("dev.toros.yde.agent.", "dev.toros.yde.desktop.", "dev.toros.yde.cli.")

    /** ソース以外のファイル（build・一覧・README）を Hit の置き場として読む。無いファイルは空の本文。 */
    private fun pseudo(module: String, path: String): SourceFile {
        val file = root.resolve(path)
        return SourceScanner.parse(module, path, if (file.isRegularFile()) file.readText() else "")
    }

    private fun extensionModules(): List<String> = SourceScanner.modules.keys.filter(::isExtension)

    // ---------------------------------------------------------------- 規則 14

    /** settings.gradle.kts の include にあって、走査（SourceScanner.modules）に無いモジュール。root（"app"）は include に書かない。 */
    fun unscannedModules(): List<Hit> {
        val settings = pseudo("app", "settings.gradle.kts")
        val include = Regex(""""\s*:([\w-]+)\s*"""")
        return settings.lines.flatMapIndexed { i, line ->
            include.findAll(line).map { it.groupValues[1] }
                .filter { it !in SourceScanner.modules }
                .map { Hit(settings, i + 1, "モジュール :$it が SourceScanner.modules に無い", key = it) }
                .toList()
        }
    }

    // ---------------------------------------------------------------- 規則 15

    /** 拡張のモジュールが公開する実装（トップレベルの object）のうち、入口の一覧のどれかに名前が無いもの。 */
    fun unregisteredExtensions(files: List<SourceFile>): List<Hit> {
        val lists = entryLists.map { p -> files.firstOrNull { it.path == p } ?: error("入口の一覧 $p が見つからない") }
        val objectDecl = Regex("""^object\s+(\w+)\s*:""")
        return files.filter { isExtension(it.module) }.flatMap { f ->
            f.lines.flatMapIndexed { i, line ->
                val name = objectDecl.find(line)?.groupValues?.get(1) ?: return@flatMapIndexed emptyList()
                val word = Regex("""(?<![\w.])$name\b""")
                lists.filter { !word.containsMatchIn(it.code) }
                    .map { Hit(f, i + 1, "$name が ${it.path} の一覧に無い", key = "${f.path}:$name") }
            }
        }
    }

    // ---------------------------------------------------------------- 規則 16

    /** 拡張の import のうち、応用の上の層・入口・ほかの拡張を指すもの。と、build の project 依存のうち許した外のもの。 */
    fun extensionDependencies(files: List<SourceFile>): List<Hit> {
        val imports = files.filter { isExtension(it.module) }.flatMap { f ->
            val own = extensionPackage(f.module)
            f.imports.filter { imp ->
                extensionMustNotImport.any { imp.name.startsWith(it) } ||
                    ((imp.name.startsWith("dev.toros.yde.format.") || imp.name.startsWith("dev.toros.yde.view.")) && !imp.name.startsWith("$own."))
            }.map { Hit(f, it.line, "import ${it.name}") }
        }
        val project = Regex("""project\(\s*":([\w-]+)"\s*\)""")
        val builds = extensionModules().flatMap { module ->
            val build = pseudo(module, "$module/build.gradle.kts")
            build.lines.flatMapIndexed { i, line ->
                project.findAll(line).map { it.groupValues[1] }.filter { it !in extensionMayUse }
                    .map { Hit(build, i + 1, "project(\":$it\") に依存している（使ってよいのは app-core とカーネル）") }.toList()
            }
        }
        return imports + builds
    }

    // ---------------------------------------------------------------- 規則 17

    /**
     * 拡張の形: README.md・共通の設定（yde.kotlin-module）を当てた build・テスト 1 つ以上・パッケージの根がモジュール名どおり・
     * 公開するトップレベルの宣言は差し込み口の実装（上位の型を持つ class / object）だけ。
     * ビューの種類の拡張（view-*）は、描画が読む定義と結果の型（class）も公開してよい（ADR-0014。描画は拡張の外にある）。
     */
    fun extensionShape(files: List<SourceFile>): List<Hit> {
        val hits = mutableListOf<Hit>()
        for (module in extensionModules()) {
            val readme = "$module/README.md"
            if (!root.resolve(readme).exists()) hits += Hit(pseudo(module, readme), 1, "README.md が無い", key = readme)
            val build = pseudo(module, "$module/build.gradle.kts")
            if ("id(\"yde.kotlin-module\")" !in build.code) hits += Hit(build, 1, "共通の設定 yde.kotlin-module を当てていない")
            val testDir = root.resolve("$module/src/test/kotlin")
            val tests = if (Files.isDirectory(testDir)) Files.walk(testDir).use { s -> s.asSequence().count { it.isRegularFile() && it.extension == "kt" } } else 0
            if (tests == 0) hits += Hit(build, 1, "テストが無い（$module/src/test/kotlin）", key = "$module:test")
        }
        val decl = Regex("""^(?!private |internal )(?:(?:public|data|sealed|enum|abstract|open)\s+)*(class|object|interface|fun|val|var|typealias)\s+(\w+)(.*)$""")
        for (f in files.filter { isExtension(it.module) }) {
            val own = extensionPackage(f.module)
            if (f.packageName != own && !f.packageName.startsWith("$own.")) {
                hits += Hit(f, 1, "パッケージ ${f.packageName} がモジュールの根 $own の外")
            }
            f.lines.forEachIndexed { i, line ->
                val m = decl.find(line) ?: return@forEachIndexed
                val (kind, name, rest) = m.destructured
                val implementation = (kind == "class" || kind == "object") && rest.substringBefore('{').contains(':')
                val viewType = f.module.startsWith("view-") && kind == "class"
                if (!implementation && !viewType) hits += Hit(f, i + 1, "公開の $kind $name は差し込み口の実装ではない（private か internal にする）")
            }
        }
        return hits
    }

    // ---------------------------------------------------------------- 規則 18

    /**
     * agent モジュールのファイルの間の循環。ファイル A が B を使う＝B のトップレベルの名前を、同じパッケージなら裸で、別のパッケージなら
     * import で書いている。強連結成分（2 ファイル以上）ごとに、含まれるファイルを 1 件ずつ返す。
     */
    fun agentFileCycles(files: List<SourceFile>): List<Hit> {
        val agent = files.filter { it.module == "agent" }
        val decl = Regex("""^(?!private )(?:(?:internal|public|data|sealed|enum|abstract|open|inline)\s+)*(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]+>\s+)?(\w+)""")
        val names = agent.associateWith { f -> f.lines.mapNotNull { decl.find(it)?.groupValues?.get(1) }.toSet() }
        val uses = agent.associateWith { a ->
            agent.filter { b -> b !== a && names.getValue(b).any { n ->
                if (a.packageName == b.packageName) Regex("""(?<![\w.])$n\b""").containsMatchIn(a.code)
                else a.imports.any { it.name == "${b.packageName}.$n" }
            } }
        }
        // Tarjan の強連結成分
        var counter = 0
        val index = HashMap<SourceFile, Int>()
        val low = HashMap<SourceFile, Int>()
        val stack = ArrayDeque<SourceFile>()
        val onStack = HashSet<SourceFile>()
        val components = mutableListOf<List<SourceFile>>()
        fun visit(v: SourceFile) {
            index[v] = counter; low[v] = counter; counter++
            stack.addLast(v); onStack += v
            for (w in uses.getValue(v)) {
                if (w !in index) { visit(w); low[v] = minOf(low.getValue(v), low.getValue(w)) }
                else if (w in onStack) low[v] = minOf(low.getValue(v), index.getValue(w))
            }
            if (low[v] == index[v]) {
                val component = mutableListOf<SourceFile>()
                while (true) { val w = stack.removeLast(); onStack -= w; component += w; if (w === v) break }
                if (component.size > 1) components += component
            }
        }
        agent.forEach { if (it !in index) visit(it) }
        return components.flatMap { c ->
            val loop = c.sortedBy { it.path }.joinToString(" ・ ") { it.name }
            c.map { Hit(it, 1, "循環: $loop") }
        }
    }
}
