package dev.toros.yde.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.app.core.DocBacklinkRow
import dev.toros.yde.app.core.DocViewAttachment
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.RefEntry
import dev.toros.yde.app.index.isAllowedBy
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.semantic.schema.AutoValue
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.app.schema.effectiveFields
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Path

/*
 * フォームの文脈（FormCtx: 編集の入口・閲覧専用の封じ・展開の覚え）と、項目の既定値（defaultNode）・自動の値の案内。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

internal val fieldTextStyle = TextStyle(fontSize = 13.sp)

/** ネスト構造（object・array・map）を既定で展開する深さ（フォームのルートからのパス段数）。 */
private const val DEFAULT_EXPAND_DEPTH = 2

/**
 * フォーム編集のコンテキスト。
 * localIds は未保存編集を含む「現在のドキュメント内の ID 集合」で、参照解決の即時判定に使う。
 * readOnly は閲覧専用モード（R10-04）: 編集UIを表示専用に切り替え、markEdited への到達を拒否する。
 * 既定値を state.readOnlyMode から取るため、FormCtx を作る全ビュー（フォーム・グリッド・横ぐし）へ自動波及する。
 * ソースに解析できない書きかけがある間（doc.draftBroken。P43 R43-02）も閲覧専用＝フォームで触って書きかけを失わない。
 * baseDepth は埋め込みフォーム（ID横ぐし）の基準パス段数。既定展開の深さ判定を
 * 「文書ルートから」ではなく「表示しているフォームのルートから」の相対深さで行うために使う。
 */
class FormCtx(
    val state: AppState,
    val doc: OpenDocument,
    val localIds: List<IdEntry>,
    val readOnly: Boolean = state.readOnlyMode || doc.draftBroken,
    val baseDepth: Int = 0,
    /** 文書ビューの要素単位アタッチ（R14-03）: 要素パス → その位置に差し込む被参照。ObjectFields が描画する。 */
    val attachments: Map<YPath, List<Pair<DocViewAttachment, DocBacklinkRow>>> = emptyMap(),
    /** パス → 検証指摘の最悪重大度（UX-14）。FieldRow が入力枠の色づけに使う（空 = 色づけしない）。 */
    val issues: Map<YPath, Severity> = emptyMap(),
    /** AI生成へ渡す関連文書の候補（R24-02）。エンティティページが出現・参照先/元のファイルを注入する。 */
    val aiRelatedFiles: List<Path> = emptyList(),
) {
    /** このパスの検証指摘の重大度（UX-14）。エラー=赤・警告=オレンジの枠色に使う。 */
    fun issueAt(path: YPath): Severity? = issues[path]

    val historyHandler: ((HistoryShortcut) -> Unit)? get() = state.historyHandler(doc)

    /**
     * ネスト構造の既定展開判定。フォームのルートから DEFAULT_EXPAND_DEPTH 階層までは開き、
     * より深い階層は畳んで畳み込みサマリ（E-11）に任せる。編集・閲覧専用モード共通。
     */
    fun defaultExpanded(path: YPath): Boolean = path.size - baseDepth <= DEFAULT_EXPAND_DEPTH

    /**
     * ハイライト対象（ジャンプ先）がこのパスの配下にあるか（UX-10）。
     * 畳まれたままだと対象の Highlightable がコンポーズされず、スクロールもハイライトも届かないため、
     * 畳みを持つエディタはこの判定で自動展開する（展開はジャンプ後も維持する）。
     */
    fun highlightInside(path: YPath): Boolean =
        state.highlightPath?.let { it.size > path.size && it.subList(0, path.size) == path } == true

    /** この文書の文書ID（画面の覚えの鍵）。 */
    private val documentId get() = state.documentOf(doc.file)

    /**
     * 畳みを開いているか。画面の覚え（`ScreenMemory`）→ 既定の深さの順で決め、ジャンプ先が中なら開いて覚える
     * （UX-10。展開はジャンプ後も維持）。覚えは画面を離れても残るので、戻ってきたとき同じ見え方になる。
     */
    fun expanded(path: YPath, default: Boolean = defaultExpanded(path)): Boolean {
        if (highlightInside(path)) {
            setExpanded(path, true)
            return true
        }
        return state.memory.fold(documentId, path) ?: default
    }

    fun setExpanded(path: YPath, expanded: Boolean) = state.memory.setFold(documentId, path, expanded)

    fun markEdited(coalesceWith: Any? = null) {
        check(!readOnly) { "閲覧専用モードでは編集できません" }
        doc.markEdited(coalesceWith)
    }

    fun resolvable(ref: RefEntry): Boolean =
        ref.targetId.isNotBlank() &&
            (localIds.any { it.id == ref.targetId && it.isAllowedBy(ref) } || state.overlayIndex().resolve(ref).isNotEmpty())

    /**
     * タイトルの索引借用（UX-71）: 要素自身にタイトルがないとき、同一論理IDの他出現（定義出現など）から
     * タイトルを借りる。id しか持たない写像行・詳細行の畳みヘッダが「何のIDか」を語れるようにする。
     */
    fun indexTitleLookup(): (String) -> String? = { id ->
        state.overlayIndex().occurrences(id).firstNotNullOfOrNull { it.title }
    }

    /** スキーマ外の汎用参照は、パターン／スコープ制約なしで解決する。 */
    fun resolvable(id: String): Boolean =
        resolvable(RefEntry(id, state.documentOf(doc.file), emptyList(), "ref"))

    /** 項目の宣言（idPatterns・scope）から参照の制約を組み立てる。参照欄とテーブルの参照セルが共用する。 */
    fun refConstraint(field: FieldDef, id: String): RefEntry =
        RefEntry(id, state.documentOf(doc.file), emptyList(), field.key ?: "ref", field.idPatterns, field.scope)

    /**
     * 参照先の名前（表示解決の結果＝`IdEntry.title`）。未保存の編集を含む自文書の ID（localIds）を優先し、
     * 次に上書き索引の出現を見る。どちらも idPatterns と scope で許される出現だけ
     * （architecture/index-references.md「索引の二層」）。解決できない・名前が無い・名前が ID と同じなら null。
     */
    fun refTargetTitle(field: FieldDef, id: String): String? {
        val ref = refConstraint(field, id)
        if (!resolvable(ref)) return null
        val title = localIds.firstOrNull { it.id == id && it.isAllowedBy(ref) }?.title
            ?: state.overlayIndex().occurrences(id).firstNotNullOfOrNull { e -> if (e.isAllowedBy(ref)) e.title else null }
        return title?.takeIf { it.isNotBlank() && it != id }
    }

    /** スキーマ型に応じてスカラー値を変換して書き込む。 */
    fun setScalar(slot: Slot, raw: String, type: FieldType) {
        val v: Any? = when (type) {
            FieldType.INT -> raw.toLongOrNull() ?: raw
            FieldType.NUMBER -> (raw.toLongOrNull() ?: raw.toDoubleOrNull()) ?: raw
            else -> raw
        }
        writeScalar(slot, v)
    }

    /** 元の値の型を保ちながらスカラー値を書き込む（汎用エディタ用）。 */
    fun setGenericScalar(slot: Slot, raw: String) {
        val prev = (slot.get() as? YNode.Scalar)?.value
        val v: Any? = when (prev) {
            is Boolean -> raw.toBooleanStrictOrNull() ?: raw
            is Int, is Long -> raw.toLongOrNull() ?: raw
            is Float, is Double -> raw.toDoubleOrNull() ?: raw
            else -> raw
        }
        writeScalar(slot, v)
    }

    private fun writeScalar(slot: Slot, v: Any?) {
        // 閲覧専用モードではツリーを変異させる前に拒否する（markEdited の防御より手前で止める）
        check(!readOnly) { "閲覧専用モードでは編集できません" }
        val node = slot.get()
        if (node is YNode.Scalar) {
            node.value = v
            // 同一スカラーへの連続入力は履歴1エントリへ合体（UX-11。キー=変異対象ノードの同一性。
            // フィールド移動・Undo後はインスタンスが変わるため自然に境界になる）
            markEdited(coalesceWith = node)
        } else {
            val created = YNode.Scalar(v)
            slot.set(created)
            markEdited(coalesceWith = created)
        }
    }
}

