package schema

import java.nio.file.Path

/**
 * 予約ディレクトリ（_agents / _activities）用の組込みスキーマ。
 * エージェント／アクティビティ定義そのものをスキーマ付きYAML文書として、
 * 既存のフォーム編集・検証・差分レビューで管理できるようにする（セルフホスティング）。
 * 利用者が同じ対象にマッチする独自スキーマを _schemas に置いた場合はそちらが優先される。
 */
object BuiltinSchemas {

    /** `_schemas/` に適用されるメタスキーマのID（R11-02）。core.Workspace.schemaFor が参照する。 */
    const val META_SCHEMA_ID = "builtin-schema"

    /** 関係レジストリ文書（kind: relations。R20-02）に適用されるメタスキーマのID。 */
    const val RELATIONS_SCHEMA_ID = "builtin-relations"

    /**
     * 組込みメタスキーマ（R11-02）: スキーマ定義そのものをフォーム編集・検証・AI生成の対象にする。
     * FieldDef の再帰構造は再帰 `${"$"}ref`（definitions.field）、「type の値ごとに持てる属性」は
     * `cases`（S-10）で表現する（自機能のドッグフーディング）。default・values 要素・phases のように
     * 単一の型で表せない箇所は `any`（検証しない明示。ARCHITECTURE §4.23）。
     */
    private val metaSchemaYaml = """
        id: $META_SCHEMA_ID
        name: スキーマ定義
        description: _schemas 配下のスキーマ文書を検証・フォーム編集するためのメタスキーマ
        match: "${SchemaLoader.SCHEMA_DIR}/**/*.{yaml,yml}"
        definitions:
          field:
            type: object
            fields:
              - key: key
                label: キー
                type: string
                description: 親オブジェクト内のキー名（ルート・配列要素では不要）
              - key: label
                label: 表示ラベル
                type: string
              - key: type
                label: 型
                type: enum
                values: [string, text, int, number, boolean, enum, date, id, ref, array, map, object, any]
                cases:
                  string:
                    - key: minLength
                      label: 最小文字数
                      type: int
                    - key: maxLength
                      label: 最大文字数
                      type: int
                    - key: pattern
                      label: 形式（正規表現）
                      type: string
                      description: 値の全体がこの正規表現に一致すること（R16-01）
                  text:
                    - key: minLength
                      label: 最小文字数
                      type: int
                    - key: maxLength
                      label: 最大文字数
                      type: int
                    - key: pattern
                      label: 形式（正規表現）
                      type: string
                  int:
                    - key: min
                      label: 最小値
                      type: int
                    - key: max
                      label: 最大値
                      type: int
                  number:
                    - key: min
                      label: 最小値
                      type: number
                    - key: max
                      label: 最大値
                      type: number
                  enum:
                    - key: values
                      label: 選択肢
                      type: array
                      item:
                        type: any
                      description: 文字列、または {value, label, description} のリッチ選択肢
                    - key: cases
                      label: 値ごとの追加フィールド
                      type: map
                      value:
                        type: array
                        item:
                          ${"$"}ref: field
                      description: enum の値ごとに親オブジェクトへ追加される兄弟フィールド
                  array:
                    - key: item
                      label: 要素定義
                      ${"$"}ref: field
                    - key: display
                      label: 一覧の表示形式
                      type: enum
                      values: [table, list]
                      description: table は表形式に固定、list は縦並びに固定（省略時は列構成から自動判定）
                  map:
                    - key: value
                      label: 値定義
                      ${"$"}ref: field
                    - key: display
                      label: 一覧の表示形式
                      type: enum
                      values: [table, list]
                      description: table は表形式（キー列つき）に固定、list は縦並びに固定（省略時は列構成から自動判定）
                  object:
                    - key: fields
                      label: 子フィールド
                      type: array
                      item:
                        ${"$"}ref: field
                    - key: unknownFields
                      label: スキーマ外キーの扱い
                      type: enum
                      values: [allow, warning, error]
                  ref:
                    - key: idPattern
                      label: 参照先IDパターン
                      type: any
                      description: glob 文字列または配列
                    - key: idPatterns
                      label: 参照先IDパターン（複数）
                      type: any
                    - key: scope
                      label: 探索範囲
                      type: object
                      fields:
                        - key: schemaIds
                          label: スキーマIDの限定
                          type: any
                          description: 文字列または配列
                        - key: documentIds
                          label: ドキュメントIDの限定
                          type: any
                          description: 文字列または配列
                    - key: relation
                      label: 関係種別
                      type: string
                      description: 例 implements / depends。表示とカバレッジ判定に使う
                    - key: fields
                      label: エッジ属性の宣言
                      type: array
                      item:
                        ${"$"}ref: field
                      description: 宣言時のみ {to, 属性...} のオブジェクト形式を許可する（属性付き参照。R20-03）
              - key: required
                label: 必須
                type: boolean
              - key: requiredFrom
                label: このフェーズ以降で必須
                type: string
              - key: role
                label: 表示役割
                type: enum
                values: [title, subtitle]
              - key: default
                label: 初期値
                type: any
              - key: description
                label: 説明
                type: text
              - key: ${"$"}ref
                label: 名前付き定義への参照
                type: string
                description: definitions 内の名前（横断参照は moduleId#definition）
              - key: ref
                label: 名前付き定義への参照（別記法）
                type: string
        root:
          type: object
          fields:
            - key: id
              label: スキーマID
              type: id
              role: title
              description: 安定したスキーマID（省略時はファイル名）
            - key: name
              label: スキーマ名
              type: string
            - key: description
              label: 説明
              type: text
              role: subtitle
            - key: kind
              label: 種別
              type: enum
              values: [document, library, relations]
              description: document（既定）は文書に適用、library は共通定義のみ公開、relations は関係レジストリ（保存すると専用フォームが適用される）
            - key: match
              label: 適用パターン（glob）
              type: string
            - key: root
              label: ルート定義
              ${"$"}ref: field
            - key: definitions
              label: 名前付き定義
              type: map
              value:
                ${"$"}ref: field
            - key: phases
              label: フェーズ宣言
              type: any
              description: フェーズ名の配列、または {key, order} の object
            - key: entity
              label: エンティティ型の宣言
              type: any
              description: "この文書群の出現のエンティティ型（R20-01）。{type, idPattern, defines} の object、または複数型のリスト（idPattern で判別）"
            - key: coverage
              label: カバレッジルール
              type: array
              item:
                type: object
                fields:
                  - key: id
                    label: ルールID
                    type: string
                  - key: description
                    label: 説明
                    type: string
                  - key: idPattern
                    label: 対象IDパターン
                    type: string
                  - key: requiredBy
                    label: 参照ベースの条件
                    type: object
                    fields:
                      - key: schemaIds
                        label: 参照元スキーマの限定
                        type: any
                      - key: relation
                        label: 関係種別の限定
                        type: string
                      - key: minCount
                        label: 必要な参照数
                        type: int
                  - key: detailIn
                    label: 出現ベースの条件
                    type: object
                    fields:
                      - key: schemaIds
                        label: 出現先スキーマ
                        type: any
                      - key: minCount
                        label: 必要な出現数
                        type: int
                  - key: level
                    label: 重大度
                    type: enum
                    values: [warning, error]
                  - key: activeFrom
                    label: 判定を始めるフェーズ
                    type: string
            - key: consistency
              label: 整合ルール
              type: array
              description: このスキーマの文書のID要素の値が、導出（計算列と同じ語彙）の期待値と一致することを検証する（R16-03a）
              item:
                type: object
                fields:
                  - key: id
                    label: ルールID
                    type: string
                  - key: description
                    label: 説明
                    type: string
                  - key: idPattern
                    label: 対象IDパターン
                    type: string
                  - key: path
                    label: 検査する値の相対パス
                    type: string
                    required: true
                    description: ID要素からの相対YPath（例 type）
                  - key: expected
                    label: 期待値の導出
                    type: object
                    required: true
                    description: ビューの計算列（computed）と同じ語彙（lookup / source / path / values / map / default）
                    fields:
                      - key: lookup
                        label: 参照の1ホップ
                        type: object
                        fields:
                          - key: schemaId
                            label: 参照値を読む出現のスキーマ
                            type: string
                          - key: fileGlob
                            label: 参照値を読む出現のファイル（glob）
                            type: string
                          - key: via
                            label: ref フィールドの相対パス
                            type: string
                            required: true
                      - key: source
                        label: 元値を読む出現
                        type: object
                        required: true
                        fields:
                          - key: schemaId
                            label: スキーマ
                            type: string
                          - key: fileGlob
                            label: ファイル（glob）
                            type: string
                      - key: path
                        label: 元値の相対パス
                        type: string
                        required: true
                      - key: values
                        label: プレースホルダ解決に使う出現
                        type: array
                        item:
                          type: object
                          fields:
                            - key: schemaId
                              label: スキーマ
                              type: string
                            - key: fileGlob
                              label: ファイル（glob）
                              type: string
                      - key: map
                        label: 値辞書
                        type: map
                        value:
                          type: string
                      - key: default
                        label: 既定値
                        type: string
                  - key: assert
                    label: 判定方法
                    type: enum
                    values:
                      - value: equals
                        label: 一致
                        description: 宣言値が導出値と一致すること（既定）
                      - value: lte
                        label: 以下
                        description: 宣言値が導出値以下であること（数値として比較。適合判定）
                      - value: gte
                        label: 以上
                        description: 宣言値が導出値以上であること（数値として比較）
                  - key: level
                    label: 重大度
                    type: enum
                    values: [warning, error]
                  - key: activeFrom
                    label: 判定を始めるフェーズ
                    type: string
            - key: derive
              label: 派生スキーマの宣言
              type: object
              description: 導出元文書（例 テーブル定義書）から検証スキーマをロード時にコンパイルする（R11-03）
              fields:
                - key: from
                  label: 導出元文書（glob）
                  type: string
                  required: true
                - key: each
                  label: 導出単位の配列パス
                  type: string
                  description: 1文書内の複数要素から導出する場合の配列パス（省略時は文書ルート）
                - key: id
                  label: 導出スキーマID
                  type: string
                  required: true
                  description: "{パス} が導出元要素の値で置換される（例 record-{id}）"
                - key: name
                  label: 導出スキーマ名
                  type: string
                - key: match
                  label: 導出スキーマの適用先（glob）
                  type: string
                  required: true
                - key: fields
                  label: フィールドの写像
                  type: object
                  required: true
                  fields:
                    - key: source
                      label: フィールドにする配列のパス
                      type: string
                      required: true
                    - key: key
                      label: キーの値パス
                      type: string
                      required: true
                    - key: label
                      label: ラベルの値パス
                      type: string
                    - key: description
                      label: 説明の値パス
                      type: string
                    - key: required
                      label: 必須の値パス
                      type: string
                    - key: type
                      label: 型の写像
                      type: object
                      fields:
                        - key: path
                          label: 型を決める値のパス
                          type: string
                          required: true
                        - key: map
                          label: 値辞書
                          type: map
                          value:
                            type: string
                          description: 導出元の値 → フィールド型（例 varchar → string）
                        - key: default
                          label: 既定の型
                          type: string
    """.trimIndent()

