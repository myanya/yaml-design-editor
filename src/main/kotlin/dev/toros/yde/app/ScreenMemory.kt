package dev.toros.yde.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.toros.yde.app.agent.AutomationEngine
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath
import java.nio.file.Path

/** 一覧を離れても保持する縦横の表示位置。 */
class TableScrollPosition {
    var index by mutableStateOf(0)
    var offset by mutableStateOf(0)
    var horizontal by mutableStateOf(0)
}

enum class FormDisplayMode(val label: String) {
    SELECTED("選択項目"), LINKED("全体・ツリー連動"), WHOLE("全体のみ")
}

/** 文書内容とは独立した編集画面の見え方。ツリーの開閉はフォームの畳みと分ける。 */
class FormNavigationMemory {
    /** 操作の対象は明示選択だけで更新し、スクロールの追従とは分ける。 */
    var actionTarget by mutableStateOf<String?>(null)
    var selected: YPath? by mutableStateOf(null)
    var navigationOpen by mutableStateOf(false)
    val expanded = mutableStateMapOf<YPath, Boolean>()
}

/**
 * 画面の覚え: 各画面の見え方（畳み・タブ・絞り込み・チェック・ツリーの展開）を、画面が描画の木から外れても捨てないための置き場。
 * 「戻る・進む」が戻すのは場所（NavLocation）で、見え方は画面が自分でここへ覚える（architecture/ui.md「UI シェル」）。
 *
 * 規律:
 * - 覚えは「あれば使う・無ければ既定」。古い鍵（削除した文書・無くなった型や種別）は読まれないだけで、誤りにしない。
 *   再読込でスキーマや文書が変わっても、画面側が今の候補と突き合わせて既定へ戻す。
 * - 操作の途中（ダイアログ・メニュー・入力途中の欄）は置かない（画面の `remember` のまま）。
 * - 寿命はワークスペース（切替で全部消す）。文書の削除・改名はその文書のぶんだけ消す・移す。再読込では消さない。
 * - 値は Compose の状態で、画面は読むだけで購読できる。
 */
class ScreenMemory {
    /** 表示方式の固定・引き継ぎと、ファイル単位の明示指定。 */
    val formDisplay = FormDisplayMemory()
    /** 文書 × スキーマ設計か。汎用フォームとは覚えを分ける。 */
    val formNavigation = mutableStateMapOf<Pair<DocumentId, Boolean>, FormNavigationMemory>()
    fun navigation(document: DocumentId, schema: Boolean): FormNavigationMemory =
        formNavigation.getOrPut(document to schema) { FormNavigationMemory() }

    // ================================================================ フォームの畳み（文書 × パス）

    private val folds = mutableStateMapOf<Pair<DocumentId, YPath>, Boolean>()

    /** 畳みを開いているか。覚えが無ければ null（画面が既定の深さで決める）。 */
    fun fold(document: DocumentId, path: YPath): Boolean? = folds[document to path]

    /** 畳みの開閉を覚える。同じ値は書き直さない（描画中に呼ばれても再描画を増やさない）。 */
    fun setFold(document: DocumentId, path: YPath, expanded: Boolean) {
        val key = document to path
        if (folds[key] != expanded) folds[key] = expanded
    }

    /** フォーム上部の他文書での定義。初期状態は畳む。 */
    val documentOccurrencesOpen = mutableStateMapOf<DocumentId, Boolean>()

    // ================================================================ ファイルツリー

    /** ファイル一覧の見え方。展開の既定は折り畳み。 */
    var treeOptions by mutableStateOf(dev.toros.yde.app.core.FileCatalog.Options())
    var treeQuery by mutableStateOf("")
    var treeScrollIndex by mutableStateOf(0)
    var treeScrollOffset by mutableStateOf(0)
    var treeContentSearch by mutableStateOf(false)
    var treeSearchStop by mutableStateOf(false)
    var treeSearchScope by mutableStateOf<Path?>(null)
    var treeSearchBudgetMiB by mutableStateOf(64)
    var treeSearchSeconds by mutableStateOf(30)
    var treeFocusedLine by mutableStateOf<Int?>(null)
    var treeSelectedPaths by mutableStateOf<Set<Path>>(emptySet())
    var treeAnchor by mutableStateOf<Path?>(null)
    val treeExpanded = mutableStateMapOf<Path, Boolean>()

