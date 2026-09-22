package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText

/**
 * 派生スキーマの宣言と展開コア（R11-03。移行後リファクタリング C-2 で root から semantic-core へ移動。
 * glob 照合（globToRegex）が semantic-core 所在のため置き場もここ）。
 * `derive:` を宣言したスキーマ（テンプレート）と導出元文書から、検証スキーマの実体
 * （ID・表示名・適用先 glob・フィールド追加済み root）を組み立てる。
 * - SchemaDef（root）への包装は schema.DerivedSchemas、SchemaTree（コア reader / session）への
 *   包装は workspace.WorkspaceReader が行う（実装はこの1箇所）
 * - 写像項目を増やすときは parse・compile・メタスキーマ（BuiltinSchemas）を対で更新する（§4.23）
 */

/**
 * 型を決める写像（R11-03）: 導出元要素の `path` の値を `map`（値辞書）で FieldType の id に変換する。
 * 語彙（path / map / default）は skill の列マッピング（SK-02）と揃える。
 */
data class DeriveTypeRule(
    val path: String,                        // 型を決める値のパス（導出元要素からのドット区切り）
    val map: Map<String, String> = emptyMap(), // 値辞書（例 varchar → string）
    val default: String = FieldType.STRING.id, // 辞書に無い・未記入のときの型
)

/**
 * 派生スキーマの宣言（R11-03）: 「テーブル定義書のような実質スキーマの文書」から検証スキーマを
 * ロード時にコンパイルする写像。宣言したスキーマ自身は文書にマッチせず、テンプレートに徹する。
 */
data class DeriveDef(
    val from: String,                 // 導出元文書の glob（予約ディレクトリは不可）
    val each: String? = null,         // 1文書内の複数要素から導出する場合の配列パス（null = 文書ルート）
    val idPattern: String,            // 導出スキーマのID（{パス} が導出元要素の値で置換される）
    val namePattern: String? = null,  // 導出スキーマの表示名（同上）
    val matchPattern: String,         // 導出スキーマの適用先 glob（同上）
    val fieldsPath: String,           // フィールド一覧にする配列のパス（導出元要素から相対）
    val keyPath: String,              // 各要素のこの値 → field.key
    val labelPath: String? = null,    // 任意 → field.label
    val descriptionPath: String? = null, // 任意 → field.description
    val requiredPath: String? = null, // 任意 → field.required（真偽値）
    val typeRule: DeriveTypeRule? = null, // 任意 → field.type（省略時は string）
) {
    private val fromRegex: Regex by lazy { globToRegex(from) }

    /** ワークスペース相対パスが導出元文書か（この文書の変更はスキーマ再構築の契機になる）。 */
    fun matchesSource(relPath: String): Boolean = fromRegex.matches(relPath)
}

object SchemaDerivation {

    private val PLACEHOLDER = Regex("""\{([^{}]+)}""")

    /** 展開された派生スキーマ1件（包装前の実体）。 */
    data class Derived(
        val id: String,
        val name: String,
        val match: String,
        /** テンプレート root（解決済み）＋導出フィールドの root。 */
        val root: FieldDef,
        /** 導出元文書（ワークスペース相対パス）。 */
        val sourceRel: String,
    )

