# スキーマ定義リファレンス


`_schemas/*.yaml` に 1 ファイル 1 スキーマまたは共通定義ライブラリで配置します。

```yaml
id: requirement                       # 安定したスキーマID（省略時はスキーマファイル名）
name: 要求                             # 表示用のスキーマ名
kind: document                        # document（既定）/ library（共通定義のみ）
description: |                         # スキーマ全体の説明（任意・複数行）
  要求事項を管理するスキーマです。
  要求の状態と関連を一貫した形式で記録します。
match: "requirements/**/*.{yaml,yml}" # 適用対象の glob（**, *, ?, {a,b} に対応）
root:                                 # ルートのフィールド定義（通常 type: object）
  type: object
  fields:
    - key: id
      label: 要求ID
      type: id
      required: true
      description: 論理要素を識別するID（同一文書内で一意）
    - key: status
      type: enum
      values: [draft, review, approved, deprecated]
      default: draft
    - key: relates_to
      type: array
      item:
        type: ref
        idPattern: "REQ-*"            # 利用者定義のID体系で候補を限定
        scope:
          schemaIds: [requirement]    # 安定したスキーマIDで探索範囲を限定
```

トップレベルの `description` には、スキーマの目的や対象を説明する複数行の文を任意で指定できます。スキーマ設計画面では複数行テキスト欄として編集できます。

## フィールド定義のプロパティ

| プロパティ | 説明 |
|---|---|
| `key` | 親オブジェクト内のキー名（ルート・配列要素では不要） |
| `label` | フォームでの表示名（省略時は `key`） |
| `type` | 下表の基底の種類か、型の名前（`definitions` に定義したスカラーの型・組込みの型 `text`＝後述「型の定義」。省略時 `string`） |
| `required` | `true` で必須。未設定は検証エラー |
| `requiredFrom` | フェーズ機構（後述）: このフェーズ以降で必須。`required` と併用不可 |
| `expression` | `true` で、この項目の値は値の式でもよくなる（短い書き方＝`{パス}` を含む文字列・完全な書き方＝式の object）・基底の種類が string の型のみ。詳しくは「新しい文書の置き場所とひな型」 |
| `values` | `enum` の選択肢。文字列、または `{value, label, description}` のリッチ選択肢（後述） |
| `role` | 表示役割 `title` / `subtitle`（後述の表示解決）。このフィールドの値を要素の要約タイトル／サブタイトルに使う |
| `cases` | `enum` の値ごとに、親オブジェクトへ追加される兄弟フィールドを定義（後述） |
| `item` | `array` の要素定義（フィールド定義を再帰的に記述） |
| `display` | `array` / `map` のフォーム表示形式（`table`＝表形式に固定〔map はキー列つき〕、`list`＝縦並びに固定、省略時は自動判定＝要素〔array は `item`、map は `value`〕が object で、スカラー系の列が2〜8個 かつ 項目の過半を占めるとき表形式）、`int` / `number` では `grouped`＝桁区切り（45000 → 45,000・フォームとビューの見せ方だけで、値・検証・CLI や書き出しは変えず、編集中は元の表記で、打ち込んだ区切りは落とす） |
| `display`（`string` 系） | `multiline`＝複数行の欄で編集する（組込みの型 `text` のヒント。見せ方だけで値・検証は変えない） |
| `rows` | 複数行の欄の行数（高さの目安。`text` など `display: multiline` の型で） |
| `format` | 画面で見せる形（`int` / `number` は DecimalFormat のパターン＝例 `¥#,##0`・`date` / `time` / `datetime` は DateTimeFormatter のパターン＝例 `yyyy年M月d日`）。ファイルと編集中は素の値のままで、`display: grouped` より優先 |
| `value` | `map` の値定義（必須。フィールド定義を再帰的に記述） |
| `fields` | `object` の子フィールド定義のリスト |
| `unknownFields` | `object` のスキーマ外項目を `allow` / `warning` / `error` で扱う（省略時 `warning`） |
| `idPattern` / `idPatterns` | `ref` の参照先IDをglob（`*`, `?`）で限定。複数指定はOR |
| `scope.schemaIds` | 参照先を特定スキーマIDに限定。配列内はOR |
| `scope.documentIds` | 参照先を特定ドキュメントIDに限定。配列内はOR |
| `relation` | `ref` の関係種別（例 `implements` / `depends`）。解決可否には影響せず、表示とカバレッジ判定に使う |
| `minLength` / `maxLength` | `string` / `text` の文字数制約。違反は検証エラーで、AI生成の JSON Schema にも伝搬する |
| `pattern` | `string` / `text` / `id` の形式制約（正規表現＝値の全体が一致すること・例 `"^[a-z][a-z0-9_]*$"`）。`id` に書くとIDフォーマットの強制になり（例 `TASK-\d{3}`）、自動採番の提案（フォームのID入力メニュー）のパターン源にもなる |
| `min` / `max` | `int` / `number` の範囲制約。違反は検証エラー |
| `default` | 「追加」「作成」時の初期値 |
| `placeholder` | 入力欄が空のときに薄く出す案内（例 `YYYY-MM-DD`）。型に書けば、その型を使う項目に引き継がれる |
| `sample` | プレビューのサンプル YAML で、初期値が無いときに使う値（例 `2026-01-01`）。型に書けば引き継がれる |
| `description` | フォームに表示される説明文 |

