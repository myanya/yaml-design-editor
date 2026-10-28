package dev.toros.yde.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.Views
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.semantic.ExtensionViewDef
import dev.toros.yde.semantic.ExtensionViewResult
import dev.toros.yde.yaml.model.DocumentId

// ================================================================ 拡張の種類のビューの描画（ADR-0013 の ViewRenderer。置き場は ADR-0014 で desktop.ui）
//
// 拡張の種類（chart など）の定義と解決は拡張モジュール（view-*）にあり、描き方だけがここにある。
// 中核の画面（台帳のビュータブ・文書ビューの区画・ダッシュボードの区画）は、種類の ID でここから描画を引き、中身を知らずに描く。

/** 描く場所。高さと、材料の行き先の既定が変わる。 */
internal enum class ViewPlace { LEDGER, SECTION, DASHBOARD }

/** 拡張の種類 1 つの描画。 */
internal interface ViewRenderer {
    /** 種類の ID（ViewKind.id と同じ）。 */
    val kind: String

    /** 台帳のビュータブの選択肢の見出し（名前に種類の詳しさを添える）。 */
    fun choiceName(def: ExtensionViewDef): String

    /** 台帳のビュータブの選択肢の副題。 */
    fun choiceSubtitle(def: ExtensionViewDef): String

    /** 解決した結果を描く。onOpenEntity は材料の ID の行き先（null＝その場所の既定）。 */
    @Composable
    fun Content(state: AppState, result: ExtensionViewResult, place: ViewPlace, onOpenEntity: ((String) -> Unit)?)

    /** 文書ビューの区画に描く。既定はその文書で解決して Content。文書を編集に使う種類（フロー）は自分で描く。 */
    @Composable
    fun Section(state: AppState, loaded: Views.LoadResult, def: ExtensionViewDef, doc: OpenDocument) {
        ExtensionViewPane(state, loaded, def, ViewPlace.SECTION, document = state.documentOf(doc.file))
    }
}

/** 描画の一覧。拡張の種類を足したら、desktop/ext/DesktopExtensions.kt の登録と一緒にここに 1 行足す（自動発見はしない）。 */
internal object ViewRenderers {
    private val all: List<ViewRenderer> = listOf(FlowRenderer, ChartRenderer)

    fun of(kind: String): ViewRenderer? = all.firstOrNull { it.kind == kind }
}

/** 解決した結果を、種類の描画で描く（描画が無ければその旨の文）。 */
@Composable
internal fun ExtensionViewContent(state: AppState, result: ExtensionViewResult, place: ViewPlace, onOpenEntity: ((String) -> Unit)? = null) {
    val renderer = ViewRenderers.of(result.def.viewKind)
    if (renderer == null) Text("「${result.def.kindLabel}」（kind: ${result.def.viewKind}）を描く部品がありません", fontSize = 11.sp, color = Palette.WarnOrange)
    else renderer.Content(state, result, place, onOpenEntity)
}

/**
 * 拡張の種類のビューを、同じ索引・未保存の編集で解決して描く（台帳のビュータブ・文書ビューの区画）。解決できなければ誤りの文。
 * document は文書に縛られる種類（フロー）が射影する文書。
 */
@Composable
internal fun ExtensionViewPane(state: AppState, loaded: Views.LoadResult, def: ExtensionViewDef, place: ViewPlace, document: DocumentId? = null) {
    val result = remember(def, loaded, document, state.indexVersion, state.editVersion) {
        runCatching { Views.resolveExtension(def, loaded, state.overlayIndex(), state.unsavedFirstTree(), state::schemaFor, document) }
    }
    val resolved = result.getOrNull()
    if (resolved == null) {
        Text(result.exceptionOrNull()?.message ?: "${def.kindLabel}「${def.id}」を解決できません", fontSize = 11.sp, color = Palette.WarnOrange)
    } else {
        ExtensionViewContent(state, resolved, place)
    }
}