    /**
     * 関係レジストリのメタスキーマ（R20-02。ARCHITECTURE §4.30）。
     * `_schemas` の `kind: relations` 文書にだけ適用される（振り分けは core.Workspace.schemaFor）。
     */
    private val relationsSchemaYaml = """
        id: $RELATIONS_SCHEMA_ID
        name: 関係レジストリ
        description: "ワークスペースの関係語彙（表示ラベル・対象型・多重度・非循環）を宣言する文書（kind: relations。R20-02）"
        match: "${SchemaLoader.SCHEMA_DIR}/**/*.{yaml,yml}"
        root:
          type: object
          fields:
            - key: id
              label: レジストリID
              type: id
              role: title
            - key: kind
              label: 種別
              type: enum
              values: [relations]
              required: true
            - key: name
              label: 表示名
              type: string
            - key: description
              label: 説明
              type: text
              role: subtitle
            - key: relations
              label: 関係の一覧
              type: array
              required: true
              item:
                type: object
                fields:
                  - key: name
                    label: 関係名
                    type: string
                    required: true
                    role: title
                    description: スキーマの ref フィールドの relation と一致させる
                  - key: label
                    label: 表示ラベル（参照方向）
                    type: string
                    description: 例「利用する」。この要素からの参照の表示に使う
                  - key: inverseLabel
                    label: 表示ラベル（被参照方向）
                    type: string
                    role: subtitle
                    description: 例「利用される」。この要素への被参照の表示に使う
                  - key: source
                    label: 参照元のエンティティ型
                    type: array
                    item: { type: string }
                    description: 参照を書く側のエンティティ型（省略可。強制は P21）
                  - key: target
                    label: 参照先のエンティティ型
                    type: array
                    item: { type: string }
                    description: 参照先のエンティティ型（省略可。強制は P21）
                  - key: cardinality
                    label: 多重度
                    type: object
                    description: in（被参照数）/ out（参照数）の下限上限（P20 では宣言のみ・強制は P21）
                    fields:
                      - { key: inMin, label: 入次数の下限, type: int }
                      - { key: inMax, label: 入次数の上限, type: int }
                      - { key: outMin, label: 出次数の下限, type: int }
                      - { key: outMax, label: 出次数の上限, type: int }
                  - key: acyclic
                    label: 非循環
                    type: boolean
                    description: この関係で循環を作らない宣言（P20 では宣言のみ・強制は P21）
                  - key: description
                    label: 説明
                    type: text
    """.trimIndent()

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
                - key: items
                  label: 開示項目
                  type: array
                  description: 初期コンテキストへ宣言順に開示する項目（R19-02。省略時は開示なし＝空既定）
                  item:
                    type: object
                    fields:
                      - key: kind
                        label: 種別
                        type: enum
                        required: true
                        values:
                          - { value: catalog, label: カタログ, description: 文書カタログ（match で対象を絞れる） }
                          - { value: phase, label: フェーズ状態, description: 対象文書のフェーズ状態（省略時は target.match の範囲） }
                          - { value: view, label: ビュー, description: 横断グリッドの解決結果（rows / columns で絞れる） }
                          - { value: doc, label: 文書, description: 指定文書（file / path）の全文 }
                          - { value: related, label: 関連情報, description: 論理IDの出現・参照・被参照のまとめ }
                          - { value: sample, label: 記述例, description: 同スキーマの既存文書N件（記述粒度の手本） }
                          - { value: graph, label: グラフ, description: ID関係グラフの経路トラバース（起点から参照・被参照を多段に辿る） }
                        cases:
                          catalog:
                            - key: match
                              label: 対象ファイル（glob）
                              type: string
                          phase:
                            - key: match
                              label: 対象ファイル（glob）
                              type: string
                          view:
                            - key: view
                              label: ビューID
                              type: ref
                              required: true
                              scope:
                                schemaIds: [builtin-view]
                            - key: rows
                              label: 行絞り（IDパターン。{target} 等のプレースホルダ可）
                              type: string
                            - key: columns
                              label: 列絞り（列ラベルの列挙。空は全列）
                              type: array
                              item:
                                type: string
                          doc:
                            - key: file
                              label: 対象文書（相対パス。{target} 等のプレースホルダ可）
                              type: string
                              required: true
                            - key: path
                              label: YPath（省略時は文書全体）
                              type: string
                          related:
                            - key: id
                              label: 論理ID（{target} 等のプレースホルダ可）
                              type: string
                              required: true
                          sample:
                            - key: schema
                              label: スキーマID
                              type: string
                              required: true
                            - key: count
                              label: 件数
                              type: int
                          graph:
                            - key: start
                              label: 起点ID（{target} 等のプレースホルダ可）
                              type: string
                              required: true
                            - key: steps
                              label: 経路ステップ（省略時は既定経路）
                              type: array
                              item:
                                type: object
                                fields:
                                  - key: follow
                                    label: 方向
                                    type: enum
                                    values:
                                      - { value: out, label: 参照を辿る }
                                      - { value: in, label: 被参照を辿る }
                                  - key: relation
                                    label: 関係種別で絞る
                                    type: string
                                  - key: via
                                    label: 参照キー名で絞る
                                    type: string
                                  - key: schema
                                    label: 到達スキーマで絞る
                                    type: string
                                  - key: emit
                                    label: 開示粒度
                                    type: enum
                                    values: [title, summary, outline, full]
                                    default: summary
                                  - key: limitPerHop
                                    label: ホップ毎の上限
                                    type: int
                      - key: when
                        label: 開示条件
                        type: enum
                        values:
                          - { value: always, label: 常に }
                          - { value: has-target, label: 対象指定あり, description: foreach の対象値または追加指示がある実行のみ }
                          - { value: no-target, label: 対象指定なし }
                        default: always
                      - key: budgetChars
                        label: 文字数予算
                        type: int
                        description: 超過分は決定的に打切り（doc / related / sample の既定は8000字）
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
                - key: rules
                  label: 出力値による分岐
                  type: array
                  description: 上から順に評価し、最初に一致したルールの then へ連鎖する（不一致なら onSuccess / onFailure）
                  item:
                    type: object
                    fields:
                      - key: when
                        label: 条件（AND）
                        type: object
                        fields:
                          - key: outcome
                            label: 終わり方
                            type: enum
                            values: [any, success, failure]
                            default: any
                          - key: changedFiles
                            label: 変更ファイルglob
                            type: string
                            description: 提出（採用）変更セットに一致するファイルがあること
                          - key: minChangedFiles
                            label: 変更ファイル数の下限
                            type: int
                          - key: noteContains
                            label: メモの部分一致
                            type: string
                            description: エージェントのメモ・提出メッセージに含まれる文字列
                      - key: then
                        label: 分岐先
                        type: ref
                        required: true
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
            - key: foreach
              label: 一括実行（対象列挙）
              type: object
              description: "手動起動時に対象を列挙し、1件=1実行としてキューへ展開する（trigger: manual 専用。R18-01）"
              fields:
                - key: source
                  label: 列挙元
                  type: enum
                  required: true
                  values: [view, issues, search, files, ids]
                - key: view
                  label: ビューID（view）
                  type: ref
                  scope:
                    schemaIds: [builtin-view]
                - key: where
                  label: 行条件（view）
                  type: enum
                  values: [detail-missing, all]
                  default: detail-missing
                  description: detail-missing は詳細文書が未作成の行のみ
                - key: severity
                  label: 重大度（issues）
                  type: enum
                  values: [error, warning]
                  default: error
                  description: warning はエラーを含む警告以上
                - key: match
                  label: ファイル絞り込みglob（issues / files / search）
                  type: string
                - key: idPattern
                  label: IDパターン（ids）
                  type: string
                - key: schemaIds
                  label: スキーマ絞り込み（ids / search）
                  type: array
                  item:
                    type: string
                - key: text
                  label: 検索テキスト（search）
                  type: string
                - key: pathGlob
                  label: パスglob（search）
                  type: string
                - key: refTo
                  label: 参照先ID（search）
                  type: string
                - key: unresolved
                  label: 未解決参照のみ（search）
                  type: boolean
                - key: as
                  label: 1件の追加指示テンプレート
                  type: string
                  description: 「{id} {title} {file} {path} {message}」等が対象の値に置換される（省略時は列挙元ごとの既定文）
                - key: limit
                  label: 展開上限
                  type: int
                  description: 1回の投入で展開する最大件数（既定 20）
            - key: meta
              label: メタアクティビティ
              type: boolean
              description: 定義文書（_agents / _activities / _evals / _skills / _views）への書込を許可する（承認は human 固定。_schemas / _runs は不可）
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
              values: [import, export, run]
              required: true
            - key: input
              label: 入力（import）
              type: object
              fields:
                - key: format
                  label: 形式
                  type: enum
                  values: [xlsx, csv, json, markdown, docx]
                - key: sheet
                  label: シート名（xlsx）
                  type: string
                - key: headerRow
                  label: ヘッダ行（xlsx・1始まり）
                  type: int
                - key: path
                  label: 行配列へのパス（json）
                  type: string
                - key: table
                  label: 表の番号（docx・1始まり）
                  type: int
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
                  - key: map
                    label: 値の変換辞書
                    type: map
                    value:
                      type: string
                    description: 列の値を変換する辞書（例 未着手 → open）
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
                - key: view
                  label: ビューID
                  type: string
                  description: _views のビュー定義を行×列の源にする（match / rows / columns と排他）
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
                  description: 拡張子で形式が決まる（.xlsx / .csv / .md / .json / .html）。YAMLへの出力は不可
                - key: sheet
                  label: シート名（xlsx）
                  type: string
            - key: run
              label: コード実行（run）
              type: object
              description: 実行前に人間がコマンド内容と権限を承認する明示許可制（自動実行・エージェントからは起動できない）
              fields:
                - key: command
                  label: コマンドと引数
                  type: array
                  item: { type: string }
                - key: workdir
                  label: 作業ディレクトリ（ワークスペース相対）
                  type: string
                - key: timeoutSeconds
                  label: タイムアウト（秒）
                  type: int
                - key: writes
                  label: 書込を宣言するglob
                  type: array
                  description: 実行後に宣言外の変更を検出して警告する（予約ディレクトリは宣言不可）
                  item: { type: string }
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
                  - key: context
                    label: 文脈レシピ差し替え
                    type: any
                    description: アクティビティの context を丸ごと差し替える（書式はアクティビティ定義の context と同じ items 配列。レシピA/B比較用。R19-06）
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