## 型一覧

| type | フォーム表現 | 検証 |
|---|---|---|
| `string` | 自由な文字列。欄は 1 行で始まり、改行が入れば縦に伸びる | required |
| `text` | 複数行の編集を保証した `string`（組込みの型＝後述「型の定義」。`rows: 5` で欄の行数） | required |
| `line` | 改行なしを保証した `string`（組込みの型。空白は可） | 改行を含まないこと |
| `word` | 1 語（空白なし）を保証した `line`（組込みの型） | 空白を含まないこと |
| `int` | テキスト（整数変換） | 整数であること |
| `number` | テキスト（数値変換） | 数値であること |
| `boolean` | チェックボックス | 真偽値であること |
| `enum` | ドロップダウン | `values` に含まれること。`cases` で値ごとの追加フィールドを定義可能 |
| `date` | テキスト（意味の基底型＝内部は日付・ファイルは `YYYY-MM-DD`・案内とサンプル値は型の既定） | `YYYY-MM-DD` 形式に一致し、存在する日付であること（`2026-02-30` は誤り） |
| `time` | テキスト（意味の基底型＝案内 `HH:MM`） | `HH:MM` か `HH:MM:SS` 形式に一致し、存在する時刻であること |
| `datetime` | テキスト（意味の基底型＝案内 `YYYY-MM-DD HH:MM`） | `YYYY-MM-DD HH:MM`（秒は任意・区切りは空白か `T`）形式に一致し、存在する日時であること |
| `id` | テキスト＋重複警告＋被参照数 | 必須チェック、重複検出、空白を含まないこと（直列化は `word`） |
| `ref` | テキスト＋候補ドロップダウン＋ジャンプボタン。解決済みの参照は入力欄の枠内右端に参照先タイトルを併記（IDと表示名を1つの領域で確認できる） | 参照解決、空白を含まないこと（直列化は `word`） |
| `array` | 要素の追加・削除・並べ替え | 要素ごとに `item` を再帰検証 |
| `map` | 任意キーの追加・削除＋型付き値エディタ | 各キーの値を `value` で再帰検証 |
| `object` | 枠付きのネストフォーム | `fields` を再帰検証 |
| `any` | 汎用エディタ（値の形に合わせた編集） | 検証しない（必須チェックのみ）。単一の型で表せない値の明示に使い、AI生成では `map` と同様に構造化出力が無効化される |

`object` / `array` / `map` は任意の深さでネストできます（例: サブ項目の配列の中に `id` を持たせて、サブ項目単位で参照させることも可能）。

`object` にスキーマ外のキーがある場合、既定ではそのキーの位置に警告を1件表示します。`unknownFields: error` ならエラー、`allow` なら通知しません。未知キーの配下はスキーマ検証およびID・参照索引の対象外とし、配下の各要素へ同じ問題を重複表示しません。ルートの予約項目 `_document` はこの検査から除外されます。

## 任意キーのMap

任意の文字列キーを持つ連想配列は `map` で定義します。`value` は必須で、Map内のすべての値に同じフィールド定義が適用されます。

```yaml
root:
  type: object
  fields:
    - key: inputs
      label: 入力
      type: map
      value:
        type: string
```

上記のスキーマでは、次のようなデータをフォームから追加・編集でき、各値が `string` として検証されます。

```yaml
inputs:
  username: ユーザ名
  password: パスワード
  email: メールアドレス
```

`value` には `object`、`array`、`ref`、別の `map` など、すべての型を再帰的に指定できます。

フォームではMapの件数と各キーが表示されます。「キーを追加」で任意のキーを作成し、値は `value` で指定した型のエディタで編集します。キーの重複と空文字は追加できず、不要なキーは行ごとに削除できます。Mapフィールド自体が未作成の場合は「Mapを作成」から空のMapを作成します。

`value` を省略したスキーマはスキーマ定義エラーになります。また、データ側がYAMLマッピングでない場合や、Map内の値が `value` の型に一致しない場合は検証エラーになります。

値をオブジェクトにする場合は、次のように定義します。

```yaml
- key: environments
  type: map
  value:
    type: object
    fields:
      - key: endpoint
        type: string
        required: true
      - key: enabled
        type: boolean
```

```yaml
environments:
  development:
    endpoint: http://localhost:8080
    enabled: true
  production:
    endpoint: https://example.com
    enabled: false
```

ルート定義に `type: map` を指定することもできます。

## enum のリッチ選択肢（label / description）

`values` の要素は文字列のほか、`{value, label, description}` の形で表示ラベルと説明を付けられます（混在可・後方互換）。

```yaml
- key: status
  type: enum
  values:
    - open                                       # 文字列だけの選択肢（従来どおり）
    - { value: in-progress, label: 対応中 }
    - value: closed
      label: 完了
      description: 対応が終わり、確認も済んだ状態
```

- **データ・検証・`cases` のキー・AI生成の制約は常に `value`** を使います。ファイルに保存されるのも `value` です。
- フォームのドロップダウンは `label` を表示し、現在の選択値に ✓ を付け、`description` を選択肢と選択後の欄外に表示します。`label` と `value` が異なる場合は候補に値も併記します。
- スキーマ設計UIの「選択肢」欄からも値・ラベル・説明を編集できます（ラベル・説明を空にすると文字列形へ戻ります）。

