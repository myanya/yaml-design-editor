package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaScaffold

/** 新しく作る文書のひな型: 作り先（ワークスペース相対パス）と、初期の内容。 */
data class DocumentScaffold(val rel: String, val root: YNode)

/**
 * 新しい文書のひな型を組み立てる（P27 段D の D-c で、root の core/Views から移した）。作成そのものは呼び出し側が行う
 * （アプリはセッションの CreateDocument トランザクション、エージェントは create_document）。
 */
object DocumentScaffolds {

    /**
     * filePattern（ビューの detail / newRow のファイルモード・検査の対応「詳細文書を作る」が共用）と ID から、新しい文書の内容を
     * 組み立てる。スキーマは `{id}` を置き換えた後のパスに合うものを使い、必須の項目の初期値と、ルート直下の ID の項目へ ID を設定する。
     */
    fun scaffold(pattern: String, id: String, schemas: List<WorkspaceSchema>): DocumentScaffold {
        require(id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..")) {
            "ファイル名に使用できないIDです: $id"
        }
        val rel = pattern.replace("{id}", id)
        val tree = schemas.firstOrNull { it.matches(rel) }?.tree
        val root = tree?.let { SchemaScaffold.defaultTree(it) } ?: YNode.Map()
        (root as? YNode.Map)?.let { map ->
            val idKey = tree?.let { t ->
                (t.resolve(t.root) ?: t.root).fields.firstOrNull { (t.resolve(it) ?: it).type == FieldType.ID }?.key
            } ?: "id"
            map.entries[idKey] = YNode.Scalar(id)
        }
        return DocumentScaffold(rel, root)
    }

    /**
     * 詳細文書の存在の検査の作成導線（R28-05a・UX-97）: 詳細スキーマの match（glob）から、`{id}` を置き換える形のファイルパターンを導く。
     * 対応するのは、ファイル名の部分が「*.yaml」「*.yml」「*.{yaml,yml}」で、ディレクトリの部分にワイルドカードを含まない単純な形だけ
     * （それ以外は null＝作り先を機械的に決められない）。
     */
    fun detailPatternFromMatch(match: String): String? {
        val slash = match.lastIndexOf('/')
        val dir = if (slash >= 0) match.substring(0, slash + 1) else ""
        val name = match.substring(slash + 1)
        if (dir.any { it == '*' || it == '?' || it == '{' }) return null
        val m = Regex("""^\*\.(yaml|yml|\{yaml,yml\}|\{yml,yaml\})$""").matchEntire(name) ?: return null
        val ext = m.groupValues[1].takeUnless { it.startsWith("{") } ?: "yaml"
        return "$dir{id}.$ext"
    }
}