    private val viewSchemaYaml = """
        id: builtin-view
        name: ビュー定義（横断一覧・文書ビュー）
        match: "${SchemaLoader.VIEWS_DIR}/**/*.{yaml,yml}"
        definitions:
          docBacklinks:
            type: object
            fields:
              - key: idPattern
                label: 対象IDパターン
                type: string
                description: 文書内のこのglobに一致するIDだけを対象にする（省略時は全ID）
              - key: relation
                label: 関係種別の限定
                type: string
                description: 参照の relation がこの値の被参照だけを表示する（省略時は全被参照）
              - key: schemaIds
                label: 参照元スキーマの限定
                type: array
                item:
                  type: string
                description: 参照元文書のスキーマIDを限定する（省略時は制限なし）
              - key: columns
                label: 表示列
                type: array
                description: 参照元要素から表示する値の列（省略時はID・タイトルの要約のみ）
                item:
                  type: object
                  fields:
                    - key: label
                      label: 見出し
                      type: string
                      required: true
                    - key: path
                      label: 相対パス
                      type: string
                      required: true
                      description: 参照元要素からの相対YPath（例 name）
        root:
          type: object
          fields:
            - key: id
              label: ビューID
              type: id
              required: true
            - key: name
              label: 表示名
              type: string
            - key: description
              label: 説明
              type: text
            - key: kind
              label: ビュー種別
              type: enum
              values:
                - value: grid
                  label: 横断グリッド
                  description: 行（論理ID）×列（出現からの相対パス）の横断一覧（省略時の既定）
                - value: document
                  label: 文書ビュー
                  description: match / schemaId に一致する文書のフォーム表示を sections で合成する
              default: grid
            - key: match
              label: 適用先ファイル（glob）
              type: string
              description: "文書ビューのみ: このglobに一致する文書へ適用する（schemaId と併記時は AND）"
            - key: schemaId
              label: 適用先スキーマ
              type: string
              description: "文書ビューのみ: このスキーマが適用される文書へ適用する"
            - key: sections
              label: セクションの定義
              type: array
              description: "文書ビューのみ: 上から順に描画するセクション（fields か backlinks のどちらか一方を持つ）"
              item:
                type: object
                fields:
                  - key: label
                    label: 見出し
                    type: string
                  - key: fields
                    label: フォームセクション
                    type: object
                    description: スキーマ駆動フォームの射影（path 省略時はルート全体。backlinks とは併用不可）
                    fields:
                      - key: path
                        label: 表示するデータのYPath
                        type: string
                        description: 文書ルートからの相対YPath（例 actors）。省略時はルート全体
                      - key: definition
                        label: 定義参照
                        type: string
                        description: このセクションが参照する named definition（module#name。schemaId 指定時は名前だけでも可）。パス先のフィールドが実際にこの定義を参照しているかをリントで検査する
                      - key: include
                        label: 表示キーの選別
                        type: array
                        item:
                          type: string
                        description: 列挙したキーだけをこの順で表示する（exclude とは併用不可）
                      - key: exclude
                        label: 表示キーの除外
                        type: array
                        item:
                          type: string
                        description: 列挙したキーを表示から除く
                  - key: backlinks
                    label: 被参照表セクション
                    description: 文書内のIDへの被参照（参照元）を一覧表示する（fields とは併用不可）
                    ${'$'}ref: docBacklinks
            - key: attachments
              label: 要素単位アタッチ
              type: array
              description: "文書ビューのみ: at パターンに一致する要素の展開フォーム内に、その要素のIDへの被参照表を差し込む"
              item:
                type: object
                fields:
                  - key: label
                    label: 見出し
                    type: string
                  - key: at
                    label: 差し込み先の要素パス
                    type: string
                    required: true
                    description: YPath 構文＋ [*]（任意のインデックス。例 actors[*]）
                  - key: backlinks
                    label: 被参照表
                    required: true
                    description: 差し込む被参照の条件（セクションの backlinks と同じ語彙）
                    ${'$'}ref: docBacklinks
            - key: rows
              label: 行の定義
              type: object
              description: "横断グリッドのみ（必須）: 行になる論理IDの条件"
              fields:
                - key: idPattern
                  label: 対象IDパターン
                  type: string
                  required: true
                  description: このglobに一致する論理IDが行になる（例 SCR-*）
                - key: schemaIds
                  label: 出現スキーマの限定
                  type: array
                  item:
                    type: string
                  description: 指定時、いずれかのスキーマに出現するIDだけを行にする
            - key: order
              label: 行の並び
              type: object
              fields:
                - key: schemaId
                  label: 並び基準の出現スキーマ
                  type: string
                - key: fileGlob
                  label: 並び基準の出現ファイル（glob）
                  type: string
              description: 条件に一致する出現の文書内順で並べる（省略時はID順）
            - key: columns
              label: 列の定義
              type: array
              description: "横断グリッドのみ（必須）: 一覧の列"
              item:
                type: object
                fields:
                  - key: label
                    label: 見出し
                    type: string
                    required: true
                  - key: schemaId
                    label: 値を取る出現のスキーマ
                    type: string
                  - key: fileGlob
                    label: 値を取る出現のファイル（glob）
                    type: string
                  - key: path
                    label: 相対パス
                    type: string
                    description: 出現要素からの相対YPath（例 name / areas / inputs[0].type）。path / backlinks / definition のいずれか一つが必要
                  - key: definition
                    label: 定義参照
                    type: string
                    description: named definition（module#name。schemaId 指定時は名前だけでも可）のセル型フィールドを列集合に展開する（ラベル・パスは定義から導出。label / path / backlinks とは併用不可）
                  - key: editable
                    label: 一覧上で編集可
                    type: boolean
                    default: false
                  - key: backlinks
                    label: バックリンク列
                    type: object
                    description: 行IDへの被参照（参照元）を一覧表示する列にする（path / editable とは併用不可）
                    fields:
                      - key: relation
                        label: 関係種別の限定
                        type: string
                        description: 参照の relation がこの値の被参照だけを表示する（省略時は全被参照）
                      - key: schemaIds
                        label: 参照元スキーマの限定
                        type: array
                        item:
                          type: string
                        description: 参照元文書のスキーマIDを限定する（省略時は制限なし）
                  - key: computed
                    label: 計算列
                    type: object
                    description: 決定論的射影の計算列（R16-02）。対象IDの出現から読んだ元値（テンプレート）を評価して表示する読み取り専用の派生列（path / backlinks / editable とは併用不可）
                    fields:
                      - key: lookup
                        label: 参照の1ホップ
                        type: object
                        description: 行の出現の ref フィールドを辿り、対象を参照先IDへ切り替える（省略時は行のID自身が対象）
                        fields:
                          - key: schemaId
                            label: 参照値を読む出現のスキーマ
                            type: string
                          - key: fileGlob
                            label: 参照値を読む出現のファイル（glob）
                            type: string
                          - key: via
                            label: ref フィールドの相対パス
                            type: string
                            required: true
                            description: 出現要素からの相対YPath（例 type）。この値（ID）の先が対象になる
                      - key: source
                        label: 元値を読む出現
                        type: object
                        required: true
                        description: 元値（テンプレート）を読む対象IDの出現の選択（schemaId / fileGlob のいずれかが必要）
                        fields:
                          - key: schemaId
                            label: スキーマ
                            type: string
                          - key: fileGlob
                            label: ファイル（glob）
                            type: string
                      - key: path
                        label: 元値の相対パス
                        type: string
                        required: true
                        description: source 出現要素からの相対YPath。値の {パス} プレースホルダは評価スコープの値で置換される（例 varchar({maxLength})）
                      - key: values
                        label: プレースホルダ解決に使う出現
                        type: array
                        description: 対象IDのこの出現の値でプレースホルダを解決する（解決順は source 要素 → values（宣言順）→ lookup の起点要素）
                        item:
                          type: object
                          fields:
                            - key: schemaId
                              label: スキーマ
                              type: string
                            - key: fileGlob
                              label: ファイル（glob）
                              type: string
                      - key: map
                        label: 値辞書
                        type: map
                        value:
                          type: string
                        description: 評価後の値を辞書で置換する（辞書に無い値はそのまま）
                      - key: default
                        label: 既定値
                        type: string
                        description: 元値が無いときに使う値
            - key: detail
              label: 詳細文書
              type: object
              fields:
                - key: schemaId
                  label: 詳細と見なす出現のスキーマ
                  type: string
                - key: fileGlob
                  label: 詳細と見なす出現のファイル（glob）
                  type: string
                - key: filePattern
                  label: 新規作成先パターン
                  type: string
                  description: 例 designs/{id}.yaml（{id} が行のIDで置換される）
            - key: newRow
              label: 新規行の入口
              type: object
              fields:
                - key: file
                  label: 一覧文書のファイル
                  type: string
                  description: 配列モード。path と対で指定し、一覧文書内の配列へ新しい要素を追加する
                - key: path
                  label: 追加先の配列YPath
                  type: string
                - key: filePattern
                  label: 新規作成先パターン
                  type: string
                  description: ファイルモード。例 designs/{id}.yaml（入力したIDで {id} を置換した詳細文書を新規作成する。file / path とは併用不可）
    """.trimIndent()