**選択肢をワークスペースのデータで管理したい場合**（選択肢自体に説明文書や参照関係が必要な場合、選択肢を文書として増やしたい場合）は、enum ではなく `type: ref` ＋ `scope` を使ってください。選択肢を1文書（または1要素）＝1IDの台帳として持ち、`scope.schemaIds` / `idPatterns` で候補を台帳に限定すれば、「ドロップダウンから選ぶ」体験のまま、選択肢の追加・説明・被参照の追跡が原本側の編集として管理できます（[ids.md](ids.md) 参照候補の絞り込み）。

## enum 値ごとの追加フィールド（cases）

「選択した値によって設定できる項目が変わる」構造（判別共用体）は、`enum` フィールドの `cases` で定義します。`cases` のキーは `values` のいずれかの値、値はフィールド定義のリストで、その enum 値が選択されているときだけ**親オブジェクトのフィールド**として有効になります。

```yaml
- key: type
  label: 入力種別
  type: enum
  values: [text, radio, checkbox, date]
  cases:
    radio:
      - key: options
        label: 選択肢
        type: array
        required: true
        item:
          type: string
    checkbox:
      - key: options
        label: 選択肢
        type: array
        item:
          type: string
```

上記のスキーマでは、`type: radio` の要素だけが `options` を持てます（`radio` のときは必須）。

挙動の要点:

- **フォーム**: case フィールドは判別フィールド（enum）の直後に行として表示され、enum の値を切り替えると表示が追随します。切り替えで不要になったキーのデータは削除されず、「現在の選択では使用しないキー」として案内つきで表示されます。
- **ネスト**: case から追加するフィールドをさらに `cases` つきの enum にできます。選択された case を順に展開し、追加フィールドはすべて同じ親オブジェクトの兄弟フィールドとして扱います。
- **検証**: アクティブな case のフィールドは通常のフィールドと同様に検証されます（`required` はその case が選択されているときのみ有効）。非アクティブな case のキーがデータに残っている場合は「「options」は「入力種別」が radio / checkbox のときのみ使用できます」のような案内になり、重大度はそのオブジェクトの `unknownFields` ポリシー（既定 warning）に従います。
- **ID・参照の索引**: アクティブな case フィールド配下の `type: id` / `type: ref` は通常どおり索引化されます。非アクティブな case のキー配下はスキーマ外サブツリーと同様に走査境界となります。
- **制約**: `cases` は `enum` 型でのみ使用できます。case のキーは共通フィールドや、同一オブジェクト内の別の enum の `cases` と重複できません（同じ enum の複数の case での重複は可。上例の `options`）。これらはメタスキーマ検証で検出されます。
- **`$ref`**: 名前付き定義の enum に `cases` を持たせて `$ref` で参照できます。

## 名前付き定義と再帰スキーマ

共通または再帰的な構造は `definitions` に置き、`$ref` で参照できます。同じスキーマ内は定義名だけ、別のスキーマ・ライブラリは `スキーマID#定義名` で参照します。名前付き定義はスキーマ内の再利用だけでなく、**ビュー定義からも参照できます**（グリッドの定義参照列・文書ビューの `fields.definition`。[views.md](views.md)）。ビューから参照したいデータ構造は `definitions` に括り出すのがおすすめです。

```yaml
name: outline
match: "outlines/**/*.yaml"
definitions:
  node:
    type: object
    fields:
      - key: title
        type: string
        required: true
      - key: children
        type: array
        item:
          $ref: node
root:
  $ref: node
```

複数の工程別スキーマで共通する構造は、業務文書へ直接適用されない `kind: library` に切り出せます。library は `definitions` だけを持ち、`match` / `root` / `phases` / `coverage` は持ちません。

```yaml
# _schemas/_common.yaml
id: common
name: 共通フィールド
kind: library
definitions:
  column:
    type: object
    fields:
      - { key: name, type: string, required: true }
      - { key: dataType, type: enum, values: [string, number, boolean] }
```

```yaml
# _schemas/logical-table.yaml
id: logical-table
name: 論理テーブル
match: "tables/logical/**/*.yaml"
root:
  type: object
  fields:
    - key: columns
      type: array
      item:
        $ref: common#column
```

修飾参照した共通定義の内部で非修飾 `$ref` を使った場合、その参照は利用側ではなく共通定義を宣言したライブラリ内で解決されます。ライブラリIDの重複と未解決参照はロード時のスキーマ定義エラーになります。

`_schemas` 配下のファイルを開くと「スキーマ設計」タブが表示され、document / library の切替、名前付き定義、ローカル／横断参照を構造化UIで編集できます。document から library への変更では適用定義が削除されるため確認ダイアログが表示されます。

スキーマ設計画面には次の確認機能があります。

- **メタスキーマ検証**: 必須情報、フィールドキー重複、arrayの要素定義、enumの選択肢、`cases` の整合（enum型のみ・値の存在・キー衝突）、未解決の `$ref` を保存前に検出
- **プレビュー**: スキーマからサンプルYAMLを生成し、実際のフォームまたはYAMLとして確認
- **サンプル生成**: 生成したYAMLをクリップボードへコピー
- **適用対象**: `match` に一致するワークスペース内のファイルを一覧表示し、他スキーマとの競合も表示
- **フェーズ・カバレッジ**: スキーマ全体設定の `phases`（申告キー・フェーズ順序）と `coverage`（参照ベース／出現ベースの切替・重大度・判定開始フェーズ）も構造化UIで編集できます（後述のリファレンス参照）

