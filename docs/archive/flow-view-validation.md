# フロービューの設計検証

[実装計画](flow-view.md)の設計段階で、既存CLIによる変換・検査を確認した結果。
本体の検証状況は実装計画の「コア実装の検証」を参照する。本書は既存の変換・検査語彙による設計段階の確認を扱う。

## 変換と来歴

実装計画の「フロービューの記法案」からprojectionを取り出し、「BZFL-0002への適用案」の構造例を入力した。
Git管理外の `scratchpad/flow-view-design-check/` にprojection.yamlとinput.yamlを置き、次を実行した。

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat runCli -q --no-daemon --console=plain --args="transform labs/user-man scratchpad/flow-view-design-check/projection.yaml --input scratchpad/flow-view-design-check/input.yaml --origins"
```

| 確認対象 | 結果 |
|---|---|
| 要素数 | 主活動5件と任意活動2件からノード7件、辺4件、レーン3件 |
| 任意活動 | BACT-0010・0011を「任意」の見出し付きで表示用データへ出力 |
| 主活動の原本 | nodes[0..4].detailがactivities[0..4]を指す |
| concat後の原本 | nodes[5].detailがsupporting_activities[0]、nodes[6].detailがsupporting_activities[1]を指す |
| 辺の原本 | edges[0..3].detailがtransitions[0..3]を指し、原本の遷移IDをkeyに保持 |
| 図形の区別 | outcomeがroundedと「到達点」、handoffがboxと「引渡し」 |

確認対象は変換結果と原本内のYPath。CLIのraw入力のDocumentId表現は、本体のワークスペース相対IDと同一とは限らない。
ビュー解決時には相対DocumentIdを渡し、別途テストする。

## 文書内参照と形式

独立した検証用ワークスペースを `scratchpad/flow-view-design-check/workspace/` に作成した。
下記スキーマを `.yde/schemas/flow.yaml` に配置し、`flows/` の21文書を対象にchecksとvalidateを実行した。
検証専用のID・型名であり、ユーザ管理仕様のID体系を確定するものではない。

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat runCli -q --no-daemon --console=plain --args="checks scratchpad/flow-view-design-check/workspace"
.\gradlew.bat runCli -q --no-daemon --console=plain --args="validate scratchpad/flow-view-design-check/workspace --sources"
```

checksでスキーマの9制約が基本の検査として読まれ、schemaErrorsが空になることを確認した。
validateは正常3文書・異常18文書に対し、正常文書の指摘なし、異常文書はすべてエラーを検出した。
合計errors=31、warnings=0、withheld=3、schemaErrorsは空。複数の制約に違反する入力は複数の指摘を持つ。

| 入力 | 結果 |
|---|---|
| valid-new・foreign | 同一文書の主体・主活動への参照が成立する新形式を受理 |
| valid-old | 非空のbasic_flowだけを持つ旧形式を受理 |
| foreign-actor | 主活動・任意活動から別文書の主体への参照を検出 |
| foreign-support-actor | 任意活動だけが別文書の主体を参照する場合も検出 |
| foreign-from・foreign-to | 遷移の各端点が別文書の主活動を参照する場合を検出 |
| support-target | 同じ文書でも任意活動は主経路の遷移先として拒否 |
| unknown-actor・unknown-to | 未解決参照を検出し、該当する文書内検査は保留 |
| missing-actor・missing-from | 必須の参照欠落を構造検査で検出 |
| both・neither | 旧新形式の両方存在・両方欠落を検出 |
| empty-old・empty-new | 旧形式・新形式の空配列を検出 |
| null-old・null-new | nullを型違反と空値の検査で検出 |
| old-null-new・new-null-old | 一方が正常で他方がnullでも併記として拒否 |
| missing-transitions | 新形式でのtransitions欠落を検出 |

containsは参照先の親フローへ戻り、その子として参照元と同じ要素に到達できるかを調べる。
主体・活動・遷移はフロー直下のID付き要素とし、主活動と任意活動は異なるエンティティ型にする。
構造をさらに入れ子にする場合は、この検査をそのまま流用せず包含関係を再検証する。
未解決参照による保留は充足を意味しない。参照解決のエラーとあわせて扱う。

existsはキーの存在を扱い、nullでもtrueになる。排他だけでは不十分なので、配列の構造検査とemptyの条件を併用する。
transitionsは新形式でキーを必須にするが、単一活動など接続不要の場合の空配列は認める。

## 再現用入力

