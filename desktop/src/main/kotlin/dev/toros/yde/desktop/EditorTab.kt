package dev.toros.yde.desktop

/**
 * 文書の編集画面のタブ。業務の文書は「フォーム・ビュー・ソース・差分」、スキーマの文書は「設計（FORM）・ソース・差分」と
 * 補助の画面（メタスキーマの汎用フォーム＝META_FORM ほか）。
 * - FORM: スキーマから作る既定のフォーム（スキーマの文書では設計の画面）。文書ビューは当てない
 * - VIEW: 当たる文書ビュー（kind: document）で合成した画面。台帳の文書だけで、当たるビューが無ければ押せない
 * - SOURCE・DIFF: テキストと差分
 * - META_FORM: スキーマの文書をメタスキーマのフォームで編集する（スキーマの文書だけ）
 * 文書を開いたときのタブの決め方（opening）と、ジャンプで着地するタブ（landing）は、画面に依らない純粋な関数。
 */
enum class EditorTab {
    FORM, VIEW, SOURCE, DIFF, META_FORM;

    /** フォームの仲間（フォームかビュー）。 */
    val formFamily: Boolean get() = this == FORM || this == VIEW

    companion object {
        /** 当たるビューがあればビュー、無ければフォーム（フォームの仲間の既定）。 */
        private fun formDefault(hasView: Boolean): EditorTab = if (hasView) VIEW else FORM

        /**
         * その文書で出せるタブに直す（ビューが当たらない文書のビュー → フォーム、スキーマの文書でない文書のメタスキーマのフォーム → フォーム、
         * スキーマの文書のビュー → 設計）。覚えていたタブと履歴のタブを、今の文書に当てはめるときに使う。
         */
        fun fit(tab: EditorTab, schemaDocument: Boolean, hasView: Boolean): EditorTab = when {
            tab == VIEW && (schemaDocument || !hasView) -> FORM
            tab == META_FORM && !schemaDocument -> FORM
            else -> tab
        }

        /**
         * 文書を開いたときのタブ。
         * - 解析できない文書はソース
         * - この文書で最後に見ていたタブがあれば、それ（出せなければ fit で直す）
         * - 無ければ直前のタブから: ソース・差分はそのまま、フォームの仲間（とメタスキーマのフォーム）は「ビューが当たればビュー、
         *   無ければフォーム」（ビューを定義すれば、開いたときにそれが見える）。スキーマの文書どうしならメタスキーマのフォームのまま
         */
        fun opening(
            remembered: EditorTab?,
            previous: EditorTab,
            parsed: Boolean,
            schemaDocument: Boolean,
            hasView: Boolean,
        ): EditorTab = when {
            !parsed -> SOURCE
            remembered != null -> fit(remembered, schemaDocument, hasView)
            previous == SOURCE || previous == DIFF -> previous
            previous == META_FORM && schemaDocument -> META_FORM
            else -> fit(formDefault(hasView), schemaDocument, hasView)
        }

        /**
         * 指摘・被参照・出現からのジャンプで着地するタブ。フォームの仲間はそのまま（ビューを見ていればビュー）、
         * ソースは keepSource のときだけそのまま（ソースの行へ着地する）、それ以外（差分・メタスキーマのフォーム）はフォームの仲間の既定。
         */
        fun landing(current: EditorTab, schemaDocument: Boolean, hasView: Boolean, keepSource: Boolean = true): EditorTab = when {
            current == SOURCE && keepSource -> SOURCE
            current.formFamily -> fit(current, schemaDocument, hasView)
            else -> fit(formDefault(hasView), schemaDocument, hasView)
        }
    }
}
