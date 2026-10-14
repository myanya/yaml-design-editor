# 操作（型の操作とアクション）

スキーマに「操作」を定義すると、決まった手順の変更に名前が付き、人も AI も同じ名前で呼べるようになります。
操作は 2 種類あります。

- **型の操作**（`methods`）: object の定義に書き、その型の要素 1 つを受け手にします。書き換えられるのは受け手の中だけ（保有する子を含む）で、他の要素や文書には触れません。
- **アクション**（`actions`）: スキーマ文書の `actions:` に書き、複数の要素や文書にまたがる操作です。受け取る値・前提・手の並びを持ち、型の操作を呼べます。

どちらも結果は**変更セット**になり、適用はこれまでと同じ経路を通ります（競合の検出・保存時に自動で入る値・適用後の姿の検証・Undo 1 回）。原本に直接書く別の経路は作りません。
いま使える入口は CLI です（[cli.md](cli.md)）。画面の操作メニューと AI エージェントからの呼び出しは、次の段階で入ります。

## 型の操作（`methods`）

object の定義（`definitions` の名前付きの型でも、配列の要素などその場の object でも）に `methods:` を書きます。

```yaml
# _schemas/business-rule.yaml（抜粋）
      item:
        type: object
        fields:
          - { key: id, type: id, required: true }
          - { key: status, type: enum, values: [draft, approved, deprecated] }
          - { key: deprecated_reason, type: string }
        methods:
          deprecate:
            label: 廃止にする
            params:
              - { key: reason, label: 廃止の理由, type: string, required: true }
            do:
              - set: { path: status, value: deprecated }
              - set: { path: deprecated_reason, value: $reason }
```

- `params` は受け取る値の宣言で、項目の定義と同じ書き方です（`type`・`required`・`values`・`default` など。[schema.md](schema.md)）。名前は英字で始まる英数字にします（`$名前` で参照するため）。
- `do` は手の並びです。書けるのは `set`（値を入れる）・`insert`（配列へ足す）・`delete`（消す）・`each: {path, do}`（自分の配列の要素ごとに）。`path` は受け手からの相対で、受け手の外（親・兄弟・他の文書）には触れません。
- 値は変換のひな型で書きます（[views.md](views.md)「変換」）。文字列 `$名前` は受け取った値そのもの、`{$format: "廃止: {reason}"}` は受け取った値で穴を埋めた文字、`{$copy: name, $of: self}` は受け手の値です。
- **受け取った値の一式が「いまの場所」**です。`$copy: reason` や条件の `path: reason` は受け取った値を読みます。受け手の要素は `$of: self`（条件なら `of: self`）で指します。
- 型を使う側（`type: 名前` で項目を足した型）は操作を引き継ぎます。同じ名前を書けば置き換え、`create` だけは定義側の上に重ねます。

### 予約の名前 `create`

`create` は新しい要素の中身を決める操作で、`do` ではなく `build`（ひな型）を書きます。省けば「必須の項目の初期値 ＋ 受け取った値のうち項目のキーに合うもの」です。

```yaml
        methods:
          create:
            params:
              - { key: id, type: id, required: true }
              - { key: name, type: string, required: true }
              - { key: summary, type: string }
            build: { id: $id, name: $name, derived_from: [], summary: { $copy: summary } }
```

`build` を書いたときは、必須の初期値の上にひな型の出力を重ねます（受け取った値は自動では写りません）。値が得られない項目（省いた `summary`）はひな型から落ちます。
`create` は直接は呼べません。アクションの `insert` と `create`、配列の追加の呼び手が使います。

### 配列の追加の決まり（配列の `methods.insert`）

配列の項目には `insert` だけを書けます。要素を足すときの採番と、同じ明細の見分けです。skill の取り込み（`target.id` / `target.key`）と同じ綴りです。

```yaml
    - key: screens
      type: array
      methods:
        insert:
          id: { path: id, prefix: "SCR-", digits: 3 }   # id が空なら SCR-001, SCR-002 … と採番
          key: [name]                                   # 同じ name の行が既にあれば足さない
      item: { ... }
```

