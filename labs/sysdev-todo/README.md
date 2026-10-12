# TODO Webアプリの設計体系

ドメイン一覧とドメイン定義書を起点として、TODO Webアプリの各種定義・基本設計・詳細設計を導出するワークスペースです。
YAML Design Editor で `labs/sysdev-todo` を開いて使用します。エージェントの作業規約は [AGENTS.md](AGENTS.md) にあります。

## アプリの前提

複数の利用者が、メールアドレスとパスワードでログインして自身のTODOを管理します。
TODO・分類・タグは利用者ごとに管理し、他の利用者と共有しません。
分類・タグ管理をTODO管理から独立したドメインとして扱います。

業務ルール・提供操作・未決事項の原本は各ドメイン定義書です。
認証状態はサーバー側のセッションで管理し、無操作が3時間続くとタイムアウトします。
言語・フレームワーク・DB製品・配備先は未決定です。★
設計状態は `draft` とし、未決定・後続設計で具体化する箇所には文末に「★」を付けます。

## 一覧と個別設計書

各種設計文書は一覧と個別設計書に分け、一覧の各項目と対応する個別設計書に同じIDを付けます。

| 文書 | 記述する情報 | 役割 |
|---|---|---|
| 一覧 | ID・タイトル・概要・種別などの分類情報・設計状態 | 設計対象を一覧で確認し、個別設計書を開くための入口とする |
| 個別設計書 | 一覧と同じID・設計の詳細・未決事項 | 当該要素の具体的な設計情報を記述する |

タイトルのYAMLキーは `name`、概要は `summary`、種別は `kind` です。
タイトル・概要・種別・設計状態を個別設計書に複製しません。
一覧の要素を定義出現、個別設計書を同一IDの詳細化出現として扱います。

ドメインでは `21_domains/domain-list.yaml` と `21_domains/<ID>.yaml` に分けています。
一覧の各項目に定義書があることと、各定義書が一覧に登録されていることを検査し、片方にしかないIDには警告を出します。
ドメインと業務データ型の文書を作成しています。それ以外のスキーマのひな型は、各文書を作成する際に、一覧と個別設計書の分担に合わせて整えます。

## ドメイン文書

[ドメイン一覧](21_domains/domain-list.yaml) がID・タイトル・概要・種別の原本です。
`kind` にはDDDのサブドメイン分類を使用します。

| 値 | 分類 | 判断基準 |
|---|---|---|
| `core` | コアサブドメイン | 事業の差別化・競争優位の中心となる領域 |
| `supporting` | 支援サブドメイン | コアを支える、当該業務に固有の補助領域 |
| `generic` | 汎用サブドメイン | 広く共通する課題で、既存の製品・サービスなどを活用できる領域 |

