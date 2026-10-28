package dev.toros.yde.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.Help
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path

// ================================================================ Markdown 文書と内蔵ヘルプの表示

/** Markdown の中のリンクの行き先（純ロジックで決める。画面は MarkdownPane）。 */
internal sealed class MarkdownLink {
    /** ワークスペース内のファイル（YAML・Markdown はアプリで開く。それ以外は OS に任せる）。 */
    data class WorkspaceFile(val file: Path) : MarkdownLink()
    /** 内蔵ヘルプの別の題目。 */
    data class HelpTopic(val file: String) : MarkdownLink()
    /** 外部（http・mailto など）。 */
    data class External(val uri: String) : MarkdownLink()
    /** 開けない（ワークスペースの外・無いファイルなど）。 */
    data class Unsupported(val reason: String) : MarkdownLink()
}

/**
 * リンクの行き先を決める。`base` は表示中のファイル（相対リンクの基準）、`helpTopic` は表示中のヘルプの題目
 * （ヘルプの中の相対リンクは題目どうしの移動）。`#` 以降（見出しへの移動）は読み飛ばす。
 */
internal fun resolveMarkdownLink(
    href: String,
    base: Path?,
    workspace: Path?,
    helpTopic: String?,
    exists: (Path) -> Boolean = { Files.exists(it) },
): MarkdownLink {
    val target = href.substringBefore('#').trim()
    if (target.isEmpty()) return MarkdownLink.Unsupported("見出しへの移動には対応していません")
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(target)) return MarkdownLink.External(href)
    if (helpTopic != null) {
        val name = target.substringAfterLast('/')
        return if (!target.contains("..") && !target.startsWith("/") && name.endsWith(".md")) MarkdownLink.HelpTopic(name)
        else MarkdownLink.Unsupported("ヘルプの外（$target）は開けません")
    }
    if (base == null || workspace == null) return MarkdownLink.Unsupported("基準の文書がありません")
    val resolved = base.parent.resolve(target).normalize()
    if (!resolved.startsWith(workspace.normalize())) return MarkdownLink.Unsupported("ワークスペースの外（$target）は開けません")
    if (!exists(resolved)) return MarkdownLink.Unsupported("リンク先がありません: $target")
    return MarkdownLink.WorkspaceFile(resolved)
}

/**
 * ワークスペース内の Markdown 文書（読み取り専用の表示）か、内蔵ヘルプを表示する。
 * 「表示」は描画した Markdown、「ソース」は素のテキスト。ファイルは更新時刻を見て外部の変更に追随する（編集はしない）。
 */
@Composable
fun MarkdownPane(state: AppState) {
    val file = state.openMarkdown
    val help = state.openHelp
    // 表示とソースのタブは画面の覚え（文書・題目ごと。離れても残る）
    val tabKey = file?.toString() ?: "help:$help"
    val tab = state.memory.markdownTab[tabKey] ?: 0
    var stamp by remember(file) { mutableStateOf(0L) }
    if (file != null) {
        LaunchedEffect(file) {
            while (true) {
                val now = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)
                if (now != stamp) stamp = now
                delay(1000)
            }
        }
    }
    val text = remember(file, help, stamp) {
        when {
            help != null -> Help.text(help) ?: "（ヘルプ「$help」が見つかりません）"
            file != null -> runCatching { Files.readString(file) }.getOrElse { "（読み込めません: ${it.message}）" }
            else -> ""
        }
    }
    val title = when {
        help != null -> Help.titleOf(help)
        file != null -> state.relPath(file)
        else -> ""
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (help != null) Text("内蔵ヘルプ（利用者マニュアル）", fontSize = 10.sp, color = Palette.SubText)
                else Text("Markdown（表示のみ。編集は外部のエディタで）", fontSize = 10.sp, color = Palette.SubText)
            }
            if (file != null) {
                OutlinedButton(
                    onClick = { runCatching { java.awt.Desktop.getDesktop().open(file.toFile()) }.onFailure { state.status = "外部のエディタで開けません: ${it.message}" } },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) { Text("外部のエディタで開く", fontSize = 12.sp) }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = Palette.Surface, contentColor = Palette.Primary) {
            Tab(selected = tab == 0, onClick = { state.memory.markdownTab[tabKey] = 0 }, text = { Text("表示", fontSize = 13.sp) })
            Tab(selected = tab == 1, onClick = { state.memory.markdownTab[tabKey] = 1 }, text = { Text("ソース", fontSize = 13.sp) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 16.dp)) {
                if (tab == 0) {
                    MarkdownView(text, Modifier.widthIn(max = 900.dp), onOpenLink = { href ->
                        when (val link = resolveMarkdownLink(href, file, state.workspace, help)) {
                            is MarkdownLink.WorkspaceFile -> {
                                val name = link.file.fileName.toString().lowercase()
                                if (name.endsWith(".md") || name.endsWith(".yaml") || name.endsWith(".yml")) state.openFile(link.file)
                                else runCatching { java.awt.Desktop.getDesktop().open(link.file.toFile()) }
                                    .onFailure { state.status = "開けません: ${it.message}" }
                            }
                            is MarkdownLink.HelpTopic -> state.openHelp(link.file)
                            is MarkdownLink.External -> openExternally(link.uri)
                            is MarkdownLink.Unsupported -> state.status = link.reason
                        }
                    })
                } else {
                    Text(text, fontFamily = AppFonts.Mono, fontSize = 12.sp, color = Palette.Text, lineHeight = 18.sp)
                }
                Spacer(Modifier.width(1.dp))
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