    /** `derive:` 宣言のパース（旧 SchemaLoader.parseDerive）。不正な宣言は例外（呼び出し側でエラー化）。 */
    fun parse(raw: Any?): DeriveDef? {
        if (raw == null) return null
        val m = raw as? Map<*, *> ?: error("derive は object である必要があります")
        val from = m["from"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.from（導出元文書の glob）が必要です")
        val fromNorm = from.replace('\\', '/').trimStart('/')
        require(!fromNorm.startsWith("_") && !fromNorm.startsWith(".")) { "derive.from に予約ディレクトリ・隠しディレクトリは指定できません: $from" }
        val fieldsMap = m["fields"] as? Map<*, *> ?: error("derive.fields（フィールドの写像）が必要です")
        val typeRule = when (val rawType = fieldsMap["type"]) {
            null -> null
            is Map<*, *> -> {
                val path = rawType["path"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: error("derive.fields.type には path（型を決める値のパス）が必要です")
                val dict = (rawType["map"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
                val default = rawType["default"]?.toString() ?: FieldType.STRING.id
                (dict.values + default).forEach { typeId ->
                    require(FieldType.entries.any { it.id == typeId }) { "derive.fields.type の型「$typeId」は未知の型です" }
                }
                DeriveTypeRule(path = path, map = dict, default = default)
            }
            else -> error("derive.fields.type は object（path / map / default）である必要があります")
        }
        return DeriveDef(
            from = from,
            each = m["each"]?.toString()?.takeIf { it.isNotBlank() },
            idPattern = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.id（導出スキーマのIDパターン）が必要です"),
            namePattern = m["name"]?.toString(),
            matchPattern = m["match"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.match（導出スキーマの適用先 glob）が必要です"),
            fieldsPath = fieldsMap["source"]?.toString()?.takeIf { it.isNotBlank() }
                ?: error("derive.fields.source（フィールドにする配列のパス）が必要です"),
            keyPath = fieldsMap["key"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.fields.key（キーの値パス）が必要です"),
            labelPath = fieldsMap["label"]?.toString(),
            descriptionPath = fieldsMap["description"]?.toString(),
            requiredPath = fieldsMap["required"]?.toString(),
            typeRule = typeRule,
        )
    }

    /**
     * テンプレート1件を導出元文書列から展開する（旧 DerivedSchemas の中核）。
     * - sources: （相対パス, 文書ルート。null = パース不能）の相対パス順
     * - baseRoot: `$ref` 解決済みのテンプレート root
     * - usedIds: ID重複検査（呼び出し側が全スキーマIDで初期化し、生成分がここで追記される）
     */
    fun compile(
        templateId: String,
        baseRoot: FieldDef,
        derive: DeriveDef,
        sources: List<Pair<String, YNode?>>,
        usedIds: MutableSet<String>,
        errors: MutableList<String>,
    ): List<Derived> {
        val out = mutableListOf<Derived>()
        for ((rel, root) in sources) {
            if (root == null) {
                errors += "$templateId: 導出元文書「$rel」をパースできないため、この文書からの導出をスキップします"
                continue
            }
            for (element in sourceElements(root, derive, rel, templateId, errors)) {
                compileOne(templateId, baseRoot, derive, element, rel, usedIds, errors)?.let {
                    out += it
                    usedIds += it.id
                }
            }
        }
        return out
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

    /** 導出元要素1件 → 派生スキーマ1件。必須情報を欠く場合は理由をエラーへ積んで null。 */
    private fun compileOne(
        templateId: String,
        baseRoot: FieldDef,
        derive: DeriveDef,
        element: YNode.Map,
        rel: String,
        usedIds: Set<String>,
        errors: MutableList<String>,
    ): Derived? {
        val id = runCatching { replacePlaceholders(derive.idPattern, element) }
            .getOrElse { errors += "$templateId: 導出元「$rel」— ${it.message}"; return null }
        if (id in usedIds) {
            errors += "$templateId: 導出スキーマID「$id」が重複しています（導出元: $rel）"
            return null
        }
        val match = runCatching { replacePlaceholders(derive.matchPattern, element) }
            .getOrElse { errors += "$templateId: 導出元「$rel」— ${it.message}"; return null }
        val name = derive.namePattern?.let { pattern ->
            runCatching { replacePlaceholders(pattern, element) }.getOrDefault(id)
        } ?: id

        // フィールド一覧の写像。配列が未記入（工程未到達など）の場合は共通フィールドだけのスキーマになる
        val fieldsNode = getAt(element, parseYPath(derive.fieldsPath))
        val items = when (fieldsNode) {
            null -> emptyList()
            is YNode.Seq -> fieldsNode.items.toList()
            else -> {
                errors += "$templateId: 導出元「$rel」の ${derive.fieldsPath} は配列ではありません"
                emptyList()
            }
        }
        val commonKeys = baseRoot.fields.mapNotNull { it.key }.toMutableSet()
        val derivedFields = mutableListOf<FieldDef>()
        items.forEachIndexed { i, item ->
            if (item !is YNode.Map) {
                errors += "$templateId: 導出元「$rel」の ${derive.fieldsPath}[$i] はobjectではありません"
                return@forEachIndexed
            }
            val key = scalarText(getAt(item, parseYPath(derive.keyPath)))
            if (key == null) {
                errors += "$templateId: 導出元「$rel」の ${derive.fieldsPath}[$i] に ${derive.keyPath}（フィールドキー）がありません"
                return@forEachIndexed
            }
            if (!commonKeys.add(key)) {
                errors += "$templateId: 導出スキーマ「$id」のフィールド「$key」が重複しています（導出元: $rel）"
                return@forEachIndexed
            }
            val type = derive.typeRule?.let { rule ->
                val raw = scalarText(getAt(item, parseYPath(rule.path)))
                val typeId = raw?.let { rule.map[it] ?: it } ?: rule.default
                FieldType.entries.firstOrNull { it.id == typeId } ?: run {
                    errors += "$templateId: 導出元「$rel」の型「$raw」を解釈できません（${rule.default} として扱います）"
                    FieldType.entries.first { it.id == rule.default }
                }
            } ?: FieldType.STRING
            derivedFields += FieldDef(
                key = key,
                type = type,
                required = derive.requiredPath?.let { (getAt(item, parseYPath(it)) as? YNode.Scalar)?.value } == true,
                definitionScope = templateId,
                hints = FieldHints(
                    label = derive.labelPath?.let { scalarText(getAt(item, parseYPath(it))) },
                    description = derive.descriptionPath?.let { scalarText(getAt(item, parseYPath(it))) },
                ),
            )
        }
        return Derived(
            id = id,
            name = name,
            match = match,
            root = baseRoot.copy(fields = baseRoot.fields + derivedFields),
            sourceRel = rel,
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
}