スキーマファイルを保存すると、メタスキーマ検証結果は通常の右側「検証」パネルにも表示されます。

## 型の定義（ユーザ定義の値型）

`definitions` にスカラーの定義（`string` / `int` / `enum` / `id` / `ref` などに決まりを足したもの）を置くと、それは**型**になり、項目の `type` に名前で書けます。XSD の simpleType や SQL の `CREATE DOMAIN` に当たります。同じスキーマ内は定義名だけ、別のスキーマ・ライブラリは `スキーマID#定義名` で書きます。

```yaml
# _schemas/common.yaml
id: common
kind: library
definitions:
  金額:         { type: int, min: 0, display: grouped, description: 円単位 }
  画面ID:       { type: id, pattern: "^SCR-\\d{3}$" }
  メールアドレス: { type: string, maxLength: 100, pattern: "^[^@]+@[^@]+$" }
  社員メール:    { type: メールアドレス, pattern: "^[^@]+@example\\.com$", maxLength: 50 }   # 型から型へ
  業務データ型参照: { type: ref, idPattern: "DT-*", relation: dtype, scope: { schemaIds: [data-type-list] } }
  予約状態:      { type: enum, values: [RESERVED, CANCELED] }
  有効な状態:    { type: 予約状態, values: [RESERVED] }                                        # 選択肢は部分集合

# 使う側（別のスキーマ）
root:
  type: object
  fields:
    - { key: id, type: common#画面ID, required: true }
    - { key: amount, label: 金額, type: common#金額 }
    - { key: mail, type: common#メールアドレス, maxLength: 50 }   # 使う側で狭める
```

- **型の連鎖**: 型の定義は必ず `type` を持ち、値は基底の種類（型一覧）か別の型の名前です。連鎖の末端が基底の種類で、フォームの編集欄・検証・AI 生成はその基底の種類で動きます。循環する定義（`a: { type: b }`・`b: { type: a }`）はスキーマの定義エラーです。
- **狭めるだけ**: 使う側（型から型へ、も同じ）に書いた決まりは型の決まりと合わさり、緩めることはできません。緩める書き方はスキーマの読み込みエラーになります（例: 「maxLength 200 は型「common#メールアドレス」の 100 より緩いため書けません（型は狭めるだけ）」）。

| 決まり | 規則 |
|---|---|
| `minLength` / `min` | 型の値以上 |
| `maxLength` / `max` | 型の値以下 |
| `pattern` | 型の形式と両方に一致すること（形式は重なる） |
| `values`（enum） | 型の選択肢の部分集合 |
| `cases` | 型に無いときだけ書ける |
| `required` / `requiredFrom` | 足すだけ |
| ref の `idPattern` | 重ね合わせ（型の絞り込みと使う側の絞り込みの両方に合う ID だけ） |
| ref の `scope` | 共通部分 |
| ref の `relation` | 型に無いときだけ。違う関係名は書けない |
| `unknownFields` | 厳しくするだけ（allow → warning → error） |
| `label` / `description` / `display` / `default` | 決まりではないので、使う側が優先 |

- **組込みの型（定義体）**: 文字列の階層 `text`（複数行を保証＝`{ type: string, display: multiline }`）・`line`（改行なし）・`word`（空白なし）は Kotlin の型ではなく、組込みの型の定義です。
- **意味の基底型**: `date` / `time` / `datetime` と `id` / `ref` は内部に値（日付・時刻・日時・ID・参照）を持ち、ファイルには正準の文字（`YYYY-MM-DD`、ID は `word`）で書きます。形式に合っても存在しない値（`2026-02-30`）は誤りになり、`予約時刻: { type: time, pattern: "^\\d{2}:(00|30)$" }` のように狭めた型を作れます。空白を含む ID・参照は誤りです。
- 利用者の型と同じ仕組みで、`type: text` や `type: date` から自分の型を作れます（`備考: { type: text, maxLength: 2000 }`・`予約日: { type: date, description: 会議室を使う日 }`）。同じ名前の定義を自分のスキーマに置けば、そちらが優先されます。
- **ヒントの引き継ぎ**: `label` / `description` / `display`（`multiline` / `grouped`）/ `format` / `placeholder` / `sample` / `default` は、使う側に書いたものが優先され、書かなければ型のものを引き継ぎます。
- **値と表示形式**: `金額: { type: int, min: 0, format: "¥#,##0" }` なら、ファイルと編集中は `20000`、見るときは `¥20,000` です（Excel の表示形式と同じ考え方）。`利用日: { type: date, format: "yyyy年M月d日" }` ならファイルは `2026-10-01`、見るときは `2026年10月1日`。検証・CLI・書き出しは値のままです。
- フォームは型の名前でなくヒントで動くので、`{ type: string, display: multiline }` と書けば `text` を通さなくても複数行の欄になります。
- **`$ref` との関係**: `$ref: 名前` も同じ意味で使えます（構造＝`object` などの定義は今までどおり `$ref` で）。ただしメタスキーマは、`$ref` を書いた項目の決まりのキー（`maxLength` など）を「型が string のときのみ」と警告するので、型は `type: 名前` で書いてください。型の名前は読み込み時にメタスキーマの `type` の選択肢へ足されます。
- **順序は型で決まる**: 検査の `lt` / `lte` / `gt` / `gte`、集計の `min` / `max`、ビューの行の並びは、値を読んだ項目の型で比べ方が決まります（`int` は数・`date` は日付。[checks.md](checks.md)「値の条件」・[views.md](views.md)「行の並び」）。読み方は数で読みます（`¥10,000` のような文字の金額は読みません。金額は `int` の型に `format` を付けて表示だけ整えます）。
- **ID の型をエンティティ型の判別に**: `画面ID: { type: id, pattern: "^SCR-\\d{3}$" }` のような型は、スキーマの `entity` の `idType: 画面ID` にも書けます（`idPattern` の glob の代わりに、型の形式で判別。[checks.md](checks.md)「エンティティ型宣言」）。
- **スキーマ設計画面**: 「定義を追加」で「型（スカラー）」を選ぶとスカラーの定義になり、項目の「型」の選択肢に基底の種類と型の名前が並びます。型の名前を選ぶと、その基底の種類で書ける決まりの入力欄が出ます。文字で打つ型（string / 数 / 日付など / id / ref）には「入力欄の案内」「サンプル値」、string には「複数行で編集」（`display: multiline`）と「行数」の欄があり、エンティティ節には「ID の型」の選択肢（id を狭めた型）が出ます。
- **リファクタリング**: `extract-definition` はスカラーの項目を型として抽出し、使う側を `type: 名前` にします（構造は `$ref`）。`rename-definition` は `type: 名前` の言及も書き換えます。`convert-type` の string ↔ text は型の名前の書き換えだけで、文書は変わりません。
- **これから**: オブジェクト型（`object` の定義に ID や表示や検査を足すこと）は [docs/ROADMAP.md](../ROADMAP.md) のテーマで進めます。

