package dev.toros.yde.desktop.ui

import androidx.compose.runtime.*
import dev.toros.yde.desktop.AppState
import dev.toros.yde.app.core.FileCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal data class BrowserRow(val entry: FileCatalog.Entry, val depth: Int, val line: Int? = null, val excerpt: String? = null)

/** 開いた枝だけを列挙。全体検索は別の取消可能な仕事で、UI スレッドではディスクを読まない。 */
@Composable
internal fun browserRows(state: AppState): Pair<List<BrowserRow>, String> {
    val root = state.workspace ?: return emptyList<BrowserRow>() to ""
    val options = state.memory.treeOptions
    val query = state.memory.treeQuery
    val content = state.memory.treeContentSearch
    val searchScope = state.memory.treeSearchScope ?: root
    val budget = state.memory.treeSearchBudgetMiB
    val seconds = state.memory.treeSearchSeconds
    val expanded = state.memory.treeExpanded.toMap()
    var rows by remember(root) { mutableStateOf<List<BrowserRow>>(emptyList()) }
    var status by remember(root) { mutableStateOf("") }
    LaunchedEffect(root, options, query, expanded, content, searchScope, budget, seconds, state.fileTreeRevision) {
        status = "読み込み中…"
        if (query.isNotBlank()) delay(200)
        val job = coroutineContext
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (query.isNotBlank() && content) {
                    val found = dev.toros.yde.app.core.ReferenceSearch.search(root, query, options, searchScope,
                        byteBudget = budget.toLong() * 1024 * 1024, timeBudgetMillis = seconds * 1000L,
                        cancelled = { !job.isActive || state.memory.treeSearchStop },
                        progress = { if (job.isActive) status = "本文を検索中… $it 件確認" },
                        onBatch = { hits -> if (job.isActive) rows = hits.map { BrowserRow(FileCatalog.Entry(it.file, false), 0, it.line, it.text) } })
                    found.hits.map { BrowserRow(FileCatalog.Entry(it.file, false), 0, it.line, it.text) } to
                        "${found.hits.size} 箇所 / ${found.visited} ファイル・除外 ${found.skipped} 件" +
                        (if (found.incomplete) "・一部の結果（中止または上限）" else "")
                } else if (query.isNotBlank()) {
                    val found = FileCatalog.search(root, query, options, cancelled = { !job.isActive || state.memory.treeSearchStop },
                        progress = { if (job.isActive) status = "検索中… $it 件確認" })
                    found.entries.map { BrowserRow(it, 0) } to
                        "${found.entries.size} 件 / ${found.visited} 件確認" +
                        (if (found.truncated) "・上限です。条件を絞ってください" else "") +
                        (if (found.errors > 0) "・読めない場所 ${found.errors} 件" else "")
                } else {
                    val out = mutableListOf<BrowserRow>()
                    var errors = 0
                    fun visit(dir: Path, depth: Int) {
                        if (!job.isActive) return
                        val children = try { FileCatalog.children(root, dir, options) } catch (_: Exception) { errors++; return }
                        children.forEach { entry ->
                            out += BrowserRow(entry, depth)
                            if (entry.directory && expanded[entry.path] == true) visit(entry.path, depth + 1)
                        }
                    }
                    visit(root, 0)
                    out to if (errors == 0) "${out.size} 件表示" else "読めない場所 ${errors} 件"
                }
            }
        }
        result.onSuccess { (list, message) -> rows = list; status = message }
            .onFailure { rows = emptyList(); status = it.message ?: "一覧を読めません" }
    }
    return rows to status
}
