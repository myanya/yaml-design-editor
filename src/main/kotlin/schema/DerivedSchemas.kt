package schema

import model.YNode
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 派生スキーマのコンパイル（R11-03）。`derive:` を宣言したスキーマ（テンプレート）と導出元文書から、
 * 検証に使う SchemaDef をロード時に組み立てる。SchemaLoader の第3段階（パース→リンク→導出）として呼ばれる。
 * - 導出スキーマはファイルとして存在しない読み取り専用の成果物（source は導出元文書を指す）
 * - 導出元文書の読み取りは寛容パース（parseFirst）の読み取り専用経路（ARCHITECTURE §4.17）
 * - 導出元文書の変更での再コンパイルは Workspace.noteDiskChanges が全再構築へ振り分ける（§4.23）
 */
object DerivedSchemas {

    data class Result(
        /** 宣言スキーマID → 導出されたスキーマ（宣言スキーマの直後に挿入される）。 */
        val byDeclaring: Map<String, List<SchemaDef>>,
        val errors: List<String>,
    )

    /**
     * 展開の中核は semantic-core の SchemaDerivation（C-2 で移動。reader / session と共有）。
     * ここはファイル走査（導出元の列挙）と SchemaDef への包装だけを行うシェル。
     */
    fun compile(
        schemas: List<SchemaDef>,
        workspace: Path,
        layout: WorkspaceLayout = WorkspaceLayout.LEGACY,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
    ): Result {
        val declaring = schemas.filter { it.derive != null }
        if (declaring.isEmpty()) return Result(emptyMap(), emptyList())
        val errors = mutableListOf<String>()
        val usedIds = schemas.map { it.id }.toMutableSet()
        val files = listYamlFiles(workspace, layout)
        val fileByRel = files.toMap()
        val byDeclaring = linkedMapOf<String, List<SchemaDef>>()
        for (template in declaring) {
            val derive = template.derive ?: continue
            val baseRoot = template.resolve(template.root) ?: template.root
            val sources = files.filter { (rel, _) -> derive.matchesSource(rel) }
                .map { (rel, file) -> rel to loader(file) }
            byDeclaring[template.id] = SchemaDerivation.compile(template.id, baseRoot, derive, sources, usedIds, errors)
                .map { d ->
                    // 導出元の語彙（consistency）は引き継ぐ。展開後の検査はスキーマIDで文書を指すため、派生スキーマのIDで展開し直す
                    // 専用チェッカーを使う検査（遷移検証）と「すべての要素について」の検査（網羅マトリクス）は、
                    // 対象の母集団を自分で宣言するため引き継がない（以前から、派生スキーマは flow / matrix を引き継いでいない）
                    val inherited = template.vocabularySource?.checksFor(d.id).orEmpty()
                        .filter { !it.usesChecker && !it.raw.containsKey("forAll") }
                    SchemaDef(
                        id = d.id,
                        name = d.name,
                        description = template.description,
                        match = d.match,
                        root = d.root,
                        definitions = template.definitions,
                        constraints = inherited.map {
                            ConstraintRule.parse(it.raw, it.origin).copy(origin = RuleOrigin(d.id, it.origin, it.index))
                        },
                        expandedChecks = inherited,
                        phases = template.phases,
                        source = fileByRel.getValue(d.sourceRel),
                        kind = SchemaKind.DOCUMENT,
                        derive = null,
                        definitionCatalog = template.definitionCatalog,
                    )
                }
        }
        return Result(byDeclaring, errors)
    }

    /** 導出元にできるYAML文書＝台帳の文書のみ（予約領域・隠しファイル・台帳外を除く）。相対パスは '/' 区切り。 */
    private fun listYamlFiles(workspace: Path, layout: WorkspaceLayout): List<Pair<String, Path>> {
        if (!Files.isDirectory(workspace)) return emptyList()
        val result = mutableListOf<Pair<String, Path>>()
        Files.walk(workspace).use { stream ->
            stream.forEach { p ->
                if (!Files.isRegularFile(p)) return@forEach
                val fileName = p.fileName.toString()
                if (fileName.startsWith(".")) return@forEach
                if (!fileName.endsWith(".yaml") && !fileName.endsWith(".yml")) return@forEach
                val rel = workspace.relativize(p).toString().replace('\\', '/')
                if (rel.startsWith("_") || rel.startsWith(".")) return@forEach
                if (!layout.inLedger(rel)) return@forEach
                result += rel to p
            }
        }
        return result.sortedBy { it.first }
    }
}
