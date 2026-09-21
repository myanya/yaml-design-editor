package validate

import core.Bench
import core.Workspace
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 構造の検証の経路の切り替え（P27 段C の C-1・意図的差分 D-30）の安全網: ワークスペースの全文書（スキーマ文書への
 * メタスキーマの適用を含む）で、導出した構造のスキーマによる検証（SchemaDef.structures）の指摘が、利用者が書いた
 * スキーマの木をそのまま読む旧い検証（StructuralValidator）の指摘と、並び順まで一致することを確かめる。
 * 旧い検証は、段C の C-2 で利用者の木と一緒に yaml-core から無くなる——それまでの、比べる相手。
 */
class StructureParityTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-structure-parity")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `全文書で 導出した構造のスキーマの検証結果が 旧い検証と一致する`() {
        Bench.generate(dir, modules = 2)
        // 構造の指摘がひととおり出るように壊す（必須の欠落・選択肢に無い値・型の違い・未定義の項目）
        fun rewrite(rel: String, transform: (String) -> String) =
            Files.writeString(dir.resolve(rel), transform(Files.readString(dir.resolve(rel))))
        rewrite("m001/screens/SCR-1-1.yaml") { it.replace(Regex("""max_length: \d+ }"""), "max_length: たくさん, extra: 1 }") }
        rewrite("m001/usecases.yaml") { it.replaceFirst(Regex("""name: [^,}\n]+"""), "nam: 名前のつづり違い") }
        rewrite("m002/tests.yaml") { "unknown_top: 1\n$it" }

        val ws = Workspace.open(dir)
        val files = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".yaml") }.toList() }
        var documents = 0
        var issues = 0
        for (file in files) {
            val schema = ws.schemaFor(file) ?: continue
            val root = YamlIO.parseFirst(Files.readString(file)) ?: continue
            val expected = StructuralValidator.validate(root, schema.structural)
            assertEquals(expected, schema.structures.validate(root), ws.relPath(file))
            documents++
            issues += expected.size
        }
        assertTrue(documents > 30, "スキーマ文書（メタスキーマの適用）を含めて比べていること（$documents 件）")
        assertTrue(issues >= 3, "構造の指摘のある文書を比べていること（$issues 件）")
    }
}
