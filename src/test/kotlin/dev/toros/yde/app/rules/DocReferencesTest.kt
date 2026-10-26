package dev.toros.yde.app.rules

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 今の姿の文書（CLAUDE.md・docs/README.md・CONVENTIONS・FEATURES・CORE-CALCULUS・DESIGN-CHECK-CATALOG・architecture/・manual/）が指す
 * ソースファイル名・テスト名・「ファイル名＋見出し」が実在することを確かめる。
 * 形の検査（チケット番号・リンク切れ・字数）は scripts/docs-lint.sh が持ち、ここは中身の参照だけを見る。
 * 落ちたら、参照を今の名前に直すか、その行を FEATURES から外す（経緯は archive と git log にある）。
 */
class DocReferencesTest {
    private val root: Path = SourceScanner.repoRoot
    private val docs: Path = root.resolve("docs")

    /** 参照元（今の姿の文書）。台帳（ROADMAP・BACKLOG・UX-SCENARIOS）・決定（decisions/）・記録（archive/）は経緯を持つので見ない。 */
    private val sources: List<Path> by lazy {
        val fixed = listOf("CLAUDE.md", "docs/README.md", "docs/CONVENTIONS.md", "docs/FEATURES.md", "docs/CORE-CALCULUS.md", "docs/DESIGN-CHECK-CATALOG.md")
            .map { root.resolve(it) }
        val dirs = listOf("architecture", "manual").flatMap { d -> markdownIn(docs.resolve(d)) }
        (fixed + dirs).filter { Files.exists(it) }
    }

    private fun markdownIn(dir: Path): List<Path> =
        Files.list(dir).use { s -> s.asSequence().filter { it.isRegularFile() && it.extension == "md" }.sortedBy { it.name }.toList() }

    private fun kotlinFilesUnder(dir: Path): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.walk(dir).use { s -> s.asSequence().filter { it.isRegularFile() && it.extension == "kt" }.toList() }

    private fun rel(p: Path): String = root.relativize(p).toString().replace('\\', '/')

    /** 5 モジュールの main と test の Kotlin ファイル名（裸の名前）。 */
    private val kotlinFileNames: Set<String> by lazy {
        val names = mutableSetOf<String>()
        for (dir in SourceScanner.modules.values) {
            for (base in listOf(dir, dir.replace("/main/", "/test/"))) {
                for (f in kotlinFilesUnder(root.resolve(base))) names += f.name
            }
        }
        names
    }

    /** test のソースで宣言しているクラス名と object 名。 */
    private val declaredTestNames: Set<String> by lazy {
        val names = mutableSetOf<String>()
        for (dir in SourceScanner.modules.values) {
            for (f in kotlinFilesUnder(root.resolve(dir.replace("/main/", "/test/")))) {
                for (m in DECL.findAll(f.readText())) names += m.groupValues[1]
            }
        }
        names
    }

    /** docs/ 配下の全文書の見出し（正規化済み）。鍵は絶対パス。 */
    private val headings: Map<Path, Set<String>> by lazy {
        val map = mutableMapOf<Path, Set<String>>()
        val files = Files.walk(docs).use { s -> s.asSequence().filter { it.isRegularFile() && it.extension == "md" }.toList() }
        for (f in files) {
            val hs = mutableSetOf<String>()
            for (line in f.readText().lines()) {
                val m = HEADING.find(line) ?: continue
                hs += normalizeHeading(m.groupValues[1])
            }
            map[f.toAbsolutePath().normalize()] = hs
        }
        map
    }

    /** 見出しの照合用の形。バッククォート・先頭の番号・末尾の補足（（…）や ——…）を落とす。 */
    private fun normalizeHeading(h: String): String =
        h.replace("`", "").replace(Regex("""^\d+\.\s*"""), "").replace(Regex("""(（|——).*$"""), "").trim().replace(Regex("""\s+"""), " ")