    /** ツリーで選択中の場所（作成先・改名・削除の対象）。無くなった場所は画面が読み捨てる。 */
    var treeSelection by mutableStateOf<Path?>(null)

    // ================================================================ 右パネル

    /** 0 = 検証、1 = 関係、2 = 出現。 */
    var sidePanelTab by mutableStateOf(0)

    /** 検証の範囲。0 = この文書、1 = 全体。 */
    var validationScope by mutableStateOf(0)

    // ================================================================ ソース

    /** ソースタブの長い行を折り返すか（偽なら横スクロール）。 */
    var sourceWrap by mutableStateOf(true)

    /** ソースタブで畳んでいる区画（見出しのパス）。文書ごと。パスで覚えるので行がずれても追随する。 */
    val sourceFolds = mutableStateMapOf<DocumentId, Set<YPath>>()

    // ================================================================ 台帳

    var listQuery by mutableStateOf("")

    /** 一覧の種別・状態の絞り込み。今の候補に無い値は画面が「すべて」として扱う。 */
    var listKind by mutableStateOf("")
    var listStatus by mutableStateOf("")

    /** 一括変更の対象に選んだファイル。無くなったファイルは画面が数えない。 */
    val listSelected = mutableStateMapOf<Path, Boolean>()

    /** ビューグリッドの絞り込み（ビュー ID ごと）。 */
    val gridQuery = mutableStateMapOf<String, String>()

    val dashboardSection = mutableStateMapOf<String, String>()
    val dashboardRow = mutableStateMapOf<Pair<String, String>, String>()
    /** 未指定なら本日。指定日は画面の覚えで、原本や履歴へ書かない。 */
    val dashboardDate = mutableStateMapOf<String, java.time.LocalDate>()
    val dashboardSplit = mutableStateMapOf<String, Boolean>()
    val dashboardScroll = mutableStateMapOf<Pair<String, String>, TableScrollPosition>()
    val dashboardEntityScroll = mutableStateMapOf<String, Int>()
    /** ダッシュボードの区画の群の畳み（ダッシュボード ID × 群の名前。true＝畳んでいる）。無い群の鍵は読まれないだけ。 */
    val dashboardGroupCollapsed = mutableStateMapOf<Pair<String, String>, Boolean>()

    var entityQuery by mutableStateOf("")

    /** エンティティページで「出現別フォーム」にしている ID。 */
    val entityOccurrencesOpen = mutableStateMapOf<String, Boolean>()

    /** レンズで隠した型と関係（名前）。隠した側だけ持つので、再読込で増えた型は見える側で始まり、無くなった名前は読まれない。 */
    val lensHiddenTypes = mutableStateMapOf<String, Boolean>()
    val lensHiddenRelations = mutableStateMapOf<String, Boolean>()

    /** レンズの近傍の深さ（起点があるとき）。 */
    var lensDepth by mutableStateOf(2)

    /** レンズの配置の種類（`LensLayoutKind` の名前）。起点の有無ごとに、利用者が選んだときだけ覚える。無ければ画面の既定。 */
    val lensLayoutKind = mutableStateMapOf<Boolean, String>()

    /** レンズの「側面で分ける」（箱＝出現）。既定は一体（箱＝エンティティ）。 */
    var lensSplitOccurrences by mutableStateOf(false)

    /** レンズで利用者が開いた・畳んだ入れ子の箱（鍵は箱の key）。無ければ既定（起点の近傍だけ開く）。 */
    val lensExpanded = mutableStateMapOf<String, Boolean>()

