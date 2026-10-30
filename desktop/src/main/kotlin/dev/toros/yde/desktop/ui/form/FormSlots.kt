package dev.toros.yde.desktop.ui.form

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.sp
import dev.toros.yde.semantic.VirtualDocEmbed
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.Slot

/**
 * フォームが自分では描かず、上のパッケージに描いてもらう欄（差し込み口）。form は機能のパッケージ（ui.file・ui.view・ui.schema）を
 * import しないので、中身は上から渡す（architecture/ui.md「画面のパッケージ」）。file 型の欄と文書ビューに埋め込む拡張のビューは
 * 骨格（ui/shell/AppRoot.kt）が、スキーマを書くときの欄はスキーマの画面（ui/schema/SchemaEditor.kt）が渡す。
 * 渡されていない欄は、描く部品が無い旨の文を出す（拡張の種類のビューと同じ扱い）。
 */
internal data class FormSlots(
    /** file 型の欄。preview は画像を下に出すか（表のセルでは出さない）。 */
    val fileField: @Composable (ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath, preview: Boolean) -> Unit =
        { _, _, _, _, _ -> MissingPart("file 型の欄") },
    /** 文書ビューの区画に埋め込んだ拡張の種類のビュー（`flow: {view}`・`chart: {view}` など）。 */
    val embeddedView: @Composable (state: AppState, doc: OpenDocument, embed: VirtualDocEmbed) -> Unit =
        { _, _, embed -> MissingPart("「${embed.label}」（kind: ${embed.kind}）") },
    /** スキーマを書いているときだけ渡す。null は業務の文書のフォーム。 */
    val schemaAuthoring: SchemaAuthoring? = null,
)

/** スキーマを書いているときにフォームが使う欄（中身は ui/schema/SchemaValueEditor.kt の SchemaFormAuthoring）。 */
internal interface SchemaAuthoring {
    /** 式の値と、型を定めない（any）値の欄。形の選択・条件・手順の入力支援がつく。 */
    @Composable
    fun ValueEditor(ctx: FormCtx, slot: Slot, path: YPath)

    /** 設定を消すボタン（確認つき）。 */
    @Composable
    fun DeleteButton(title: String, onDelete: () -> Unit)
}

internal val LocalFormSlots = compositionLocalOf { FormSlots() }

@Composable
private fun MissingPart(what: String) {
    Text("$what を描く部品が渡されていません", fontSize = 11.sp, color = Palette.WarnOrange)
}