## スキーマ・リファクタリング（既存文書を追随させるスキーマ変更）

スキーマの `key` や構造を直接書き換えると、既存文書のキーが「スキーマ外項目」として残留します。スキーマ・リファクタリングは、スキーマ変更と既存文書・ビューの追随を**1つの計画**（プレビュー→適用。オール・オア・ナッシング）にまとめます。GUI からの適用は1つのトランザクションとして記録され、**Ctrl+Z（Undo）1回で全変更が戻ります**。

スキーマ設計画面からは次の操作が使えます（フィールド行のボタン。未保存の変更があるときは保存を促します）。

- **一括改名…**: スキーマ定義のキー（`$ref` で共有された定義のフィールドなら定義側＝全使用スキーマの文書が対象。ルート直下のフィールドが `phases` の申告キーなら `phases.key` も追随）＋全該当文書のデータキー（値・キー順・コメントを保った改名。`cases` 由来のキーは、その case が有効な文書だけが対象）＋ビューのパス（通常列・計算列・文書ビューなど、解決が一意に定まる場合のみ。多義な列は書き換えず警告）
- **一括削除…**: スキーマ定義と全該当文書のデータキーを削除。**値が入っている件数**をプレビューで明示します（ID型・フェーズ申告キーは拒否）
- **文書をスキーマ順へ整列…**（ルート定義の右上）: 全該当文書のキー順をスキーマのフィールド順へ正規化します（値・コメント保持。`_document` は先頭・スキーマ外キーは末尾に相対順で残る。スキーマは変更しません）

新CLI `workspace refactor`（[cli.md](cli.md)）にはさらに次があります。フィールドパスはドット区切り（例 `details.priority`。配列・map は暗黙に降ります）。フィールドの改名（`rename-field`）は、GUI と同じく、ビュー定義も追随します（書き換えられない言及は、警告に挙がります）。

- `move-field <schema-id> <field-path> <dest-path>`: フィールドの親子移動（`dest-path` は object のパス・ルートは `.`）。移動元と移動先が**同じ要素コンテキスト**にある場合のみ（配列・map の境界をまたぐ移動は1対Nの対応になるため拒否）。文書側は中間 object を生成し、空になった親 object は削除します（`--keep-empty` で残す）
- `rename-enum-value <schema-id> <field-path> <old> <new>`: enum 値の改名。`values`・`cases` のキー・`default`・（フェーズ申告フィールドなら）`phases.order` と `requiredFrom` / `activeFrom`・全該当文書の現在値が追随します
- `require-field <schema-id> <field-path> --default <値> [--from <フェーズ>]`: 必須化と同時に、値が無い（キー欠落・空）文書へ既定値を一括充填します（`--from` 指定時はそのフェーズに達している文書だけ）
- `convert-type <schema-id> <field-path> <型> [--values a,b,c]`: 決定的な型変換の一括版。スカラー→`array`（1要素に包む）・array→要素の型（1要素は引き継ぎ・空は削除・複数要素は拒否）・string→`enum`（逸脱値があれば一覧つき拒否）／`int`／`number`（解析検証つき）・enum／数値等→`string`・string↔`text`（組込みの型の名前の書き換えだけ。文書は変わらない）
- `rename-definition <module-id> <name> <new>` / `extract-definition <schema-id> <field-path> <name>` / `inline-definition <schema-id> <field-path>`: 名前付き定義の改名（`$ref` を横断書換）・インライン構造の定義化・定義参照のインライン化（参照が残らない定義は削除）。文書は変わりません
- `rename-relation <old> <new>` / `rename-schema-id <old> <new>`: 関係名（レジストリ・スキーマの `relation:`・ビュー）とスキーマID（修飾 `$ref`・`schemaId` 系の参照）の横断改名