- `id.prefix` はひな型です。新しい要素で穴を埋められます（例 `{$format: "TX-{date}-"}`）。連番は、ワークスペースの既存の ID と配列の中と同じ操作で付けた ID の最大の次です。
- `key` が同じ要素が既にあれば足しません（計画の要約に「同じ明細が既にあります」と出ます）。

## アクション（`actions`）

スキーマ文書（文書のスキーマか `kind: library`）の `actions:` に書きます。

```yaml
# _schemas/screen-list.yaml（抜粋）
actions:
  - id: add-screen
    label: 画面を追加する
    params:
      - { key: id, label: 画面ID, type: id, required: true }
      - { key: name, label: 画面名, type: string, required: true }
      - { key: derived_from, label: 導出元, type: array, required: true, item: { type: ref } }
      - { key: from, label: 遷移元画面, type: ref, idPattern: "SCR-*" }
      - { key: trigger, label: 遷移の契機, type: string }
    require:
      - match: [ { node: { id: $id } } ]
        expect: absent
        message: "$id は既にあります"
    do:
      - insert: { in: { schemaId: screen-list }, path: screens, params: { id: $id, name: $name, derived_from: $derived_from } }
      - create: { schema: screen-def, params: { id: $id } }
      - when: { path: from, exists: true }
        insert: { in: { schemaId: screen-transition }, path: transitions, params: { from: $from, to: $id, trigger: $trigger } }
```

| キー | 意味 |
|---|---|
| `id` / `label` / `description` | 操作の名前と説明 |
| `target` | 受け手＝`{entity: 型, bind: 名前}`（エンティティの要素、`bind` 省略時は `self`）か `{schema: スキーマID}`（その文書 1 つ）で、省けばワークスペースの操作 |
| `params` | 受け取る値（項目の定義と同じ書き方） |
| `require` | 前提（検査と同じ書き方＝`match` ＋ `expect` / `count` / `check` ＋ `message`。[checks.md](checks.md)） |
| `do` | 手の並び |

受け手のキーは `target` です（`on` は YAML が真偽値として読むため使えません）。

### 手の書き方

| 手 | 書き方 | 意味 |
|---|---|---|
| `set` | `{of, in, path, value}` | 値を入れる（無ければ作る） |
| `insert` | `{of, in, path, params}` または `{..., value}` | 配列の末尾へ足す（`params` は要素の型の `create` へ渡す値、`value` は要素そのもののひな型） |
| `delete` | `{of, in, path}` | 要素や値を消す |
| `create` | `{schema, params}` | そのスキーマの文書を作る（中身はルートの型の `create`、置き場所は `filePattern` か単純な `match`） |
| `call` | `{of, method, params}` | 要素の型の操作を呼ぶ |
| `each` | `{from, match, where, do}` | 起点を固定した照合の解ごとに、中の手を当てる（`from` 省略時は受け手） |
| `each` | `{of, in, path, do}` | 配列の要素ごとに、その要素を受け手として中の手を当てる |

- 手の対象は `of`（束縛名。受け手は `target` の `bind`、照合で付けた名前は `each` の中だけ）→ `in`（`{schemaId, fileGlob}` に合う文書がちょうど 1 つ）→ 受け手、の順で決まります。合う文書が複数あれば止まります（`fileGlob` で絞るか、`of` で要素を指します）。
- 各手に `when:`（条件。クエリの `where` と同じ書き方）を添えると、合うときだけ当てます。条件の `path` は受け取った値を読み、`of: 束縛名` でその要素を読みます。
- 手は上から順に、作業用の写しの上で当たります。後の手は前の手の結果を読めます。配列がまだ無ければ作ります。

### 前提（`require`）

