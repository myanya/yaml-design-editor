package schema

import model.YNode
import model.getAt
import model.parseYPath
import model.scalarText
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

    private val PLACEHOLDER = Regex("""\{([^{}]+)}""")

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
        val byDeclaring = linkedMapOf<String, List<SchemaDef>>()
        for (template in declaring) {
            val derive = template.derive ?: continue
            val out = mutableListOf<SchemaDef>()
            for ((rel, file) in files) {
                if (!derive.matchesSource(rel)) continue
                val root = loader(file)
                if (root == null) {
                    errors += "${template.id}: 導出元文書「$rel」をパースできないため、この文書からの導出をスキップします"
                    continue
                }
                for (element in sourceElements(root, derive, rel, template.id, errors)) {
                    compileOne(template, derive, element, file, rel, usedIds, errors)?.let {
                        out += it
                        usedIds += it.id
                    }
                }
            }
            byDeclaring[template.id] = out
        }
        return Result(byDeclaring, errors)
    }

    /** 導出元文書内の導出単位（each 省略時は文書ルート、指定時はその配列の各要素）。 */
    private fun sourceElements(root: YNode, derive: DeriveDef, rel: String, templateId: String, errors: MutableList<String>): List<YNode.Map> {
        val nodes = if (derive.each == null) listOf(root)
        else when (val seq = getAt(root, parseYPath(derive.each))) {
            null -> emptyList()
            is YNode.Seq -> seq.items.toList()
            else -> {
                errors += "$templateId: 導出元文書「$rel」の ${derive.each} は配列ではありません"
                emptyList()
            }
        }
        return nodes.filterIsInstance<YNode.Map>()
    }

    /** 導出元要素1件 → 導出スキーマ1件。必須情報を欠く場合は理由をエラーへ積んで null。 */
    private fun compileOne(
        template: SchemaDef,
        derive: DeriveDef,
        element: YNode.Map,
        file: Path,
        rel: String,
        usedIds: Set<String>,
        errors: MutableList<String>,
    ): SchemaDef? {
        val id = runCatching { replacePlaceholders(derive.idPattern, element) }
            .getOrElse { errors += "${template.id}: 導出元「$rel」— ${it.message}"; return null }
        if (id in usedIds) {
            errors += "${template.id}: 導出スキーマID「$id」が重複しています（導出元: $rel）"
            return null
        }
        val match = runCatching { replacePlaceholders(derive.matchPattern, element) }
            .getOrElse { errors += "${template.id}: 導出元「$rel」— ${it.message}"; return null }
        val name = derive.namePattern?.let { pattern ->
            runCatching { replacePlaceholders(pattern, element) }.getOrDefault(id)
        } ?: id

        // フィールド一覧の写像。配列が未記入（工程未到達など）の場合は共通フィールドだけのスキーマになる
        val fieldsNode = getAt(element, parseYPath(derive.fieldsPath))
        val items = when (fieldsNode) {
            null -> emptyList()
            is YNode.Seq -> fieldsNode.items.toList()
            else -> {
                errors += "${template.id}: 導出元「$rel」の ${derive.fieldsPath} は配列ではありません"
                emptyList()
            }
        }
        val baseRoot = template.resolve(template.root) ?: template.root
        val commonKeys = baseRoot.fields.mapNotNull { it.key }.toMutableSet()
        val derivedFields = mutableListOf<FieldDef>()
        items.forEachIndexed { i, item ->
            if (item !is YNode.Map) {
                errors += "${template.id}: 導出元「$rel」の ${derive.fieldsPath}[$i] はobjectではありません"
                return@forEachIndexed
            }
            val key = scalarText(getAt(item, parseYPath(derive.keyPath)))
            if (key == null) {
                errors += "${template.id}: 導出元「$rel」の ${derive.fieldsPath}[$i] に ${derive.keyPath}（フィールドキー）がありません"
                return@forEachIndexed
            }
            if (!commonKeys.add(key)) {
                errors += "${template.id}: 導出スキーマ「$id」のフィールド「$key」が重複しています（導出元: $rel）"
                return@forEachIndexed
            }
            val type = derive.typeRule?.let { rule ->
                val raw = scalarText(getAt(item, parseYPath(rule.path)))
                val typeId = raw?.let { rule.map[it] ?: it } ?: rule.default
                FieldType.entries.firstOrNull { it.id == typeId } ?: run {
                    errors += "${template.id}: 導出元「$rel」の型「$raw」を解釈できません（${rule.default} として扱います）"
                    FieldType.entries.first { it.id == rule.default }
                }
            } ?: FieldType.STRING
            derivedFields += FieldDef(
                key = key,
                label = derive.labelPath?.let { scalarText(getAt(item, parseYPath(it))) },
                type = type,
                required = derive.requiredPath?.let { (getAt(item, parseYPath(it)) as? YNode.Scalar)?.value } == true,
                description = derive.descriptionPath?.let { scalarText(getAt(item, parseYPath(it))) },
                definitionScope = template.id,
            )
        }
        return SchemaDef(
            id = id,
            name = name,
            description = template.description,
            match = match,
            root = baseRoot.copy(fields = baseRoot.fields + derivedFields),
            definitions = template.definitions,
            coverage = template.coverage,
            phases = template.phases,
            source = file,
            kind = SchemaKind.DOCUMENT,
            derive = null,
            definitionCatalog = template.definitionCatalog,
        )
    }

    /**
     * `{パス}` を導出元要素のスカラー値で置換する。値がなければ例外（呼び出し側でエラー化）。
     * `,` を含む `{a,b}` は glob の選択構文としてそのまま残す（match パターンと共存させるため）。
     */
    private fun replacePlaceholders(pattern: String, element: YNode): String =
        PLACEHOLDER.replace(pattern) { m ->
            val path = m.groupValues[1]
            if (',' in path) return@replace m.value
            scalarText(getAt(element, parseYPath(path)))
                ?: throw IllegalArgumentException("プレースホルダ {$path} に対応する値が導出元要素にありません")
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