共通の安全規則: 改名・移動先のキーが既存文書に存在する等の衝突は**適用せずに中止**します（対象を提示）。アクティビティの指示文など自動書換できない参照は、言及箇所を**警告として列挙**するので適用後に確認してください。派生スキーマ（`derive`）のフィールドは対象外です（導出元文書を修正してください）。適用は保存を伴うため、対象に未保存の編集があるときは先に保存してください（未保存のまま適用しようとすると拒否されます）。

## カバレッジルール（詳細文書の必須化）

スキーマのルート直下に `coverage` を書くと、「指定スキーマの文書にも同じIDが出現する＝詳細文書があること」（出現ベース `detailIn`）を検証できます。一覧と詳細を同一ID（1:1の詳細化。[ids.md](ids.md)）で運用するとき、詳細未作成のIDを機械検出できます。

```yaml
id: screen-list
match: "screens/index.yaml"
coverage:
  - idPattern: "SCR-*"            # 対象ID（省略時はこのスキーマの全ID）
    detailIn:
      schemaIds: [screen-detail]  # 出現先（詳細文書）のスキーマ（必須）
      minCount: 1                 # 必要な出現数（既定 1）
    level: warning                # warning（既定）/ error
    activeFrom: detail-design     # フェーズ機構（後述）: このフェーズに達するまで判定を保留（省略可）
    description: 全画面は画面定義（詳細文書）を持つこと
```

**カバレッジルールは組込みの定義体です**（後述「検査の定義と語彙の定義」）。`coverage` という書き方は、検証の前に基本の検査（その ID が書かれた場所の数の比較）へ展開されて、`constraints` と同じ規則で評価されます。不足の指摘・充足状況の一覧・「詳細文書を作成」のメニュー・詳細文書の側への案内は、すべてこの定義の宣言（`message` / `status` / `responses` / `fixSite`）から作られており、展開結果は CLI `checks` で確認できます。

不足があると、IDを定義している文書（その ID がこのスキーマの文書に書かれている場所）の「検証」パネルに指摘が表示され、同じ ID が書かれた他の文書には案内（情報）が出ます。詳細スキーマの文書には「このスキーマを出現先とするカバレッジで不足している ID」の案内が出ます（フェーズ未到達で指摘が出ていない ID は載りません）。画面上の判定は未保存の編集を含む「見たまま」で行われ、詳細文書を書いた時点で（保存前でも）反映されます（CLI・エージェントなどのヘッドレス経路はディスク基準）。充足状況はID横ぐしビュー・ビューグリッドの状態列でも確認できます。

**被参照数の要求**（「条件を満たす参照を一定数以上受けること」。旧 `requiredBy`）は、正準の綴りへ一本化しました。
1ホップ・無条件なら関係レジストリの `cardinality`（`inMin` 等。§関係レジストリ）、参照元スキーマの限定など条件付き・多段なら `constraints`（`expect: exists` / `count`）で宣言してください。`requiredBy` を書いたスキーマは移行先を案内する定義エラーになります（使い分けの一覧は [docs/DESIGN-CHECK-CATALOG.md](../DESIGN-CHECK-CATALOG.md)）。

## 新しい文書の置き場所とひな型（`filePattern` / `scaffold`）

詳細文書を「詳細文書を作成」（検証パネルの対応）やビューの「作成」で作るとき、**ファイル名を元の要素の値から決める**ことができます。スキーマ直下に `filePattern` を書きます。書かなければ今までどおり `match` から `{id}` だけのパターンを導きます（ディレクトリにワイルドカードの無い単純な glob だけ。`**` を含む `match` のスキーマも、`filePattern` を書けば作成先を決められます）。

```yaml
id: screen-design
match: 40_detail-design/screens/*.{yaml,yml}
filePattern: "40_detail-design/screens/{id}_{name}.yaml"   # 短い書き方
```

- **短い書き方**は `{パス}` を含む文字列です。穴は**元の要素**（その ID の定義出現＝一覧側の要素）の値で埋まります（`{owner.name}` のような入れ子のパスも書けます）。予約の名前は `{id}`（ID）と `{title}`（表示の解決結果。`role: title` ＞ 予約語）の2つで、要素の項目より先に埋まります。
- **完全な書き方**は、検査の `check` に書ける**値の式**（後述「検査の定義と語彙の定義」）そのものです。`of` を省略すると元の要素を読みます。`format` には `with:`（穴の名前 → 式）を添えて、計算した値を穴に差し込めます。

```yaml
match: 40_detail-design/screens/**/*.{yaml,yml}
filePattern:                                          # ドメインごとのディレクトリへ振り分ける
  format: "40_detail-design/screens/{dir}/{id}.yaml"
  with:
    dir: {map: {path: domain}, using: {予約: reservation, 会議室: room}}
```