/** スキーマ定義に基づく新規ノードの初期値を作る。 */
/** 保存時に自動で入る値（auto）の案内文（項目の説明の下に出す）。 */
internal fun autoValueHint(auto: AutoValue): String = when (auto) {
    AutoValue.CREATED -> "作成時に自動で入ります（空のときだけ）"
    AutoValue.UPDATED -> "保存のときに自動で今の日付・時刻になります（中身が変わっていたとき）"
    AutoValue.REVISION -> "保存のたびに自動で +1 されます（中身が変わっていたとき）"
}

fun defaultNode(field: FieldDef?, schema: SchemaDef? = null, depth: Int = 0): YNode {
    if (depth > 24) return YNode.Map()
    val resolved = schema?.resolve(field) ?: field
    if (resolved?.default != null) return YamlIO.fromAny(resolved.default)
    return when (resolved?.type) {
        FieldType.BOOLEAN -> YNode.Scalar(false)
        FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
        FieldType.ARRAY -> YNode.Seq()
        FieldType.MAP -> YNode.Map()
        FieldType.OBJECT -> {
            val entries = LinkedHashMap<String, YNode>().apply {
                resolved.fields.forEach { ch ->
                    val key = ch.key
                    if (ch.required && key != null) put(key, defaultNode(ch, schema, depth + 1))
                }
            }
            val map = YNode.Map(entries)
            // 判別 enum の初期値に応じた case フィールドのうち必須のものを補う
            schema?.effectiveFields(resolved.fields, map)?.fields?.forEach { ch ->
                val key = ch.key ?: return@forEach
                if (ch.required && key !in entries) entries[key] = defaultNode(ch, schema, depth + 1)
            }
            map
        }
        else -> YNode.Scalar("")
    }
}
