package dev.toros.yde.workspace

import dev.toros.yde.semantic.ExtensionViewDef
import dev.toros.yde.semantic.VirtualViewDefinition
import dev.toros.yde.yaml.model.DocumentId

/**
 * ビューの種類の差し込み口（ADR-0014。architecture/views.md「ビューの種類の差し込み口」）。
 * JDBC の Driver と同じ形で、差し込み口はカーネルにあり、実装はその外（app-core の組込み app.view の chart・flow。ADR-0015）にあり、
 * 使うものは登録簿（app.ext.ExtensionRegistry）が持つ。カーネルは大域の登録簿を持たず、使う種類を引数（kinds）で受け取る。
 *
 * 中核の種類（grid・document・matrix・lens・dashboard）はここを通らない。
 */
interface ViewKind {
    /** kind の値（chart など）。中核の種類の ID とは重ねない。 */
    val id: String

    /** 種類の名前（チャートなど）。誤りの文に使う。 */
    val label: String

    /**
     * 定義の形の断片（YAML の原文）。ビューのメタスキーマに合成する（ViewSchemaFragments）。
     * 書けるのは `kind`（選択肢 1 つ）・`fields`（ルートの項目）・`section`（文書ビューの区画に埋め込むときの項目）の 3 つ。
     */
    val schemaFragment: String

    /** 文書ビューの区画に `<キー>: {view: ID}` で埋め込めるなら、そのキー（chart なら chart）。null＝埋め込めない。 */
    val sectionKey: String? get() = null

    /** ダッシュボードの区画（`view: ID`）に置けるか。 */
    val inDashboard: Boolean get() = false

    /** 定義 1 件を読む。誤りは文で例外を投げる（ファイル名は呼ぶ側が前に付ける）。 */
    fun parse(raw: Map<*, *>, source: DocumentId): ExtensionViewDef

    /**
     * 定義の一式を見た点検（元の表の有無など）。all は読めたすべての定義。schemas は空のことがある（リントを省くとき）。
     * 文はファイル名を付けずに返す（呼ぶ側が前に付ける）。
     */
    fun lint(def: ExtensionViewDef, all: List<VirtualViewDefinition>, schemas: List<WorkspaceSchema>): List<String> = emptyList()

    /**
     * 定義 1 件とスキーマだけで分かる点検（適用先のスキーマの有無など）。schemas が空なら呼ばれない。
     * 定義文書を開いている間の検証（ViewDefinitions.lintDocument）でも呼ばれる。文はファイル名を付けずに返す。
     */
    fun lintDefinition(def: ExtensionViewDef, schemas: List<WorkspaceSchema>): List<String> = emptyList()

    /**
     * 文書の移動・改名で、この定義の対象から外れる警告（RefactoringImpact.moveWarnings）。
     * leaves(glob) は「glob が移動前の場所に合い、移動後の場所に合わない」か。
     */
    fun moveWarnings(def: ExtensionViewDef, leaves: (String?) -> Boolean): List<String> = emptyList()

    /**
     * 改名の言及（自動で書き換えない場所）。raw はこの種類の定義文書の中身、token は改名する名前。
     * 返すのは場所（例 `projection.build.nodes`）で、文に組むのは RefactoringImpact。探すときは DefinitionMentions を使う。
     */
    fun mentionSites(raw: Map<*, *>, token: String): List<String> = emptyList()
}

/** 定義文書の中の名前の言及を探す（改名の警告。RefactoringImpact と拡張の種類が共に使う）。 */
object DefinitionMentions {
    /** 名前の前後が英数字と _ でないこと（部分一致を言及にしない）。 */
    fun tokenRegex(token: String): Regex =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")

    /** raw の中で token を含むキーと文字の場所（crumb から始まる座標。配列は 1 から数える）。 */
    fun literal(raw: Any?, token: String, crumb: String): List<String> {
        val pattern = tokenRegex(token)
        val out = linkedSetOf<String>()
        fun walk(node: Any?, at: String) {
            when (node) {
                is Map<*, *> -> node.forEach { (key, value) ->
                    val here = "$at.$key"
                    if (pattern.containsMatchIn(key.toString())) out += here
                    walk(value, here)
                }
                is List<*> -> node.forEachIndexed { i, value -> walk(value, "$at[${i + 1}]") }
                is String -> if (pattern.containsMatchIn(node)) out += at
                else -> Unit
            }
        }
        walk(raw, crumb)
        return out.toList()
    }
}