    private fun failures(label: String, items: List<String>) {
        assertTrue(items.isEmpty(), buildString {
            appendLine("$label が実在しません（参照を今の名前に直すか、行を外してください）:")
            items.forEach { appendLine("  $it") }
        })
    }

    @Test
    fun `今の姿の文書が指す Kotlin のファイル名が実在する`() {
        val missing = mutableListOf<String>()
        for (src in sources) {
            src.readText().lines().forEachIndexed { i, line ->
                for (m in KT_TOKEN.findAll(line)) {
                    val token = m.groupValues[1]
                    if ('*' in token) continue
                    val name = token.substringAfterLast('/')
                    if (name !in kotlinFileNames) missing += "${rel(src)}:${i + 1}: $name"
                }
            }
        }
        failures("文書が指す .kt ファイル", missing)
    }

    @Test
    fun `FEATURES と設計文書が指すテスト名が実在する`() {
        val missing = mutableListOf<String>()
        for (src in sources) {
            if (src.name != "FEATURES.md" && src.parent.name != "architecture") continue
            src.readText().lines().forEachIndexed { i, line ->
                for (m in TEST_NAME.findAll(line)) {
                    val name = m.groupValues[1]
                    if (name !in declaredTestNames) missing += "${rel(src)}:${i + 1}: $name"
                }
            }
        }
        failures("文書が指すテスト名", missing)
    }

    @Test
    fun `「ファイル名＋見出し」の参照が実在する`() {
        val broken = mutableListOf<String>()
        for (src in sources) {
            src.readText().lines().forEachIndexed { i, line ->
                for (m in HEADING_REF.findAll(line)) {
                    val file = m.groupValues[1].let { if (it.endsWith(".md")) it else "$it.md" }
                    val wanted = normalizeHeading(m.groupValues[2])
                    val target = resolveDoc(src, file)
                    val hs = target?.let { headings[it.toAbsolutePath().normalize()] }
                    when {
                        hs == null -> broken += "${rel(src)}:${i + 1}: $file が無い"
                        hs.none { it == wanted || it.startsWith(wanted) } -> broken += "${rel(src)}:${i + 1}: $file「${m.groupValues[2]}」の見出しが無い"
                    }
                }
            }
        }
        failures("「ファイル名＋見出し」の参照", broken)
    }

    /** 参照先の文書。ディレクトリ付きは docs/ から。裸の名前は参照元と同じ場所 → docs/ → リポジトリの根 → docs の中で名前が 1 つに決まるもの、の順に探す。 */
    private fun resolveDoc(src: Path, file: String): Path? {
        if ('/' in file) return docs.resolve(file).takeIf { Files.exists(it) }
        listOf(src.parent.resolve(file), docs.resolve(file), root.resolve(file)).firstOrNull { Files.exists(it) }?.let { return it }
        val same = headings.keys.filter { it.name == file && "archive" !in it.toString() }
        return same.singleOrNull()
    }

    private companion object {
        val DECL = Regex("""\b(?:class|object)\s+([A-Z][A-Za-z0-9_]*)""")
        val HEADING = Regex("""^#+\s+(.*?)\s*$""")

        /** `core/Cli.kt` のような語（.kts は除く）。glob（星印を含む語）は呼び出し側で除く。KDoc の中に「スラッシュ＋星」を書くと入れ子のコメントになるので書かない */
        val KT_TOKEN = Regex("""([\w\-/*.]+\.kt)(?![A-Za-z])""")
        val TEST_NAME = Regex("""\b([A-Z][A-Za-z0-9]*Test)\b""")

        /** `architecture/checks.md「4 つの結果」`・`CONVENTIONS「モジュールの規約」`・`README.md「規模の計測」` の形。 */
        val HEADING_REF = Regex("""((?:[\w\-]+/)?[A-Za-z][\w\-]*\.md|CORE-CALCULUS|DESIGN-CHECK-CATALOG|CONVENTIONS|FEATURES)「([^」]+)」""")
    }
}
