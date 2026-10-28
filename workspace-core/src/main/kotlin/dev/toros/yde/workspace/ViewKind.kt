package dev.toros.yde.workspace

import dev.toros.yde.semantic.ExtensionViewDef
import dev.toros.yde.semantic.VirtualViewDefinition
import dev.toros.yde.yaml.model.DocumentId

/**
 * ビューの種類の差し込み口（ADR-0014。architecture/views.md「ビューの種類の差し込み口」）。
 * JDBC の Driver と同じ形で、差し込み口はカーネルにあり、実装は拡張モジュール（view-chart など）にあり、使うものを入口が登録する
 * （app.ext.ExtensionRegistry）。カーネルは大域の登録簿を持たず、使う種類を引数（kinds）で受け取る。
 *
 * 中核の種類（grid・document・matrix・flow・lens・dashboard）はここを通らない。
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
}
