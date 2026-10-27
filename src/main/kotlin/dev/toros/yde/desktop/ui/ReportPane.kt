package dev.toros.yde.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.AppState
import dev.toros.yde.app.core.Reports
import dev.toros.yde.app.core.Views
import dev.toros.yde.yaml.YamlIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * レポート（R30-04）の画面: 受け取る値の欄 → 「作る」で変換とひな形を評価し、Markdown として表示する。
 * 「保存」で定義の output（派生物）へ書く。入力中の値は画面の覚え（ScreenMemory）に置く。
 */
@Composable
fun ReportPane(state: AppState, loaded: Reports.Loaded, catalog: Views.LoadResult) {
    val ws = state.workspace ?: return
    val def = loaded.def
    val scope = rememberCoroutineScope()
    var result by remember(def.id) { mutableStateOf<Reports.Result?>(null) }
    var error by remember(def.id) { mutableStateOf<String?>(null) }
    var message by remember(def.id) { mutableStateOf<String?>(null) }
    var showSource by remember(def.id) { mutableStateOf(false) }
    fun paramValues(): Map<String, Any?> = def.params.mapNotNull { name ->
        val text = state.memory.reportParams[def.id to name].orEmpty()
        if (text.isBlank()) null else name to (runCatching { YamlIO.parseToRaw(text) }.getOrElse { text })
    }.toMap()
    fun build() {
        error = null
        message = null
        result = runCatching {
            Reports.run(loaded, paramValues(), catalog, state.overlayIndex(), state.unsavedFirstTree(), state::schemaFor)
        }.onFailure { error = it.message ?: it::class.simpleName }.getOrNull()
    }
    // 値がそろっていれば、索引や未保存の編集が変わるたびに作り直す（表と同じく編集に追随する）
    LaunchedEffect(def.id, state.indexVersion, state.editVersion) {
        if (def.params.all { !state.memory.reportParams[def.id to it].isNullOrBlank() }) build()
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            def.params.forEach { name ->
                Text(name, fontSize = 12.sp, color = Palette.SubText)
                CompactTextField(
                    state.memory.reportParams[def.id to name].orEmpty(),
                    { state.memory.reportParams[def.id to name] = it },
                    singleLine = true, modifier = Modifier.width(160.dp),
                )
            }
            Button(onClick = ::build, enabled = def.params.all { !state.memory.reportParams[def.id to it].isNullOrBlank() }) { Text("作る", fontSize = 12.sp) }
            result?.let { r ->
                r.outputRel?.let { rel ->
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { Reports.write(ws, rel, r.text) }
                                message = "書きました: $rel"
                            } catch (e: Exception) {
                                message = "保存できません: ${e.message}"
                            }
                        }
                    }) { Text("保存（$rel）", fontSize = 12.sp) }
                }
                TextButton(onClick = { showSource = !showSource }) { Text(if (showSource) "表示で見る" else "Markdown で見る", fontSize = 12.sp) }
            }
            message?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
        }
        def.description?.let { Text(it, fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(top = 4.dp)) }
        error?.let { Text(it, fontSize = 12.sp, color = Palette.ErrorRed, modifier = Modifier.padding(top = 6.dp)) }
        val r = result
        if (r == null) {
            if (error == null) Text(
                if (def.params.isEmpty()) "「作る」でレポートを作ります" else "受け取る値（${def.params.joinToString("・")}）を入れて「作る」を押します",
                fontSize = 12.sp, color = Palette.SubText, modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }
        if (r.missing.isNotEmpty()) Text("埋まらなかった穴: " + r.missing.joinToString("・"), fontSize = 11.sp, color = Palette.WarnOrange, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.padding(top = 6.dp))
        Column(
            Modifier.fillMaxSize().border(1.dp, Palette.Border, PanelShape).background(Palette.Surface, PanelShape)
                .padding(12.dp).verticalScroll(rememberScrollState()),
        ) {
            if (showSource) Text(r.text, fontSize = 12.sp, fontFamily = AppFonts.Mono, color = Palette.Text)
            else MarkdownView(r.text, Modifier.fillMaxWidth())
        }
    }
}

/** レポートの一覧の見出し（ビュータブの一覧で使う）。 */
internal fun reportSubtitle(loaded: Reports.Loaded): String =
    "${loaded.def.id} — ひな形: ${loaded.def.template}" + (loaded.def.output?.let { "・出力: $it" } ?: "") +
        (loaded.def.params.takeIf { it.isNotEmpty() }?.let { "・受け取る値: ${it.joinToString("・")}" } ?: "")
