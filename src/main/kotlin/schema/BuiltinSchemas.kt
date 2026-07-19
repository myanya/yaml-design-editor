package schema

import java.nio.file.Path

/**
 * 予約ディレクトリ（_agents / _activities）用の組込みスキーマ。
 * エージェント／アクティビティ定義そのものをスキーマ付きYAML文書として、
 * 既存のフォーム編集・検証・差分レビューで管理できるようにする（セルフホスティング）。
 * 利用者が同じ対象にマッチする独自スキーマを _schemas に置いた場合はそちらが優先される。
 */
object BuiltinSchemas {

    private val agentSchemaYaml = """
        id: builtin-agent
        name: エージェント定義
        match: "${SchemaLoader.AGENTS_DIR}/**/*.{yaml,yml}"
        root:
          type: object
          fields:
            - key: id
              label: エージェントID
              type: id
              required: true
              description: アクティビティから参照する識別子
            - key: name
              label: 表示名
              type: string
            - key: persona
              label: 性格・行動指針
              type: text
              required: true
              description: システムプロンプトに組み込まれる人格・規範
            - key: rubric
              label: 観点
              type: array
              item:
                type: string
              description: レビューや作成で重視する観点のリスト
            - key: model
              label: モデル設定
              type: object
              fields:
                - key: provider
                  label: プロバイダ
                  type: enum
                  values: [openai, anthropic]
                - key: id
                  label: モデルID
                  type: string
            - key: tools
              label: 許可ツール分類
              type: array
              item:
                type: enum
                values: [explore, edit, validate, ask, delegate]
              description: 省略時はすべて許可（manage 系は常に利用可能）
    """.trimIndent()

    private val activitySchemaYaml = """
        id: builtin-activity
        name: アクティビティ定義
        match: "${SchemaLoader.ACTIVITIES_DIR}/**/*.{yaml,yml}"
        root:
          type: object
          fields:
            - key: id
              label: アクティビティID
              type: id
              required: true
            - key: name
              label: 表示名
              type: string
            - key: description
              label: 説明
              type: text
            - key: agent
              label: 実行エージェント
              type: ref
              required: true
              scope:
                schemaIds: [builtin-agent]
            - key: target
              label: 対象
              type: object
              fields:
                - key: match
                  label: 対象ファイル（glob）
                  type: string
            - key: instruction
              label: 指示
              type: text
              required: true
            - key: context
              label: 文脈レシピ
              type: object
              fields:
                - key: include
                  label: 追加開示
                  type: array
                  item:
                    type: enum
                    values: [catalog, target-outlines]
            - key: allowedFiles
              label: 書込許可ファイル（glob）
              type: array
              item:
                type: string
              description: 省略時は全ファイルに書込可
            - key: allowedPaths
              label: 書込許可パス（glob）
              type: array
              item:
                type: string
              description: YPath（例 review_comments*）。省略時は全パスに書込可
            - key: tools
              label: 許可ツール分類
              type: array
              item:
                type: enum
                values: [explore, edit, validate, ask, delegate]
            - key: limits
              label: ループ上限
              type: object
              fields:
                - key: maxTurns
                  label: 最大ターン数
                  type: int
                - key: maxTokens
                  label: 最大トークン数
                  type: int
                - key: maxSeconds
                  label: 最大実行秒数
                  type: int
            - key: gates
              label: 提出ゲート
              type: object
              fields:
                - key: validation
                  label: 検証条件
                  type: enum
                  values: [error-free, none]
                  default: error-free
            - key: approval
              label: 承認方式
              type: enum
              values: [human, auto]
              default: human
              description: auto はゲート通過・スコープ内・非編集中のときのみ自動適用（条件を欠くと承認待ちへ降格）
            - key: trigger
              label: 起動契機
              type: object
              fields:
                - key: type
                  label: 種別
                  type: enum
                  values: [manual, on-change, schedule, on-invalid]
                  default: manual
                - key: match
                  label: 監視対象（glob）
                  type: string
                  description: 省略時は target.match
                - key: debounceSeconds
                  label: デバウンス秒
                  type: int
                - key: everyMinutes
                  label: 周期（分）
                  type: int
                  description: schedule のとき必須
            - key: chain
              label: 連鎖
              type: object
              fields:
                - key: onSuccess
                  label: 成功時に起動
                  type: ref
                  scope:
                    schemaIds: [builtin-activity]
                - key: onFailure
                  label: 失敗時に起動
                  type: ref
                  scope:
                    schemaIds: [builtin-activity]
            - key: retry
              label: 再試行（無人実行）
              type: object
              fields:
                - key: maxAttempts
                  label: 最大試行回数
                  type: int
                  description: 失敗理由をフィードバックして再実行する回数（既定 1 = 再試行なし）
            - key: meta
              label: メタアクティビティ
              type: boolean
              description: 定義文書（_agents / _activities / _evals / _skills）への書込を許可する（承認は human 固定。_schemas / _runs は不可）
    """.trimIndent()