    /** レンズの右パネルのタブ（0 = 型、1 = 関係、2 = 辺）。 */
    var lensPanelTab by mutableStateOf(0)

    /** レンズの段: 型（箱＝エンティティ型＝スキーマの図。P42）。既定は実体（箱＝エンティティ）。 */
    var lensSchemaLevel by mutableStateOf(false)

    /** 型の図の起点（型名）。無ければ全体。実体の起点は AppState.lensOriginId（移動の履歴に乗る）。 */
    var lensTypeOrigin by mutableStateOf<String?>(null)

    var readinessPendingOnly by mutableStateOf(false)
    var readinessQuery by mutableStateOf("")

    // ================================================================ 実行・設計・表示だけの文書

    /** 0 = 受信箱、1 = 手動実行、2 = 自動化、3 = 計測・評価。 */
    var runsTab by mutableStateOf(0)

    /** 受信箱で開いているレビュー対象。承認待ちから消えたものは画面が読み捨てる。 */
    var runsReview by mutableStateOf<AutomationEngine.PendingReview?>(null)

    /** 設計スペースで選んでいる定義の種類。null = 先頭。 */
    var designZone by mutableStateOf<WorkspaceLayout.Zone?>(null)

    /** 未初期化のワークスペースの案内（編集スペース）を閉じたか。 */
    var initBannerDismissed by mutableStateOf(false)

    /** Markdown・ヘルプの表示とソースのタブ。鍵はファイルの場所か「help:題目」。 */
    val markdownTab = mutableStateMapOf<String, Int>()

    /** スキーマ設計のタブ（0 = 設計、1 = プレビュー、2 = 適用対象）。文書ごと。 */
    val schemaTab = mutableStateMapOf<DocumentId, Int>()

    /** 設計の区画と、定義ごとの構造一覧の選択位置。 */
    val schemaSection = mutableStateMapOf<DocumentId, Int>()
    val schemaDefinition = mutableStateMapOf<DocumentId, String>()
    val schemaSelection = mutableStateMapOf<Pair<DocumentId, YPath>, YPath>()

    /** 編集画面のタブ（0 = フォーム、1 = ソース、2 = 差分、3 = メタスキーマのフォーム）。文書ごとに最後に見ていたもの。 */
    val editorTab = mutableStateMapOf<DocumentId, Int>()

    // ================================================================ 寿命

    /** 全部忘れる（ワークスペース切替）。 */
    fun clear() {
        formDisplay.clear()
        formNavigation.clear()
        folds.clear()
        documentOccurrencesOpen.clear()
        treeExpanded.clear()
        treeOptions = dev.toros.yde.app.core.FileCatalog.Options()
        treeQuery = ""
        treeScrollIndex = 0
        treeScrollOffset = 0
        treeContentSearch = false
        treeSearchStop = false
        treeSearchBudgetMiB = 64
        treeSearchSeconds = 30
        treeFocusedLine = null
        treeSearchScope = null
        treeSelectedPaths = emptySet()
        treeAnchor = null
        treeSelection = null
        sidePanelTab = 0
        validationScope = 0
        listQuery = ""
        listKind = ""
        listStatus = ""
        listSelected.clear()
        gridQuery.clear()
        dashboardSection.clear()
        dashboardRow.clear()
        dashboardDate.clear()
        dashboardSplit.clear()
        dashboardScroll.clear()
        dashboardEntityScroll.clear()
        dashboardGroupCollapsed.clear()
        entityQuery = ""
        entityOccurrencesOpen.clear()
        lensHiddenTypes.clear()
        lensHiddenRelations.clear()
        lensDepth = 2
        lensLayoutKind.clear()
        lensSplitOccurrences = false
        lensExpanded.clear()
        lensPanelTab = 0
        lensSchemaLevel = false
        lensTypeOrigin = null
        readinessPendingOnly = false
        readinessQuery = ""
        runsTab = 0
        runsReview = null
        designZone = null
        initBannerDismissed = false
        markdownTab.clear()
        schemaTab.clear()
        schemaSection.clear()
        schemaDefinition.clear()
        schemaSelection.clear()
    }