- **穴が埋まらなければ、作成はエラー**になります（どの穴が埋まらないかを文面に出します。値が未記入のときに、黙って別の名前にはしません）。後退させたいときは `{first: [{path: name}, {path: id}]}` のように書きます。
- **穴に入った値は、ファイル名に使える文字に直されます**（`/` `\` `:` `*` `?` `"` `<` `>` `|` と制御文字は `-` に、前後の空白は落とす）。ひな型に直接書いた `/` はディレクトリの区切りのままです。予約の `{id}` だけは直さず、使えない文字を含む ID は拒否します（今までどおり）。
- **読み込み時の点検**: 代表値で解決した作成先がそのスキーマの `match` に合わなければ、スキーマの読み込みエラーになります（作った文書にそのスキーマが適用されないため）。`{id}` を含まないときは注意が出ます（同じ名前になる要素があると、作成は「既に同名のファイルが存在します」で止まります）。項目の改名（スキーマ・リファクタリング）では、`filePattern` / `scaffold` に旧名の言及があれば警告します（自動では書き換えません）。
- **中身**: `scaffold` を書くと、必須の項目の初期値の上に、元の要素から組み立てた内容を重ねられます。
書き方は変換のひな型（CLI `transform` の変換定義の `build` と同じ。`$copy` / `$format`（`$with` で計算した値を穴へ）/ `$map` / `$first` / `$each` / `$when`、
値をそろえる `$date`（年月日を `yyyy-MM-dd` に。`$as` で形）/ `$number`（桁区切りや `¥` を許して数に）/ `$trim`（前後の空白を落とす）。いまの場所＝元の要素。[cli.md](cli.md)・skill の取り込みの `build` は [ai.md](ai.md)）です。ルート直下の ID の項目には、今までどおり ID が入ります。

```yaml
scaffold:
  name: {$copy: name}                           # 一覧側の name を写す
  summary: {$copy: summary}
  source: {$format: "{id}（画面一覧から作成）"}
```

- **仕組み（項目の印）**: `filePattern` は、メタスキーマで項目に `expression: true` の印が付いているだけの、ふつうの項目です。利用者のスキーマの項目（`type: string` / `text`）にも同じ印を書けます——その項目の値は値の式でもよくなり（短い書き方でも完全な書き方でも）、式として読めない値は検証で「「項目名」（場所）の式が読めません」と指摘されます。式を何に使うか（いまの要素は何か）は、その値を使う側が決めます。
- **どこに効くか**: 検証パネルの「詳細文書を作成: ID（スキーマ）」（不足の指摘の文面 `{create.places}` にも、評価した実際のパスが出ます）・ビューの詳細列の「作成」（`detail.filePattern` も同じ式。
行の要素の値で埋まります）・CLI `view` の `detailCreatePath`・エージェントの `read_view` の「詳細: 未作成（filePattern: … → 作り先）」。ビューの `newRow.filePattern`（ファイルモード）は行がまだ無いので、書ける穴は `{id}` だけです（他の穴は定義エラー）。
式に書けるのは値の計算だけで、条件分岐・今日の日付・連番は書けません（連番は ID の採番で決めます）。

## フェーズ機構（工程に応じた検証の強弱）

要件定義→論理設計→物理設計のように、1つの論理要素（例: データモデルの1エンティティ）を複数の工程にまたがって記述していく運用では、後工程の項目を要件定義の時点で必須にすると検証エラーが大量発生してしまいます。`phases` を宣言すると、「この項目はこのフェーズ以降で必須」という**累積的な**必須化ができます。

```yaml
id: entity
match: "models/**/*.yaml"
phases:
  key: phase              # 現在フェーズを申告するルート直下のキー（省略時 phase）
  order: [requirements, logical-design, physical-design]
root:
  type: object
  fields:
    - key: phase
      type: enum
      values: [requirements, logical-design, physical-design]
      default: requirements
    - key: name
      type: string
      required: true       # 無条件必須（全フェーズで必須）
    - key: columns
      type: array
      requiredFrom: logical-design   # logical-design 以降で必須。requirements 段階の未記入はエラーにしない
      item: { type: object, fields: [ { key: name, type: string, required: true } ] }
    - key: physicalName
      type: string
      requiredFrom: physical-design  # 物理設計以降で必須
coverage:
  - detailIn: { schemaIds: [table-design] }
    activeFrom: physical-design      # 物理設計に達するまではカバレッジ判定を保留する
    level: error
```

挙動の要点:

- 文書はルート直下の申告キー（既定 `phase`）で現在フェーズを記載します。未申告・未知の値は**先頭フェーズ**（最も緩い判定）として扱われ、未知の値には警告が出ます。
- `requiredFrom` は「そのフェーズ**以降**で必須」（累積的）。現在フェーズより先の工程に属する項目の未記入はエラーにならず、代わりに「検証」パネルにフェーズ別の記入進捗（例: `logical-design: 1/2 記入`）として表示されます。フェーズが進むと、過去に必須化した項目は引き続き必須のままです。
- `required: true` と `requiredFrom` は併用できません（メタスキーマ検証でエラー）。
- カバレッジルールの `activeFrom` は「IDを**定義している文書**の現在フェーズがそのフェーズに達してから判定する」という保留を表します。未到達の間は「フェーズ未到達」として区別表示され、検証エラー・警告にはなりません（ID横ぐしビュー・ビューグリッドの状態列も同様に区別表示します）。
- 文書の物理的な分割単位（1文書で全工程を書くか、工程ごとにファイルを分けて段階的詳細化するか）はどちらでも成立します。工程ごとに分けた運用では、後工程のスキーマだけ `requiredFrom` を使わず素の `required` で書いても構いません。