    private val skillSchemaYaml = """
        id: builtin-skill
        name: skill定義（入出力変換）
        match: "${SchemaLoader.SKILLS_DIR}/**/*.{yaml,yml}"
        root:
          type: object
          fields:
            - key: id
              label: skillID
              type: id
              required: true
            - key: name
              label: 表示名
              type: string
            - key: description
              label: 説明
              type: text
            - key: kind
              label: 種別
              type: enum
              values: [import, export]
              required: true
            - key: input
              label: 入力（import）
              type: object
              fields:
                - key: format
                  label: 形式
                  type: enum
                  values: [xlsx, csv, json, markdown]
                - key: sheet
                  label: シート名（xlsx）
                  type: string
                - key: headerRow
                  label: ヘッダ行（xlsx・1始まり）
                  type: int
                - key: path
                  label: 行配列へのパス（json）
                  type: string
            - key: target
              label: 取り込み先（import）
              type: object
              fields:
                - key: file
                  label: ファイルパターン
                  type: string
                  description: 例 issues/{ID}.yaml（{列名} が行の値で置換される）
            - key: mapping
              label: 列の対応（import）
              type: array
              item:
                type: object
                fields:
                  - key: column
                    label: 列名
                    type: string
                    required: true
                  - key: path
                    label: 取り込み先パス
                    type: string
                    required: true
                  - key: type
                    label: 型
                    type: enum
                    values: [string, int, number, boolean, auto]
                    default: string
                  - key: required
                    label: 必須
                    type: boolean
                  - key: split
                    label: 配列化の区切り
                    type: string
            - key: source
              label: 対象文書（export）
              type: object
              fields:
                - key: match
                  label: 対象glob
                  type: string
                - key: rows
                  label: 行にする配列パス
                  type: string
                  description: 省略時は1文書1行
            - key: columns
              label: 列の定義（export）
              type: array
              item:
                type: object
                fields:
                  - key: header
                    label: 見出し
                    type: string
                    required: true
                  - key: path
                    label: 参照パス
                    type: string
                    required: true
                  - key: join
                    label: 配列の結合区切り
                    type: string
            - key: output
              label: 出力（export）
              type: object
              fields:
                - key: file
                  label: 出力ファイル
                  type: string
                  description: 拡張子で形式が決まる（.xlsx / .csv / .md）。YAMLへの出力は不可
                - key: sheet
                  label: シート名（xlsx）
                  type: string
    """.trimIndent()

    private val evalSchemaYaml = """
        id: builtin-eval
        name: 評価セット定義
        match: "${SchemaLoader.EVALS_DIR}/**/*.{yaml,yml}"
        root:
          type: object
          fields:
            - key: id
              label: 評価セットID
              type: id
              required: true
            - key: name
              label: 表示名
              type: string
            - key: description
              label: 説明
              type: text
            - key: activity
              label: 評価対象アクティビティ
              type: ref
              required: true
              scope:
                schemaIds: [builtin-activity]
            - key: variants
              label: 比較実験の変異（A/B）
              type: array
              description: 省略時は現行定義のまま1系統（baseline）で実行する
              item:
                type: object
                fields:
                  - key: name
                    label: 変異名
                    type: string
                    required: true
                  - key: model
                    label: モデル差し替え
                    type: object
                    fields:
                      - key: provider
                        label: プロバイダ
                        type: enum
                        values: [openai, anthropic]
                      - key: id
                        label: モデルID
                        type: string
                  - key: agent
                    label: エージェント差し替え
                    type: ref
                    scope:
                      schemaIds: [builtin-agent]
                  - key: instruction
                    label: 追加指示
                    type: text
            - key: cases
              label: 評価ケース
              type: array
              required: true
              item:
                type: object
                fields:
                  - key: id
                    label: ケースID
                    type: string
                    required: true
                  - key: name
                    label: ケース名
                    type: string
                  - key: setup
                    label: ゴールデン入力（サンドボックスへ上書き）
                    type: array
                    item:
                      type: object
                      fields:
                        - key: file
                          label: ファイル
                          type: string
                          required: true
                        - key: content
                          label: 内容（YAMLテキスト）
                          type: text
                          required: true
                  - key: instruction
                    label: 追加指示
                    type: text
                  - key: expect
                    label: 期待結果
                    type: object
                    fields:
                      - key: status
                        label: 終了状態
                        type: enum
                        values: [submitted, aborted, limit, error, cancelled, any]
                        default: submitted
                      - key: errorFree
                        label: 検証エラー0
                        type: boolean
                      - key: changedFiles
                        label: 変更されるべきファイル（glob）
                        type: array
                        item:
                          type: string
                      - key: unchangedFiles
                        label: 変更されてはならないファイル（glob）
                        type: array
                        item:
                          type: string
                      - key: content
                        label: 内容チェック
                        type: array
                        item:
                          type: object
                          fields:
                            - key: file
                              label: ファイル
                              type: string
                              required: true
                            - key: path
                              label: YPath
                              type: string
                            - key: exists
                              label: 存在すること
                              type: boolean
                              default: true
                            - key: contains
                              label: 含まれるべき文字列
                              type: string
                            - key: equals
                              label: 一致すべき値
                              type: string
    """.trimIndent()

    /** 組込みスキーマ一覧（読み込み失敗はあり得ない前提。失敗時は例外で気付かせる）。 */
    val all: List<SchemaDef> by lazy {
        listOf(
            SchemaLoader.parseSchemaText(agentSchemaYaml, Path.of("(builtin)/agent.yaml")),
            SchemaLoader.parseSchemaText(activitySchemaYaml, Path.of("(builtin)/activity.yaml")),
            SchemaLoader.parseSchemaText(evalSchemaYaml, Path.of("(builtin)/eval.yaml")),
            SchemaLoader.parseSchemaText(skillSchemaYaml, Path.of("(builtin)/skill.yaml")),
        )
    }
}