    /** 組込みスキーマ一覧（読み込み失敗はあり得ない前提。失敗時は例外で気付かせる）。match はレガシー配置。 */
    val all: List<SchemaDef> by lazy {
        listOf(
            SchemaLoader.parseSchemaText(agentSchemaYaml, Path.of("(builtin)/agent.yaml")),
            SchemaLoader.parseSchemaText(activitySchemaYaml, Path.of("(builtin)/activity.yaml")),
            SchemaLoader.parseSchemaText(evalSchemaYaml, Path.of("(builtin)/eval.yaml")),
            SchemaLoader.parseSchemaText(skillSchemaYaml, Path.of("(builtin)/skill.yaml")),
            SchemaLoader.parseSchemaText(viewSchemaYaml, Path.of("(builtin)/view.yaml")),
            SchemaLoader.parseSchemaText(metaSchemaYaml, Path.of("(builtin)/schema.yaml")),
            SchemaLoader.parseSchemaText(relationsSchemaYaml, Path.of("(builtin)/relations.yaml")),
        )
    }

    /** 組込みスキーマID → 適用先ゾーン（match の書き換えに使う。R13-02）。 */
    private val zoneById = mapOf(
        "builtin-agent" to WorkspaceLayout.Zone.AGENTS,
        "builtin-activity" to WorkspaceLayout.Zone.ACTIVITIES,
        "builtin-eval" to WorkspaceLayout.Zone.EVALS,
        "builtin-skill" to WorkspaceLayout.Zone.SKILLS,
        "builtin-view" to WorkspaceLayout.Zone.VIEWS,
        META_SCHEMA_ID to WorkspaceLayout.Zone.SCHEMAS,
        RELATIONS_SCHEMA_ID to WorkspaceLayout.Zone.SCHEMAS,
    )

    /**
     * レイアウトに合わせて match を各ゾーンの実ディレクトリへ書き換えた組込みスキーマ一覧（R13-02）。
     * レガシー構成では `all` と同一。SchemaDef は data class のため copy で match だけ差し替える
     * （glob の正規表現は lazy で再コンパイルされる）。
     */
    fun forLayout(layout: WorkspaceLayout): List<SchemaDef> {
        if (layout.configDir == null) return all
        return all.map { builtin ->
            val zone = zoneById[builtin.id] ?: return@map builtin
            builtin.copy(match = "${layout.dir(zone)}/**/*.{yaml,yml}")
        }
    }

    /** 組込みスキーマの原文（テスト用: すべてがメタスキーマ検証を通ることのドッグフーディングに使う）。 */
    internal val allYamlTexts: List<String>
        get() = listOf(metaSchemaYaml, relationsSchemaYaml, agentSchemaYaml, activitySchemaYaml, evalSchemaYaml, skillSchemaYaml, viewSchemaYaml)
}
