package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import app.OpenDocument
import yaml.YamlIO
import org.yaml.snakeyaml.error.MarkedYAMLException
import androidx.compose.ui.input.key.*

/**
 * YAML ソースを直接編集するペイン。
 * 「反映」でパースしてツリー（source of truth）へ取り込む。
 * 反映後のテキストは正規化（ブロックスタイル・インデント2）される点に注意。
 */
@Composable
fun SourcePane(state: AppState, doc: OpenDocument) {
    val canonical = remember(doc, doc.version) { doc.canonicalText() }
    var text by remember(doc, doc.version) { mutableStateOf(canonical) }
    var error by remember(doc) { mutableStateOf<String?>(null) }
    var search by remember(doc) { mutableStateOf("") }
    val edited = text != canonical

    fun applySource(): Boolean {
        return try {
            val parsed = YamlIO.parse(text)
            doc.root = parsed
            doc.parseError = null
            error = null
            doc.markEdited()
            true
        } catch (e: Exception) {
            val mark = (e as? MarkedYAMLException)?.problemMark
            error = "パースエラー" + (mark?.let { "（${it.line + 1} 行 ${it.column + 1} 列）" } ?: "") + ": ${e.message}"
            false
        }
    }

    Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.S) {
            if (edited) {
                if (applySource()) { if (event.isShiftPressed) state.saveAll() else state.saveCurrent() }
            } else if (event.isShiftPressed) state.saveAll() else state.saveCurrent()
            true
        } else false
    }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = edited,
                onClick = { applySource() },
            ) { Text("変更を反映", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = edited, onClick = { text = canonical; error = null }) {
                Text("破棄", fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            if (doc.root == null) {
                Text(
                    "パースエラーのためフォーム表示不可。ここで修正して「反映」してください。",
                    fontSize = 12.sp, color = Palette.ErrorRed,
                )
            } else if (edited) {
                Text("未反映：反映後、文書ヘッダーの「保存」で書き出します", fontSize = 12.sp, color = Palette.WarnOrange)
            } else if (doc.dirty) {
                Text("反映済み・未保存：Ctrl+Sでファイルへ保存できます", fontSize = 12.sp, color = Palette.WarnOrange)
            } else {
                Text("保存済み", fontSize = 12.sp, color = Palette.OkGreen)
            }
            Spacer(Modifier.weight(1f))
            CompactTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                placeholder = { Text("ソース内を検索", fontSize = 11.sp, color = Palette.SubText) },
                modifier = Modifier.width(180.dp),
            )
            if (search.isNotEmpty()) {
                val count = remember(text, search) { Regex(Regex.escape(search), RegexOption.IGNORE_CASE).findAll(text).count() }
                Text("$count 件", fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(start = 6.dp))
            }
        }
        error?.let {
            Text(
                it,
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                fontSize = 12.sp, color = Palette.ErrorRed,
            )
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(Palette.EditorBg)
        ) {
            val scroll = rememberScrollState()
            Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
                val lineCount = text.count { it == '\n' } + 1
                BasicTextField(
                    value = (1..lineCount).joinToString("\n"),
                    onValueChange = {},
                    readOnly = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.End,
                        color = Palette.SubText,
                    ),
                    modifier = Modifier.widthIn(min = 42.dp)
                        .background(Palette.PanelBg)
                        .padding(start = 6.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                )
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = Palette.Text,
                    ),
                    modifier = Modifier.weight(1f).padding(12.dp),
                )
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
