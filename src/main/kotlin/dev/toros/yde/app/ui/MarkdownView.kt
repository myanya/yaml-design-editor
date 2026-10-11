package dev.toros.yde.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

// ================================================================ Markdown の描画（multiplatform-markdown-renderer を包む）

/**
 * Markdown を描く。ライブラリの型はこのファイルの外へ出さない（差し替えるときはここだけを書き換える）。
 * 色と文字の大きさは Palette と本文の目安（CONVENTIONS「Compose UI の書き方」）に合わせる。
 * `compact` は項目の説明など小さく見せる場所向け。リンクのクリックは `onOpenLink` に渡す（無ければ OS の既定で開く）。
 * 画像は描かない（画像の読み込みは別モジュールが要るため。需要が出たら足す）。
 */
@Composable
fun MarkdownView(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpenLink: ((String) -> Unit)? = null,
) {
    val handler = remember(onOpenLink) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (onOpenLink != null) onOpenLink(uri) else openExternally(uri)
            }
        }
    }
    val base = if (compact) 11.sp else 13.sp
    val textColor = if (compact) Palette.SubText else Palette.Text
    val body = TextStyle(fontSize = base, color = textColor, lineHeight = base * 1.5)
    fun heading(size: Int) = TextStyle(fontSize = size.sp, fontWeight = FontWeight.Bold, color = Palette.Text, lineHeight = (size * 1.4).sp)
    val code = TextStyle(fontSize = if (compact) 10.sp else 12.sp, fontFamily = AppFonts.Mono, color = Palette.Text)
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(
            content = text,
            colors = markdownColor(
                text = textColor,
                codeBackground = Palette.PanelBg,
                inlineCodeBackground = Palette.PanelBg,
                dividerColor = Palette.Border,
                tableBackground = Palette.Surface,
            ),
            typography = markdownTypography(
                h1 = heading(if (compact) 14 else 20), h2 = heading(if (compact) 13 else 17), h3 = heading(if (compact) 12 else 15),
                h4 = heading(if (compact) 11 else 13), h5 = heading(if (compact) 11 else 13), h6 = heading(if (compact) 11 else 13),
                text = body, paragraph = body, list = body, bullet = body, ordered = body,
                code = code, inlineCode = code,
                quote = body.copy(fontStyle = FontStyle.Italic, color = Palette.SubText),
                textLink = TextLinkStyles(style = SpanStyle(color = Palette.Primary, textDecoration = TextDecoration.Underline)),
                table = body.copy(fontSize = if (compact) 10.sp else 12.sp),
            ),
            modifier = modifier,
        )
    }
}

/** OS の既定（ブラウザなど）で URI を開く。失敗しても黙る（表示の邪魔をしない）。 */
fun openExternally(uri: String) {
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        desktop.browse(java.net.URI(uri))
    }
}
