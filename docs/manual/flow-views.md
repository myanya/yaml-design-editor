# フロービュー

`kind: flow`で、文書の内容や複数文書のクエリ結果をノード・辺・レーンへ変換できます。台帳の「フロー」タブ、文書フォームへの埋め込み、CLIとエージェントの`read_view`で利用できます。

図の「PNG に保存」で、表示中の配置を画像に書き出せます。記法の画像・区画・端点記号も含まれます。
ノード内のカラムなどへ線を接続する場合は、共通の[ポート](diagram-notations.md#ポートで行へ接続する)を記法で指定します。

## 配置と最小例

ワークスペース直下に次の3ファイルを作ります。業務内容は原本、構造・参照はスキーマ、表示への変換はビューが担当します。
`projection`はビュー内に書き、別の変換ファイルを置く必要はありません。
親子の配列から辺を作る例は [ER図のフロー](diagram-notations.md#入れ子の外部キーをフローへ写す) を参照してください。

`.yde/schemas/process.yaml`：

```yaml
id: process
match: processes/*.yaml
root:
  type: object
  fields:
    - key: activities
      type: array
      item:
        type: object
        fields:
          - {key: id, type: id, required: true}
          - {key: title, type: string, required: true}
    - key: transitions
      type: array
      item:
        type: object
        fields:
          - {key: id, type: id, required: true}
          - {key: from, type: ref, required: true}
          - {key: to, type: ref, required: true}
```

`processes/example.yaml`：

```yaml
activities:
  - {id: ACT-001, title: 依頼する}
  - {id: ACT-002, title: 確認する}
transitions:
  - {id: TRANS-001, from: ACT-001, to: ACT-002}
```

`.yde/views/process-flow.yaml`：

```yaml
id: process-flow
name: 手続の流れ
kind: flow
schemaId: process
layout: {direction: down}
projection:
  id: process-projection
  build:
    nodes:
      $each: activities
      $build:
        key: {$copy: id}
        label: {$copy: title}
        detail: {$copy: .}
    edges:
      $each: transitions
      $build:
        key: {$copy: id}
        from: {$copy: from}
        to: {$copy: to}
        detail: {$copy: .}
```

## 複数文書の設定から図を導く

`sources`にクエリ変換を並べると、各変換の結果がその`id`の配列として`projection`へ渡ります。
たとえばサービスの設定が個別の文書に分かれていても、設定の参照を辿って構成図を作れます。図のための接続文書や転記は要りません。

```yaml
sources:
  - id: services
    from:
      match: [{node: {entity: service, bind: service}}]
      limit: 1000
    build: {$copy: .}
projection:
  id: services-diagram
  build:
    nodes:
      $each: services
      $build:
        key: {$copy: id}
        label: {$copy: name}
        detail: {$copy: .}
    edges:
      $each: services
      $build:
        $when: {path: target, exists: true}
        from: {$copy: id}
        to: {$copy: target}
        detail: {$copy: .}
```

`match`・`schemaId`は図を開く対象文書を選びます。sourcesがある場合、その文書は`document`というキーで入力に残ります。
sourcesを省略すると、従来どおり対象文書そのものが入力です。

- 各sourceは既存の[変換](../architecture/transform.md)で、`from`が必須です。外部`params`とsources間の参照は使えません。
- sourceのIDは英字から始まる英数字・`_`・`-`で、重複と予約名`document`は使えません。
- 未保存の設定変更も同じ索引と原本から解決し、Undoも通常の編集経路を使います。
- 丸ごと写した単一原本をeachで表示する詳細は、その原本を編集できます。複数原本を結合した詳細は出どころへの移動で確認します。
- クエリの打切りや、未解決参照で経路を辿れない場合は診断に出ます。空の図や一部の辺だけを見て設定の正しさを判断せず、ワークスペースの検証も実行してください。

## 実行

リポジトリのルートから、`my-workspace`を上記ファイルを置いたワークスペースへ読み替えて実行します。

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat runCli -q --args="view my-workspace process-flow processes/example.yaml"
```

`view my-workspace`でビュー一覧、`view my-workspace process-flow`で対象候補を確認できます。
適用対象が1文書だけなら自動選択し、複数なら候補一覧を返します。明示する文書はワークスペース内に限定します。
エージェントの`read_view`もviewに`process-flow`、fileに`processes/example.yaml`を指定すると同じ形式の結果を返します。

## 画面での操作

1. ワークスペースを開き、「台帳」→「フロー」で「手続の流れ」を選びます。対象文書が複数ある場合は文書も選択します。
   業務の流れ（`flow`）を持つダッシュボードも「業務の流れ: 名前」として並びます。対象文書は選ばず、ここで動かした箱の位置がダッシュボードの流れ図にも出ます（[ダッシュボード](dashboards.md)「業務の流れ（flow）」）。
2. ノードまたは辺のラベル・線をクリックすると、右側に詳細フォームが表示されます。
3. 活動のtitleを編集すると、未保存のまま図のラベルに反映されます。Undo・Redoも通常の編集と共通です。「すべて保存」で原本を保存します。
4. 参照フィールドや「原本へ」から移動し、ツールバーの「戻る」で図へ戻れます。

図は最初に全体が収まる倍率で表示します。背景のドラッグで画面を移動、「＋」「−」で拡大・縮小できます。
ノードのドラッグでは周囲を動かさず、その位置で固定します。Esc で中断できます。固定解除はその場に留まり、「再配置」で未固定ノードを並べ直します。
線のラベルをドラッグすると、その位置が線の経由点になり、線はそこを通るように引き直されます。「選択した線を自動に戻す」で元に戻せます（[図のノード位置](diagram-positions.md)「線の経由点（フロー）」）。
辺の中央ラベルは線の近くへ配置し、テーブル間などの狭い場所では自動で折り返します。ノードや端の多重度との重なりを避け、PNGにも同じ配置を使います。
現在位置と固定をワークスペース内に保存します。「配置を初期化」は全解除して自動配置します。詳しくは [図のノード位置](diagram-positions.md) を参照してください。
「全体」で図全体を再び画面に収め、「表示をリセット」で移動量をゼロ・倍率を100%に戻します。
図の上部の「レイアウト」で、次の表示を選べます。

| 表示 | 配置 |
|---|---|
| グループ（初期値） | 担当する工程を枠で囲み、つながりに合わせて配置する |
| スイムレーン | 担当別の帯を定義順に固定し、工程を帯の中へ配置する |
| レーンなし | 担当別の枠を表示せず、工程と接続線を配置する |

「方向」は「ビュー定義に従う」（初期値）・「上から下」・「左から右」から選びます。
スイムレーンは上から下なら縦帯、左から右なら横帯になります。担当未設定の工程は帯の外に置き、空のレーンも残します。
レーンの定義がない文書では帯を表示しません。担当者を増やす場合は原本のレーン・所属定義を編集します。
変更すると全体が収まるよう再配置します。設定はビュー・文書ごとにワークスペースを開いている間保持し、台帳と文書内の図で共有します。
表示の切替では原本の業務内容・参照・選択を変更せず、ファイルへの保存も行いません。

線が多い図で箱どうしが離れすぎるときは、「配置の調整」で自動配置の詰め方を変えられます。いくつでも組み合わせられ、押してもメニューは閉じません。

| 調整 | 効果 |
|---|---|
| 間隔を詰める | 箱どうしと線の通り道の余白を小さくします |
| ラベルの席を取らない | ラベルの場所を確保せずに列の間を詰め、ラベルは配置の後で線の近くへ置きます（狭い所では折り返します） |
| 長い一本道を折り返す | 流れの向きに長く続いて画面に収まらない図だけを折り返します |

調整は自動で並べるとき（初めて表示したとき・「配置を初期化」・手動固定の無い「再配置」）に効きます。
選んだ組み合わせごとに位置を別に覚え、外すと前の並びに戻ります。
「レイアウト」「方向」と同じく、ワークスペースを開いている間だけ保持します。

「線のラベル」のチェックを外すと、線の中央のラベル（条件など）を隠します。選んだ線のラベルは隠していても表示します。
多重度のような線の端のラベルは記法の意味なので残します。箱は動かさず、PNG も画面と同じに出します。
隠したラベルの分まで詰めたいときは、「ラベルの席を取らない」と組み合わせてください。

詳細の直接編集に適格でない射影は、導出結果と原本へのリンクを表示します。閲覧専用モードでは編集できません。

一意なkeyを持つ選択は、並べ替え後も現在のモデルから再解決します。keyの削除・重複で選択を解除します。
keyがない辺は、原本またはフロー定義の版が変わると解除します。Undoで同じ内容に戻しても、古い選択は復活しません。
ノード・辺・レーンの問題は右側の診断に出ます。接続先が不明な辺もラベルを残し、誤った相手には接続しません。

## 文書への埋め込み

`.yde/views/process-document.yaml`を追加すると、原本のフォーム内でも同じ図を表示できます。

```yaml
id: process-document
name: 手続の文書
kind: document
schemaId: process
sections:
  - label: 手続の流れ
    flow: {view: process-flow}
  - label: 原本の項目
    fields: {}
```

各セクションはfields・backlinks・flow・chartのいずれか1つを持ちます。flow.viewは一意なflowビューのIDを指定します。
埋め込み先は現在の文書です。match・schemaIdへの適合は表示時に判定し、不一致は診断として表示します。
図の高さは固定し、「拡大表示」で同じ文書を台帳のフローへ開きます。原本・フロー定義の未保存編集にも追随します。
CLIの文書ビュー結果は各セクションのflowに表示モデルを含み、read_viewでも同じモデルと座標を確認できます。

## 結果の読み方

| 項目 | 内容 |
|---|---|
| model | nodes・edges・lanes、要素ごとのoutput位置とdetail |
| detail.origins | 原本の相対文書IDとYPath |
| detail.editable | 詳細を原本へ対応付けて編集できる射影かどうか |
| origins | 表示項目全体の変換来歴 |
| diagnostics | コード・重大度・メッセージ・出力位置・原本または定義の位置 |
| errors | ビュー定義の読取・リントの指摘 |

例ではノード2件・辺1件になり、最初のノードの原本は`processes/example.yaml`の`activities[0]`です。
辺のfrom・toのstateは`resolved`になります。toを未知のIDへ変えると、辺を保持したまま`unresolved`の診断を返します。
原本のIDを重複させた場合は`ambiguous`となり、任意の一方へ接続しません。

editableは利用者の編集権限を表しません。値は表示用のコピーであり、変更は原本への既存の編集経路で行います。
図の解決によるファイルの生成・更新はありません。

## 定義の範囲

- `match`か`schemaId`を指定し、両方指定した場合はANDで適用します。
- directionはdownまたはright、既定はdownです。
- ノードにはkey・label・detail、辺にはfrom・to・detailを持たせます。辺のkey・labelは任意です。
- ノードのshapeはbox・diamond・roundedです。未知のshapeは診断付きでboxへ代替します。
- `appearance.nodes` で[図の記法](diagram-notations.md)を適用できます。射影ノードの任意の `entityType` は適用を選ぶための明示的な分類です。
- lanesは任意の配列で、各要素にkey・labelを持たせ、ノードのlaneからkeyを参照します。
- 任意活動など別配列の要素も表示するときは、nodesの変換でeach同士をconcatします。
- 詳細の直接編集に適格なのは、配列eachの通常マップ直下に`detail: {$copy: .}`を置く形と、そのeach同士のconcatです。
  eachとcopyのof・in指定、条件付き・子パスのcopy・規則経由などは表示と原本への移動用になります。
- projectionは既存の[変換記法](../architecture/transform.md)を使いますが、fromによる入力選択と外部paramsは指定できません。

文書内の主体や遷移先の制約など、業務データの正しさは原本スキーマで定義します。表示モデルの診断とは別にvalidateで確認してください。

## 改名と移動

原本のIDは、一括改名で参照型の遷移元・遷移先も更新されます。copyで読み出している図は更新後の原本を表示します。
埋め込みの`flow.view`も参照型なので、ビューIDの一括改名に追随し、参照されているビューの一括削除は拒否されます。
ビューIDと埋め込み先の変更は同じトランザクションになり、一回のUndoで戻せます。

projection内の項目パス・固定ID・関係名は自動置換しません。相対パス・規則・条件・辞書の意味を一意に確定できないためです。
一括改名の計画には、該当するファイルと位置を「自動書換の対象外・要確認」として表示します。
この言及調査は保守的で、説明文や別用途の同名の値も含みます。警告箇所を確認し、必要な投影を修正してから再検証してください。
原本文書の移動でmatchから外れる場合も、単独フローと文書ビューの警告を確認します。
