package core

import model.YNode
import model.YPath
import model.pretty
import schema.SchemaLoader
import schema.globToRegex
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 構造検索（R1-04）。
 * - refsTo / unresolvedRefs を指定すると「参照」を検索する（text は対象IDへの部分一致になる）
 * - それ以外は本文検索: スカラー値・キー名への部分一致（大文字小文字を区別しない）
 * - schemaIds / fileGlob / pathGlob は共通の絞り込み条件（AND）
 */
data class SearchQuery(
    val text: String? = null,
    val schemaIds: List<String> = emptyList(),
    val fileGlob: String? = null,     // ワークスペース相対パスの glob
    val pathGlob: String? = null,     // pretty パス（例 items[*].title）への * ? マッチ
    val refsTo: String? = null,       // この ID への参照を検索
    val unresolvedRefs: Boolean = false, // 未解決参照のみを検索
    val limit: Int = 50,
)

data class SearchHit(
    val file: String,
    val path: String,        // pretty 形式
    val kind: String,        // value / key / ref / unresolved-ref
    val excerpt: String,
)

object Search {

    /**
     * 検索を実行する。files / index / loader を差し替えると、エージェントのステージング
     * （メモリ上の編集を含む状態）に対しても同じ検索が使える。
     */
    fun run(
        ws: Workspace,
        query: SearchQuery,
        files: List<Path> = ws.files,
        index: index.IdIndex = ws.index,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
    ): List<SearchHit> {
        val fileRegex = query.fileGlob?.let { globToRegex(it) }
        val pathRegex = query.pathGlob?.let { pathGlobToRegex(it) }

        fun fileMatches(file: Path): Boolean {
            val rel = ws.relPath(file)
            if (ws.layout.indexExcluded(rel)) return false
            if (fileRegex != null && !fileRegex.matches(rel)) return false
            if (query.schemaIds.isNotEmpty() && ws.schemaFor(file)?.id !in query.schemaIds) return false
            return true
        }

        // 参照の検索（索引ベース）
        if (query.refsTo != null || query.unresolvedRefs) {
            return index.refs.asSequence()
                .filter { fileMatches(ws.fileOf(it.document)) }
                .filter { query.refsTo == null || it.targetId == query.refsTo }
                .filter { query.text == null || it.targetId.contains(query.text, ignoreCase = true) }
                .filter { pathRegex == null || pathRegex.matches(it.path.pretty()) }
                .mapNotNull { ref ->
                    val unresolved = index.resolve(ref).isEmpty()
                    if (query.unresolvedRefs && !unresolved) return@mapNotNull null
                    SearchHit(
                        file = ref.document.value,
                        path = ref.path.pretty(),
                        kind = if (unresolved) "unresolved-ref" else "ref",
                        excerpt = buildString {
                            append(ref.viaKey).append(" -> ").append(ref.targetId)
                            ref.relation?.let { append("（関係: ").append(it).append("）") }
                        },
                    )
                }
                .take(query.limit)
                .toList()
        }

        // 本文の検索（キー・スカラー値）
        val needle = query.text ?: return emptyList()
        val hits = mutableListOf<SearchHit>()
        for (file in files) {
            if (hits.size >= query.limit) break
            if (!fileMatches(file)) continue
            val root = loader(file) ?: continue
            walk(root, emptyList()) { path, key, scalar ->
                if (hits.size >= query.limit) return@walk
                if (pathRegex != null && !pathRegex.matches(path.pretty())) return@walk
                val value = scalar?.value?.toString()
                when {
                    value != null && value.contains(needle, ignoreCase = true) ->
                        hits += SearchHit(ws.relPath(file), path.pretty(), "value", excerpt(key, value, needle))
                    key != null && key.contains(needle, ignoreCase = true) ->
                        hits += SearchHit(ws.relPath(file), path.pretty(), "key", "$key:")
                    else -> Unit
                }
            }
        }
        return hits
    }

    /** ツリーを走査し、スカラー（と親キー）ごとにコールバックする。マップ自体はキー名判定のため scalar = null で呼ぶ。 */
    private fun walk(node: YNode, path: YPath, visit: (path: YPath, key: String?, scalar: YNode.Scalar?) -> Unit) {
        when (node) {
            is YNode.Scalar -> visit(path, path.lastOrNull() as? String, node)
            is YNode.Seq -> node.items.forEachIndexed { i, child -> walk(child, path + i, visit) }
            is YNode.Map -> {
                for ((k, child) in node.entries) {
                    if (child !is YNode.Scalar) visit(path + k, k, null)
                    walk(child, path + k, visit)
                }
            }
        }
    }

    private fun excerpt(key: String?, value: String, needle: String): String {
        val oneLine = value.replace('\n', ' ')
        val idx = oneLine.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
        val start = (idx - 30).coerceAtLeast(0)
        val end = (idx + needle.length + 30).coerceAtMost(oneLine.length)
        val core = (if (start > 0) "…" else "") + oneLine.substring(start, end) + (if (end < oneLine.length) "…" else "")
        return if (key != null) "$key: $core" else core
    }

    /** pretty パス用の簡易 glob（`*` は任意文字列、`?` は1文字）。 */
    private fun pathGlobToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}