新形式の基本入力は次のとおり。CASEを文書固有の名前に置換すればIDの重複を避けられる。
別文書参照の確認ではCASEをforeignにした正常文書を併置し、対象のactor、fromまたはtoだけをそのIDへ変更する。
表の各異常入力は基本入力の該当項目を変更・削除して作る。旧形式はidと `basic_flow: [old]` の2項目とする。

```yaml
id: FLOW-CASE
actors: [{id: ACTOR-CASE}]
activities: [{id: ACTIVITY-CASE, actor: ACTOR-CASE}]
supporting_activities: [{id: SUPPORT-CASE, actor: ACTOR-CASE}]
transitions: [{id: TRANS-CASE, from: ACTIVITY-CASE, to: ACTIVITY-CASE}]
```

## 検証用スキーマ

業務項目を省略し、文書内の参照と移行条件に絞った定義。原本移行時は本番スキーマへ既存項目と統合する。

```yaml
id: flow-check
name: フロー設計検証
match: flows/*.yaml
entity: {type: flow, idPattern: 'FLOW-*', defines: true}
root:
  type: object
  fields:
    - {key: id, type: id, required: true}
    - key: actors
      type: array
      item:
        type: object
        entity: {type: flow-actor, defines: true}
        fields:
          - {key: id, type: id, required: true}
    - key: activities
      type: array
      item:
        type: object
        entity: {type: flow-activity, defines: true}
        fields:
          - {key: id, type: id, required: true}
          - {key: actor, type: ref, required: true}
    - key: supporting_activities
      type: array
      item:
        type: object
        entity: {type: flow-supporting-activity, defines: true}
        fields:
          - {key: id, type: id, required: true}
          - {key: actor, type: ref, required: true}
    - key: transitions
      type: array
      item:
        type: object
        entity: {type: flow-transition, defines: true}
        fields:
          - {key: id, type: id, required: true}
          - {key: from, type: ref, required: true}
          - {key: to, type: ref, required: true}
    - key: basic_flow
      type: array
      item: {type: line}
constraints:
  - match:
      - node: {entity: flow-activity, bind: s}
      - edge: {via: actor}
      - node: {entity: flow-actor}
      - edge: {contains: true, dir: in}
      - node: {entity: flow}
      - edge: {contains: true}
      - node: {same: s}
    expect: exists
    level: error
    message: 'same-document:flow-activity:actor'
  - match:
      - node: {entity: flow-supporting-activity, bind: s}
      - edge: {via: actor}
      - node: {entity: flow-actor}
      - edge: {contains: true, dir: in}
      - node: {entity: flow}
      - edge: {contains: true}
      - node: {same: s}
    expect: exists
    level: error
    message: 'same-document:flow-supporting-activity:actor'
  - match:
      - node: {entity: flow-transition, bind: s}
      - edge: {via: from}
      - node: {entity: flow-activity}
      - edge: {contains: true, dir: in}
      - node: {entity: flow}
      - edge: {contains: true}
      - node: {same: s}
    expect: exists
    level: error
    message: 'same-document:flow-transition:from'
  - match:
      - node: {entity: flow-transition, bind: s}
      - edge: {via: to}
      - node: {entity: flow-activity}
      - edge: {contains: true, dir: in}
      - node: {entity: flow}
      - edge: {contains: true}
      - node: {same: s}
    expect: exists
    level: error
    message: 'same-document:flow-transition:to'
  - match: [{node: {entity: flow, bind: f}}]
    where:
      - {of: f, path: basic_flow, exists: true}
      - {of: f, path: activities, exists: true}
    expect: absent
    level: error
    message: format-both
  - match: [{node: {entity: flow, bind: f}}]
    where:
      - {of: f, path: basic_flow, exists: false}
      - {of: f, path: activities, exists: false}
    expect: absent
    level: error
    message: format-neither
  - match: [{node: {entity: flow, bind: f}}]
    where:
      - {of: f, path: basic_flow, exists: true}
      - {of: f, path: basic_flow, empty: true}
    expect: absent
    level: error
    message: format-empty-old
  - match: [{node: {entity: flow, bind: f}}]
    where:
      - {of: f, path: activities, exists: true}
      - {of: f, path: activities, empty: true}
    expect: absent
    level: error
    message: format-empty-new
  - match: [{node: {entity: flow, bind: f}}]
    where:
      - {of: f, path: activities, exists: true}
      - {of: f, path: transitions, exists: false}
    expect: absent
    level: error
    message: transitions-required
```