- 受け手があるアクションでは、`match` の先頭の node が受け手に当たり、検査と同じ 4 つの結果で決めます。**違反と保留（相手の見つからない参照などで決められない）は実行しません。**理由が文面に出ます。
- 受け手の無いアクションの前提に書けるのは `expect` と `count` だけです（照合の全体の件数で判定）。
- 文面の `{id}` は起点（受け手）の ID、`{束縛名.id}` `{束縛名.title}` は見つかった解、`$名前` は受け取った値です。前提で使う受け取った値は、必ず渡します（渡さないと止まります）。

### 受け手を持つアクションの例

```yaml
# _schemas/business-rule.yaml（抜粋）
actions:
  - id: deprecate-rule
    label: ビジネスルールを廃止する
    target: { entity: business-rule, bind: br }
    params:
      - { key: reason, label: 廃止の理由, type: string, required: true }
    require:
      - match:
          - node: { entity: business-rule }
          - edge: { dir: in, relation: based-on }
          - node: { bind: x }
        expect: absent
        message: "{id} はまだ {x.id} から根拠として参照されています。先に参照を外してください"
    do:
      - call: { of: br, method: deprecate, params: { reason: $reason } }
```

参照元をまとめて変えるときは `each` で解を集め、相手の型の操作を呼びます。相手の型ごとに同じ名前の操作の中身が違ってよいのが、型に操作を置く利点です。

```yaml
    do:
      - each:
          from: br
          match: [ { node: { entity: business-rule } }, { edge: { dir: in, relation: based-on } }, { node: { entity: usecase, bind: uc } } ]
          do:
            - call: { of: uc, method: drop-basis, params: { rule: { $copy: id, $of: br } } }
      - call: { of: br, method: deprecate, params: { reason: $reason } }
```

## CLI で使う

```bash
./gradlew runCli -q --args="action labs/sysdev-template"                                   # 使える操作の一覧
./gradlew runCli -q --args="action labs/sysdev-template add-screen --param id=SCR-ROOM-DETAIL --param name=会議室詳細画面 --param derived_from=[UA-003] --param from=SCR-ROOM-LIST --param trigger=会議室名クリック"
./gradlew runCli -q --args="action labs/sysdev-template deprecate-rule --on BR-004 --param reason=統合"
./gradlew runCli -q --args="action labs/sysdev-template business-rule.deprecate --on BR-004 --param reason=統合"
```

- 引数なしは一覧です（アクションは `id`、型の操作は `型名.操作名`。`kind: create` は直接呼べません）。
- 既定は**計画だけ**です。変更セット（`changes`）と、適用後の姿の検証の結果（`results`）が出て、原本は変わりません。`--apply` で書き込みます。**検証エラーが残るなら書きません**（`--allow-errors` で書きます）。
- `--on` は受け手（エンティティの ID か、文書の相対パス）。受け手が `schema` で文書が 1 つしか無いときは省けます。
- `--param 名前=値` の値は YAML として読みます（配列は `--param "derived_from=[UA-003]"`）。
- 前提に反する・受け取った値に誤りがある・受け手が見つからない、のときは理由を出して止まります（終了コード 1）。

上の `add-screen` を計画すると、画面一覧の行・画面定義の文書・遷移の行の 3 つの変更ができ、適用後の姿の検証が「新しい画面から出る遷移がなく、終端としても宣言されていません」をエラーにします。戻りの遷移を足すまで `--apply` は書きません。検査が守る不変条件を、操作の前に確かめる使い方です。

## いまの制限

- 入口は CLI だけです。画面の操作メニュー（エンティティページ・ビューの行・フォーム）と、AI エージェントのツールは次の段階です。
- 型の操作から他の要素・文書は変えられません（設計どおり）。必要ならアクションに書きます。
- `each` の照合は起点を固定した形だけです（受け手か束縛から辿る）。
- 新しく作った要素や文書を、同じ操作の後の手で束縛として指すことはできません（照合は操作を始める前の索引で行います）。受け取った値（ID など）で指してください。
- 人の実行は履歴（Undo）に残り、実行記録（`_runs`）には残しません。
