package dev.toros.yde.app.skill

import java.nio.file.Files
import java.nio.file.Path

/**
 * テンプレートパック（R5-04）。ユースケース別のスキーマ＋エージェント＋アクティビティ＋評価＋skill一式を
 * 新規ワークスペースへ展開する。配布パッケージでも動くようファイル内容はコードに埋め込む。
 * 既存ファイルは上書きしない（スキップして報告する）。
 */
object TemplatePacks {

    data class Pack(val name: String, val description: String, val files: Map<String, String>)

    data class ApplyResult(val written: List<String>, val skipped: List<String>)

    val all: List<Pack> by lazy { listOf(ledger) }

    fun pack(name: String): Pack? = all.firstOrNull { it.name == name }

    fun apply(dir: Path, pack: Pack): ApplyResult {
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        Files.createDirectories(dir)
        pack.files.forEach { (rel, content) ->
            val target = dir.resolve(rel)
            if (Files.exists(target)) {
                skipped += rel
            } else {
                Files.createDirectories(target.parent)
                Files.writeString(target, content.trimIndent().trim() + "\n")
                written += rel
            }
        }
        return ApplyResult(written, skipped)
    }

    // ---------------------------------------------------------------- ledger: 課題台帳（Excel往復のショーケース）

    private val ledger = Pack(
        name = "ledger",
        description = "課題台帳: Excelの台帳をインポートしてYAML原本で運用し、Excel帳票を再出力する（汎用情報管理の雛形）",
        files = linkedMapOf(
            "_schemas/issue.yaml" to """
                id: issue
                name: 課題
                match: "issues/**/*.{yaml,yml}"
                root:
                  type: object
                  fields:
                    - { key: id, label: 課題ID, type: id, required: true }
                    - { key: title, label: 件名, type: string, required: true }
                    - key: status
                      label: 状態
                      type: enum
                      values: [open, in-progress, done]
                      default: open
                    - { key: priority, label: 優先度, type: int }
                    - { key: owner, label: 担当, type: string }
                    - key: tags
                      label: タグ
                      type: array
                      item: { type: string }
                    - { key: description, label: 内容, type: text }
                    - key: triage_notes
                      label: 棚卸しメモ
                      type: array
                      item:
                        type: object
                        fields:
                          - { key: comment, label: 指摘, type: text }
                          - { key: suggestion, label: 提案, type: text }
            """,
            "_agents/curator.yaml" to """
                id: curator
                name: 台帳管理担当
                persona: |
                  台帳の整合性を重視する。指摘には必ず対象IDと根拠を添え、
                  推測で値を埋めず、不明点は指摘として残す。
                rubric: [欠落, 重複, 状態と内容の整合, 期限切れ]
            """,
            "_activities/triage-issues.yaml" to """
                id: triage-issues
                name: 課題の棚卸し
                description: 担当・優先度の欠落や、状態と内容の矛盾を点検し、棚卸しメモを追記する。
                agent: curator
                target:
                  match: "issues/**/*.{yaml,yml}"
                context:
                  items:
                    - kind: catalog
                      match: "issues/**"
                instruction: |
                  各課題を点検し、次に当てはまるものへ triage_notes 配列に指摘（comment / suggestion）を追記してください。
                  - owner または priority が未設定
                  - status が done なのに内容が未完了を示唆している
                  - 同じ内容と思われる課題の重複
                  問題がない課題には何も書かないでください。
                allowedFiles: ["issues/**"]
                allowedPaths: ["triage_notes*"]
                limits: { maxTurns: 16 }
            """,
            "_evals/triage-eval.yaml" to """
                id: triage-eval
                name: 棚卸しの回帰評価
                activity: triage-issues
                cases:
                - id: missing-owner-is-flagged
                  name: 担当未設定の課題に指摘が付く
                  setup:
                  - file: issues/ISSUE-900.yaml
                    content: |
                      id: ISSUE-900
                      title: 担当者が決まっていないタスク
                      status: open
                      description: 評価用のゴールデン入力。owner と priority が未設定。
                  expect:
                    status: submitted
                    errorFree: true
                    content:
                    - { file: issues/ISSUE-900.yaml, path: triage_notes, exists: true }
            """,
            "_skills/import-issues.yaml" to """
                id: import-issues
                name: 課題台帳の取り込み（Excel）
                description: Excel台帳（1行1課題）を issues/ 配下のYAML原本へ取り込む。既存課題はマップした列だけ更新される。
                kind: import
                input: { format: xlsx, headerRow: 1 }
                target: { file: "issues/{ID}.yaml" }
                mapping:
                - { column: ID, path: id, required: true }
                - { column: 件名, path: title, required: true }
                - column: 状態
                  path: status
                  map: { 未着手: open, 対応中: in-progress, 完了: done }
                - { column: 優先度, path: priority, type: int }
                - { column: 担当, path: owner }
                - { column: タグ, path: tags, split: "," }
            """,
            "_skills/export-issues.yaml" to """
                id: export-issues
                name: 課題台帳の出力（Excel）
                description: YAML原本からExcel帳票を再生成する（派生成果物。いつでも作り直せる）。
                kind: export
                source: { match: "issues/**/*.{yaml,yml}" }
                output: { file: exports/issues.xlsx, sheet: 課題一覧 }
                columns:
                - { header: ID, path: id }
                - { header: 件名, path: title }
                - { header: 状態, path: status }
                - { header: 優先度, path: priority }
                - { header: 担当, path: owner }
                - { header: タグ, path: tags, join: "," }
            """,
            "_skills/export-issues-md.yaml" to """
                id: export-issues-md
                name: 課題一覧の出力（Markdown）
                kind: export
                source: { match: "issues/**/*.{yaml,yml}" }
                output: { file: exports/issues.md }
                columns:
                - { header: ID, path: id }
                - { header: 件名, path: title }
                - { header: 状態, path: status }
                - { header: 担当, path: owner }
            """,
            "issues/ISSUE-001.yaml" to """
                id: ISSUE-001
                title: ログイン画面のエラーメッセージが不親切
                status: open
                priority: 2
                owner: yamada
                tags: [ux, login]
                description: |
                  認証失敗時に「エラーが発生しました」としか表示されず、原因が分からない。
                  パスワード誤り・アカウントロックを区別して表示したい。
            """,
            "issues/ISSUE-002.yaml" to """
                id: ISSUE-002
                title: 一覧画面の表示が遅い
                status: in-progress
                priority: 1
                owner: sato
                tags: [performance]
                description: |
                  1000件を超えると一覧の初期表示に10秒以上かかる。ページングか仮想化を検討する。
            """,
        ),
    )
}