CLI `validate` の出力にも、`phases` を宣言したスキーマの文書には `phaseProgress`（フェーズごとの記入済み／総数）が付与されます。


## メタスキーマ（`_schemas` 文書の編集・検証・AI生成）

`_schemas/` 配下のスキーマ文書自体にも、組込みのメタスキーマ（ID `builtin-schema`）が適用されます。

- スキーマ文書を開くと「スキーマ設計」タブ（主要UX）に加えて「フォーム」タブが表示され、メタスキーマによる構造化フォームでも編集できます。文書ヘッダーの「AIで作成」でスキーマのドラフトをAI生成することもできます。
- 検証パネル・CLI `validate` はスキーマ文書をメタスキーマで検査します（例: `type` の値が選択肢外、`fields` は `type: object` のときのみ有効、`scope` のキー名間違い）。定義として読み込めない致命的な問題は従来どおりスキーマ読込エラーとして表示されます。
- 一般スキーマの `match` は `_schemas/` には適用されません（catch-all パターンの誤適用を防止）。メタスキーマを差し替えたい場合は、同じID（`builtin-schema`）のスキーマを `_schemas` に置くと上書きできます。
- エージェントの `_schemas` への書込禁止は従来どおりです（メタスキーマは編集・検証のためのもので、書込経路は増えません）。

## スキーマ推測（既存文書からのドラフト作成）

スキーマを書く前にYAML文書が既にある場合、既存文書からスキーマのドラフトを推測できます。

- **入口**: ファイルツリーで文書を選択して「スキーマ推測」ボタン（魔法の杖アイコン）、設計スペース「スキーマ」の「既存文書から推測…」、または CLI `infer-schema`。
- **推測の内容**: glob に一致する複数文書をマージし、①値の実例からの型推測（`int` / `number` / `boolean` / `date` / 長文は `text`）②参照の認識——命名規約（`ref` / `*_ref` / `refs` / `depends_on` 等。
[ids.md](ids.md) の参照認識と同じ規約）に加え、全値がサンプル中の論理IDと一致するフィールドも `ref` と推測 ③小さく反復する値集合の `enum` 候補化（3件以上＋同じ値の反復が条件。IDらしい値＝既存IDと一致・`SCR-001` のようなID形式は enum にしません）④全文書に出現するキーの `required` 候補化 ⑤`id` キーの `type: id` 化 — を行います。
構造が文書間で揺れるキーは `any` になります。
- **仕上げ**: 生成された `_schemas/<ID>.yaml` はドラフトです（先頭にその旨のコメントが付きます）。メタスキーマ検証・スキーマ設計画面で型や必須を確認・調整し、サンプルYAML生成（プレビュー）を逆方向の検算に使ってください。既存のスキーマファイルは上書きしません。

## 派生スキーマ（`derive`: 文書から検証スキーマを導出する）

テーブル定義書のような「実質スキーマの文書」がある場合、その文書を二重にスキーマへ書き写す代わりに、`derive:` を宣言したスキーマ（テンプレート）が導出元文書から検証スキーマを**ロード時にコンパイル**します。

```yaml
# _schemas/record-template.yaml
id: record-template
name: レコード検証
derive:
  from: "tables/**/*.{yaml,yml}"     # 導出元文書（テーブル定義書）の glob。予約ディレクトリは不可
  # each: entities                   # 1文書内の配列要素ごとに導出する場合は配列パスを指定（省略時は文書ルート）
  id: "record-{id}"                  # 導出スキーマのID。{パス} は導出元要素の値で置換される
  name: "{title} レコード"
  match: "records/{id}/**/*.{yaml,yml}"  # 導出スキーマの適用先（`,` を含む {a,b} は glob 構文として残る）
  fields:                            # 写像の語彙（path / map / default）は skill の列マッピング（[ai.md](ai.md)）と同じ
    source: columns                  # フィールド一覧にする配列のパス
    key: name                        # 各要素のこの値 → フィールドのキー
    label: label                     # 任意: 表示ラベル
    description: note                # 任意: 説明
    required: required               # 任意: 必須（真偽値）
    type:                            # 任意: 型の写像（省略時はすべて string）
      path: dataType
      map: { varchar: string, bool: boolean, number: int }
      default: string
root:                                # 任意: 全導出スキーマ共通のフィールド（先頭に合成される）
  type: object
  fields:
    - { key: id, type: id, required: true }
```

- 上の例では `tables/tbl-task.yaml`（`id: TBL-TASK`、`columns` 配列）から、`records/TBL-TASK/**` に適用されるスキーマ `record-TBL-TASK` が導出されます。テーブル定義書に列を足して保存すると、レコード文書は**すぐに新しいルールで再検証**されます。
- `derive` を宣言したスキーマ自身はどの文書にもマッチしません（`match` は書けません）。導出スキーマはファイルとしては存在しない読み取り専用の成果物で、`coverage` / `phases` / `definitions` はテンプレートから引き継がれます。
- 導出できない要素（プレースホルダ値の欠落・フィールドキーの欠落・IDの重複・未知の型）は、理由付きでスキーマ読込エラーに報告されます（他の導出は継続します）。
- 導出元文書は通常の文書なので、エージェントの変更セットが（承認を経て）検証ルールを変えることがあります。このため**導出元文書への自動適用（`approval: auto`）は常に承認待ちへ降格**されます（[ai.md](ai.md)）。