    /** 文書（ディレクトリならその下の文書すべて）の覚えを忘れる（削除）。 */
    fun forgetDocument(document: DocumentId) {
        formDisplay.specifiedModes.keys.filter { it.isUnder(document) }.forEach(formDisplay.specifiedModes::remove)
        formNavigation.keys.filter { it.first.isUnder(document) }.forEach(formNavigation::remove)
        documentOccurrencesOpen.keys.filter { it.isUnder(document) }.forEach(documentOccurrencesOpen::remove)
        folds.keys.filter { it.first.isUnder(document) }.forEach(folds::remove)
        schemaTab.keys.filter { it.isUnder(document) }.forEach(schemaTab::remove)
        schemaSection.keys.filter { it.isUnder(document) }.forEach(schemaSection::remove)
        schemaDefinition.keys.filter { it.isUnder(document) }.forEach(schemaDefinition::remove)
        schemaSelection.keys.filter { it.first.isUnder(document) }.forEach(schemaSelection::remove)
    }

    /** 文書（ディレクトリならその下の文書すべて）の覚えを新しい場所へ移す（改名）。 */
    fun renameDocument(from: DocumentId, to: DocumentId) {
        formDisplay.specifiedModes.keys.filter { it.isUnder(from) }.forEach { key ->
            formDisplay.specifiedModes[key.moved(from, to)] = formDisplay.specifiedModes.remove(key) ?: return@forEach
        }
        formNavigation.keys.filter { it.first.isUnder(from) }.forEach { key ->
            formNavigation[key.first.moved(from, to) to key.second] = formNavigation.remove(key) ?: return@forEach
        }
        schemaSection.keys.filter { it.isUnder(from) }.forEach { key ->
            schemaSection[key.moved(from, to)] = schemaSection.remove(key) ?: return@forEach
        }
        schemaDefinition.keys.filter { it.isUnder(from) }.forEach { key ->
            schemaDefinition[key.moved(from, to)] = schemaDefinition.remove(key) ?: return@forEach
        }
        schemaSelection.keys.filter { it.first.isUnder(from) }.forEach { key ->
            schemaSelection[key.first.moved(from, to) to key.second] = schemaSelection.remove(key) ?: return@forEach
        }
        documentOccurrencesOpen.keys.filter { it.isUnder(from) }.forEach { key ->
            val value = documentOccurrencesOpen.remove(key) ?: return@forEach
            documentOccurrencesOpen[key.moved(from, to)] = value
        }
        folds.keys.filter { it.first.isUnder(from) }.forEach { key ->
            val value = folds.remove(key) ?: return@forEach
            folds[key.first.moved(from, to) to key.second] = value
        }
        schemaTab.keys.filter { it.isUnder(from) }.forEach { key ->
            val value = schemaTab.remove(key) ?: return@forEach
            schemaTab[key.moved(from, to)] = value
        }
    }

    /** ファイルの場所（ディレクトリならその下も）に紐づく覚え＝ツリーの展開と選択・一覧の選択を忘れる（削除・改名）。 */
    fun forgetPath(path: Path) {
        treeExpanded.keys.filter { it == path || it.startsWith(path) }.forEach(treeExpanded::remove)
        listSelected.keys.filter { it == path || it.startsWith(path) }.forEach(listSelected::remove)
        if (treeSelection?.let { it == path || it.startsWith(path) } == true) treeSelection = null
    }

    private fun DocumentId.isUnder(parent: DocumentId): Boolean =
        this == parent || value.startsWith(parent.value + "/")

    private fun DocumentId.moved(from: DocumentId, to: DocumentId): DocumentId =
        DocumentId(to.value + value.removePrefix(from.value))
}
