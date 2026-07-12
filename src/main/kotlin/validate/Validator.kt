package validate

import app.OpenDocument
import index.DocScanner
import index.IdIndex
import model.YNode
import model.YPath
import model.pretty
import model.scalarText
import schema.FieldDef
import schema.FieldType

enum class Severity { ERROR, WARNING }

data class Issue(
    val severity: Severity,
    val message: String,
    val path: YPath,
)

/**
 * 開いているドキュメントの検証。
 * - スキーマ適合（必須／型／enum／日付形式）
 * - 参照解決（索引 + 同一ドキュメント内の未保存 ID を考慮）
 * - ID 重複（ドキュメント内、およびワークスペース全体）
 */
object Validator {

    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    fun validate(doc: OpenDocument, indexObj: IdIndex): List<Issue> {
        val root = doc.root
            ?: return listOf(Issue(Severity.ERROR, "YAML パースエラー: ${doc.parseError ?: "不明"}", emptyList()))

        val issues = mutableListOf<Issue>()

        // 1) スキーマ検証
        doc.schema?.let { checkField(it.root, root, emptyList(), issues, it) }

        // 2) ID / 参照の検証
        val (localIds, localRefs) = DocScanner.scan(root, doc.file, doc.schema)
        val localIdSet = localIds.map { it.id }.toSet()

        // ドキュメント内の ID 重複
        localIds.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, list) ->
            list.forEach { e ->
                issues += Issue(Severity.ERROR, "ID「$id」がこのファイル内で重複しています", e.path)
            }
        }
        // 他ファイルとの ID 重複（索引はディスク上の状態）
        for (e in localIds) {
            val others = indexObj.entries[e.id].orEmpty().filter { it.file != doc.file }
            if (others.isNotEmpty()) {
                val where = others.joinToString(", ") { it.file.fileName.toString() }
                issues += Issue(Severity.ERROR, "ID「${e.id}」が他ファイルでも定義されています（$where）", e.path)
            }
        }
        // 未解決参照
        for (r in localRefs) {
            val resolved = r.targetId in localIdSet || indexObj.entries.containsKey(r.targetId)
            if (!resolved) {
                issues += Issue(
                    Severity.ERROR,
                    "参照「${r.targetId}」（${r.path.pretty()}）を解決できません",
                    r.path,
                )
            }
        }
        return issues
    }

    private fun checkField(field: FieldDef, node: YNode?, path: YPath, issues: MutableList<Issue>, schema: schema.SchemaDef) {
        val f = schema.resolve(field) ?: field
        val label = f.displayLabel
        if (node == null) {
            if (f.required) issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
            return
        }
        when (f.type) {
            FieldType.OBJECT -> {
                if (node !is YNode.Map) {
                    issues += Issue(Severity.ERROR, "「$label」はオブジェクトである必要があります", path)
                } else {
                    for (ch in f.fields) {
                        val key = ch.key ?: continue
                        checkField(ch, node.entries[key], path + key, issues, schema)
                    }
                }
            }
            FieldType.ARRAY -> {
                if (node !is YNode.Seq) {
                    issues += Issue(Severity.ERROR, "「$label」は配列である必要があります", path)
                } else {
                    val itemDef = f.item ?: return
                    node.items.forEachIndexed { i, child ->
                        checkField(itemDef.copy(label = "$label[$i]"), child, path + i, issues, schema)
                    }
                }
            }
            FieldType.MAP -> {
                if (node !is YNode.Map) {
                    issues += Issue(Severity.ERROR, "「$label」はMapである必要があります", path)
                } else {
                    val valueDef = f.value ?: return
                    node.entries.forEach { (key, value) ->
                        checkField(valueDef.copy(label = "$label.$key"), value, path + key, issues, schema)
                    }
                }
            }
            FieldType.ENUM -> {
                val v = scalarText(node)
                if (v == null) {
                    if (f.required) issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
                } else if (v !in f.values) {
                    issues += Issue(Severity.ERROR, "「$label」の値「$v」は選択肢 ${f.values} に含まれていません", path)
                }
            }
            FieldType.INT -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Int || v is Long || (v is String && v.toLongOrNull() != null)
                if (!ok) issues += Issue(Severity.ERROR, "「$label」は整数である必要があります（現在値: $v）", path)
            }
            FieldType.NUMBER -> {
                val v = (node as? YNode.Scalar)?.value
                val ok = v == null || v is Number || (v is String && v.toDoubleOrNull() != null)
                if (!ok) issues += Issue(Severity.ERROR, "「$label」は数値である必要があります（現在値: $v）", path)
            }
            FieldType.BOOLEAN -> {
                val v = (node as? YNode.Scalar)?.value
                if (v != null && v !is Boolean) {
                    issues += Issue(Severity.ERROR, "「$label」は真偽値である必要があります（現在値: $v）", path)
                }
            }
            FieldType.DATE -> {
                val v = scalarText(node)
                if (v != null && !DATE_RE.matches(v)) {
                    issues += Issue(Severity.WARNING, "「$label」は YYYY-MM-DD 形式を推奨します（現在値: $v）", path)
                }
            }
            FieldType.ID -> {
                val v = scalarText(node)
                if (v == null && f.required) {
                    issues += Issue(Severity.ERROR, "必須項目「$label」（ID）が未設定です", path)
                }
            }
            FieldType.REF, FieldType.STRING, FieldType.TEXT -> {
                if (node !is YNode.Scalar) {
                    issues += Issue(Severity.WARNING, "「$label」はスカラー値を想定しています", path)
                } else if (f.required && scalarText(node) == null) {
                    issues += Issue(Severity.ERROR, "必須項目「$label」が未設定です", path)
                }
            }
        }
    }
}