分類の考え方は [SAPのDDD解説](https://github.com/SAP/curated-resources-for-domain-driven-design/blob/main/blog/0002-core-concepts.md#domains-and-subdomains) を参照してください。
本実験ではTODO管理の体験を差別化の中心に据える前提で分類します。具体的な競争優位は未定のため、コアの位置付けは初期仮説です。
各ドメインの分類と理由は一覧の `kind` とコメントに記載し、事業上の位置付けが変わった際に見直します。

| 個別定義書 | 記述する内容 |
|---|---|
| [DOM-IDENTITY.yaml](21_domains/DOM-IDENTITY.yaml) | 利用者・認証状態・ログイン履歴・本人確認要求と、登録・認証・情報変更・再設定・退会 |
| [DOM-TODO.yaml](21_domains/DOM-TODO.yaml) | TODOとその所有者、一覧・詳細の取得・登録・編集・完了状態の変更・削除 |
| [DOM-CLASSIFICATION.yaml](21_domains/DOM-CLASSIFICATION.yaml) | 分類とタグ、一覧・登録・名称変更・削除 |

ドメインはサブドメイン相当のまとまりです。個別のドメイン定義書は次の情報を持ち、後続設計の大本となります。

- `responsibility`・`out_of_scope`：責務、対象範囲、他ドメインとの境界。
- `dependencies`：業務上利用するドメイン。コードの依存方向を直接指定するものではありません。
- `rule_groups`：ドメイン全体の業務ルールを、主題ごとの `name` と `rules` に分けて記述。
- `entities`：管理対象の情報。`ENT-*` のID・名称・意味、業務データ型を指定した情報項目、制約。
- `operations`：提供操作。`OP-*` のID・名称・目的、操作主体、対象エンティティ、入力・結果、事前条件、事後条件、業務エラー。
- `notes`：未決事項と補足。

`entities` と `operations` はドメイン内の構成要素であり、ドメイン一覧のメタデータとは別に定義します。
提供操作は業務上の能力を表し、ボタン・HTTPリクエスト・サービスメソッドと必ずしも一対一にはしません。
設計要素のIDと、実行時の利用者ID・TODO IDなどは別の識別子です。

## 操作と条件の記述

操作対象は `operations[].entities` に記述します。エンティティごとに `C`（作成）・`R`（参照）・`U`（更新）・`D`（削除）をすべて明記します。
各CRUDは `enabled` で有無を示し、実施する場合は `target` に対象、必要に応じて `condition` に実施条件、`notes` に特記事項を記述します。
実施しないCRUDは `enabled: false` のみとします。例えばログアウトでは、検索と無効化で異なる条件を指定できます。

```yaml
entity: ENT-AUTH-SESSION
C: { enabled: false }
R:
  enabled: true
  target: 提示された認証情報に対応するセッション
  condition: { kind: predicate, subject: 現在の認証情報, operator: not_equals, expected: 未指定 }
U:
  enabled: true
  target: 提示された認証情報に対応するセッション
  condition: { kind: predicate, subject: 該当する認証状態の件数, operator: equals, expected: '1' }
  notes: 有効状態を無効にする。
D: { enabled: false }
```

`condition` の省略は追加の実施条件なしを意味します。通常のCRUDは事前条件が成立した場合に実施します。
事前条件の照合に必要な参照は、その判定過程で行います。受付時・失敗時にも行う履歴記録などは、その条件をCRUD側に明記します。
共通認証処理での参照・更新も対象に含めます。CRUDの並びは処理順序を表しません。
退会は `U` で利用状態を変更します。退会後の削除・匿名化は未決定です。★

事前条件 `preconditions`、CRUDの実施条件 `condition`、エンティティの利用可能条件 `availability` は、同じ再帰的な条件ツリーで記述します。

- `kind: predicate`：`subject`・`operator`・`expected` で1性質を記述する葉。
- `kind: and`：`conditions` 内の子条件をすべて満たす。
- `kind: or`：`conditions` 内の子条件を少なくとも1つ満たす。
- AND・ORの子には葉・AND・ORを任意に入れ子にできる。子条件は1件以上とし、空のグループは使わない。
- 追加の事前条件がない場合は `preconditions` 自体を省略する。
- 葉の `operator` は `equals`、`not_equals`、`less_than`、`greater_or_equal`、`satisfies`。

例えばTODOの登録では「認証済み AND（分類なし OR（分類が存在 AND 本人所有））」を次のように表します（他の事前条件は省略）。

```yaml
preconditions:
  kind: and
  conditions:
    - { kind: predicate, subject: 現在の認証状態, operator: satisfies, expected: ENT-AUTH-SESSIONの利用可能条件 }
    - kind: or
      conditions:
        - { kind: predicate, subject: 入力の分類, operator: equals, expected: 指定なし }
        - kind: and
          conditions:
            - { kind: predicate, subject: 指定された分類の件数, operator: equals, expected: '1' }
            - { kind: predicate, subject: 指定された分類の所有者ID, operator: equals, expected: 認証中の利用者ID }
```

値が存在しないなどで比較できない葉を真とはみなしません。ORの別の子が真ならORは成立し、ANDに偽の子があればANDは不成立です。
それ以外で未評価の子が残る場合は未評価とし、条件成立として処理を進めません。記述順は業務処理の順番を指定するものではありません。
現在のツール検証は条件ツリーの構造・必須項目・判定の種別を検査します。
`subject`・`expected` の実データへの対応付けと条件の実行は後続設計で具体化します。★

入力は、指定された業務データ型の値域・必須指定・項目固有の制約を満たすことを共通の前提とし、違反する入力は受け付けません。
これらを各操作の事前条件・業務エラーに列挙しません。事前条件には、所有者確認・参照先の存在・重複確認・パスワード照合などの業務上の判定を記述します。

業務ルールはドメインとエンティティの `rule_groups` で主題ごとにまとめます。記述の分担は次のとおりです。

| 記述先 | 内容 |
|---|---|
| 業務データ型・入力項目 | 値域・形式・必須・項目固有の入力制約 |
| エンティティの `rule_groups` | 一意性・初期値・不変条件・ライフサイクル |
| エンティティの `availability` | 状態に応じた共通の利用可否 |
| ドメインの `rule_groups` | 認証制限など、操作を横断する方針 |
| 操作の `preconditions` | 型や入力制約だけでは決まらない実行可否 |
| 操作のCRUD | 作成・参照・更新・削除の有無、対象、実施条件、特記事項 |
| 操作の `postconditions` | 全セッションの無効化など、操作固有の追加効果 |
| 操作の `errors` | 業務上の失敗と、その際の取扱い |

IDの一意性・登録時の初期値・入力の単純な転記・CRUDから自明な効果は事後条件へ繰り返しません。追加の保証がなければ `postconditions` を省略します。
例えばログアウトは対象を無効化し、無効な認証状態で操作できないことは `ENT-AUTH-SESSION.availability` と認証方針に集約します。
事後条件が原本となる操作固有の副作用は、ドメイン全体のルールへ再掲しません。

## 認証の方針

認証制限の単位はメールアドレスで、直近60秒の照合対象試行を最大10回に制限します。
成功・失敗に加え、同時要求で枠を超えないように処理中の試行も数えます。制限で拒否した試行は照合回数に含めません。
ログイン履歴は本人の利用者IDに関連付くものだけを一覧で返します。

セッションの有効期限は最終操作日時の3時間後です。期限と等しい時刻からタイムアウトとし、サーバー側で判定します。
自動ポーリングなどを操作の延長対象から除きます。対象操作とAPIの対応は後続設計で具体化します。★
パスワード再設定は登録済みメールアドレス、メールアドレス変更は変更先に送信した確認用リンクで確認します。
リンクの有効期間、履歴の保持期間、退会に伴うTODOなどのデータ処理は未決定です。★

セッションの期限判定と本人確認リンクの設計では、
[OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) と
[OWASP Forgot Password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html) を参考にしています。
具体的な業務ルールの原本は `DOM-IDENTITY` です。

## 業務データ型

業務データ型は、単独の値を表す値型と、複数の項目で構成するモデル型に分けます。
それぞれの一覧に型のID・名称・業務上の意味・種別・設計状態を記述し、同じディレクトリの `DT-*.yaml` に詳細を記述します。

| 配置先・一覧 | 対象 | 個別定義書の内容 |
|---|---|---|
| [22_valuetypes/value-type-list.yaml](22_valuetypes/value-type-list.yaml) | 値型：識別子・文字情報・日付・日時・区分 | 値の制約・取扱い・区分の選択肢 |
| [23_modeltypes/model-type-list.yaml](23_modeltypes/model-type-list.yaml) | モデル型：複合情報 | 制約・取扱い・構成項目と各項目の業務データ型 |

ドメイン定義書の `entities[].information`、`operations[].inputs` は、次の項目で記述します。
後続の論理エンティティ設計の属性にも同じ書き方を使います。

| 項目 | 記述する内容 |
|---|---|
| `name`・`description` | 項目の名称と、その場所での意味・用途 |
| `data_type` | 業務データ型一覧に定義した `DT-*` への参照 |
| `required` | 値の指定が必要かどうか |
| `multiple` | 複数値なら `true`、省略時は単一値 |
| `constraints` | その項目で追加する条件 |

`multiple: true` の場合、`data_type` は1件分の型を表します。
`required: true` は一覧自体の指定が必要という意味で、空の一覧は許可します。件数制限や重複禁止は追加条件で示します。
型の制約は値が指定された場合に適用し、項目が必須かどうかは型の使用箇所で決めます。

「複数値」の未指定は `multiple: false` と同じで、単独の値を扱うという意味です。実際の値が未入力であることは意味しません。
`required: false` は値を省略できるという指定です。`multiple: true` の空のリスト `[]` は、リストを指定したうえで要素が0件であることを示します。
TODOの保持情報・取得結果では、タグなしを必ず空のリストで表します。登録入力でタグを省略した場合も、保存時には空のリストとします。
編集入力でのタグの省略と空リストの対応は、入力側の設計で具体化します。★

単独の値は業務上の意味ごとに区別し、利用者ID・TODO ID・分類ID・タグIDをそれぞれ別の型にしています。
[TODO状態](22_valuetypes/DT-TODO-STATUS.yaml) と [TODO状態の検索条件](22_valuetypes/DT-TODO-STATUS-FILTER.yaml) も別の値型です。
[TODOの内容](23_modeltypes/DT-TODO-CONTENT.yaml) や [TODO情報](23_modeltypes/DT-TODO-INFO.yaml) などの既存モデル型は、構成項目から値型や他のモデル型を参照します。
`23_modeltypes` の今後の位置付けは検討中です。既存定義と入力側の参照は残し、操作の結果は以下の方式で直接記述します。
結果の返却項目の原本は各操作の `result` とし、既存モデル型との同期は行いません。

文字数上限・IDの形式など、まだ決めていない条件は各型の `notes` に記述します。
所有者の確認、参照先の存在、利用者内での名称重複など、他の情報との照合が必要な条件はドメイン側で定義します。
通信形式・プログラミング言語の型・DBの物理型への対応は後続設計で決めます。★

本ツールでは型参照の必須チェック・参照解決・一覧と型定義書の対応を検証します。
型定義書の業務ルールは設計情報であり、TODOアプリの実データに対する入力検証の実装は後続工程で行います。

## 操作の結果

各操作の `result` に、成功時に返す情報を直接記述します。独立したモデル型の参照で結果全体を包まず、複合値は返却する属性を列挙します。
結果全体には「TODOの詳細」などの名称を付けず、各返却属性に名称・意味・値型などを記述します。
これは業務上返す情報の定義であり、JSONの構造やHTTPのレスポンス形式を指定するものではありません。

| 項目 | 意味 |
|---|---|
| `kind` | `value`：単独の値、`record`：複数属性を持つ複合値、`none`：戻り値なし |
| `multiple` | `true`：リスト、`false`：単独。戻り値がある場合は明記する |
| `required` | 成功時に結果を必ず返すか。リストでは空のリストも許可する。戻り値がある場合は明記する |
| `data_type` | `kind: value` のときの値型。リストでも1件分の型を指定する |
| `attributes` | `kind: record` のときの1件分の返却属性。各属性に名称・意味・値型・必須・必要なら複数値を記述する |
| `constraints` | 結果の追加条件や受け渡しに関する補足 |

例えばTODO一覧は `kind: record`・`multiple: true` とし、1件分の属性を直接列挙します。
その中のタグ属性は `data_type: DT-TAG-ID`・`multiple: true` とします。結果の件数と、属性内の件数は別々に指定します。
TODOの詳細取得は同じ返却属性でも `multiple: false`、削除結果は `kind: value`・`data_type: DT-OPERATION-RESULT` です。

現在の操作は成功時に必ず結果を返します。一覧取得では該当なしを空のリストで表し、詳細取得で対象が存在しない場合は業務エラーとして扱います。
戻り値のない操作を追加する場合は `kind: none` とし、`multiple`・`required`・`data_type`・`attributes` は記述しません。
失敗時の条件は操作の `errors`、成功によって保証する状態は `postconditions` に記述します。

管理対象の全属性をそのまま返すことは前提にしません。返却属性がエンティティと重なる場合も、操作で返す情報として明示します。
属性の値の意味・制約は値型を参照します。現在の結果は属性を平坦に列挙し、複合値の属性内にさらに複合値を入れる形式はまだ導入していません。

## ディレクトリと責務

| 配置先 | 設計対象 | 個別文書で具体化すること |
|---|---|---|
| `21_domains` | ドメイン | 対象範囲・管理する情報・提供操作・業務ルール |
| `22_valuetypes` | 値型 | 単独の値の制約・取扱い・区分の選択肢 |
| `23_modeltypes` | モデル型 | 複合情報の制約・構成項目・各項目の業務データ型 |
| `31_screens` | 画面の基本設計 | 利用目的・表示や入力・操作・利用API |
| `32_apis` | APIの基本設計 | 提供機能・入出力の意味・業務エラー |
| `33_entities` | 論理エンティティ | ドメイン定義書のエンティティを具体化した属性・型・制約 |
| `41_views` | 画面の詳細設計 | 対象画面・構成部品・状態・イベント処理 |
| `42_apis` | APIの詳細設計 | 対象API・HTTPメソッド・パス・通信形式 |
| `43_controllers` | コントローラー | HTTP入力の受付・検証・サービス呼出し・応答変換 |
| `44_services` | サービス | 処理手順・業務ルール・トランザクション |
| `45_repositories` | リポジトリ | 永続化操作・検索条件・データ変換 |
| `46_tables` | 物理テーブル | カラム・主キー・一意制約・索引 |

`31_screens` と `41_views` は「何を操作できるか」と「どう表示・制御するか」を分担します。
`32_apis` と `42_apis` は「何を提供するか」と「HTTPでどう受け渡すか」を分担します。
`41_views` はTODOアプリのUI設計で、本ツールの一覧表示を定義する `.yde/views` とは別の用途です。

## 導出と参照

1. ドメイン一覧で対象ドメインを定義し、同じIDのドメイン定義書で管理する情報と提供操作を具体化します。
2. ドメイン内のエンティティを同じ `ENT-*` で `33_entities` へ詳細化します。新しい定義出現にはしません。
3. 提供操作から画面・API基本設計・サービスを導出し、`domain_operations` に `OP-*` を記述します。
4. 画面からビュー、API基本設計からAPI詳細設計を作り、後続側から基本側を参照します。
5. コントローラー → サービス → リポジトリ → テーブルの利用関係を記述します。

ドメイン自体への参照は一覧、エンティティと提供操作への参照はドメイン定義書を対象にします。
エンティティの名称・意味を後続文書に写さず、同一IDによる詳細化でつなぎます。
後続のエンティティ一覧を作る際も、ドメイン内の定義と同じIDを使い、どの文書を情報の原本とするかを明確にします。
逆方向の対応は本ツールの被参照表示で確認します。

管理対象や提供操作を変更するときは定義元を先に更新し、同一IDの詳細文書と被参照先への影響を確認します。
導出とは、定義を根拠に設計を具体化し、対応を記録することです。自動生成・自動同期はまだ設定していません。

## 本ツールの設定と確認

README.md と AGENTS.md はワークスペース直下に置き、設定・スキーマ・ビューは `.yde` に置きます。

- [.yde/workspace.yaml](.yde/workspace.yaml)：指定ディレクトリを台帳として宣言。
- [.yde/schemas/domain-list.yaml](.yde/schemas/domain-list.yaml)：ドメイン一覧の項目と定義書の存在検査。
- [.yde/schemas/domain.yaml](.yde/schemas/domain.yaml)：ドメイン定義書の項目と一覧への登録検査。
- [.yde/schemas/value-type-list.yaml](.yde/schemas/value-type-list.yaml)・[value-type.yaml](.yde/schemas/value-type.yaml)：値型の一覧・定義書と相互の存在検査。
- [.yde/schemas/model-type-list.yaml](.yde/schemas/model-type-list.yaml)・[model-type.yaml](.yde/schemas/model-type.yaml)：モデル型の一覧・定義書と相互の存在検査。
- [.yde/schemas/relations.yaml](.yde/schemas/relations.yaml)：設計要素間の関係。
- [.yde/views/domain-catalog.yaml](.yde/views/domain-catalog.yaml)：一覧情報の編集と個別定義書への移動。
- [.yde/views/value-type-catalog.yaml](.yde/views/value-type-catalog.yaml)：値型一覧の編集と型定義書への移動。
- [.yde/views/model-type-catalog.yaml](.yde/views/model-type-catalog.yaml)：モデル型一覧の編集と型定義書への移動。

本ツールでワークスペースを開き、「ドメイン一覧と定義書」ビューから一覧と個別文書を確認できます。
一覧のタイトル・概要・種別と、個別文書の詳細が分担されていることを確認してください。
「値型一覧と定義書」「モデル型一覧と定義書」ビューでは、それぞれの型を一覧できます。
ドメイン定義書の「業務データ型」欄では両方の一覧から型を選択し、参照先へ移動できます。
設計状態の `approved` への変更だけで、設計の網羅性や業務ルールの妥当性を自動判定することはありません。

リポジトリルートからCLIで検証できます。

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat runCli -q --args="validate labs/sysdev-todo" --no-daemon --console=plain
```
