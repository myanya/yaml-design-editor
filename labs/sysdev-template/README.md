# システム開発テンプレート（設計情報管理＋機械検証）

小規模（〜10万行）システム開発の文書体系（要件定義 → アーキテクチャ設計 → 基本設計 →
詳細設計 → 試験）を、YAML Design Editor のワークスペースとして定義したテンプレートです
（ROADMAP P28・R28-01）。サンプルとして「会議室予約システム」の設計一式が入っています。

このテンプレートの主眼は**設計の機械的検証**です。人間レビューとフォーマット規律で
担保していた品質チェックを、スキーマ・関係レジストリ・パターン制約として宣言し、
編集のたび／AIエージェントの提出のたびに自動で判定させます。

## 要件一覧の整備範囲

[ユースケース一覧](10_requirements/usecase-list.yaml)が対象業務と機能概要の原本です。
社員による予約操作、総務部によるマスタ管理・代理操作・集計、外部連携、開始前通知を対象とします。
アクター一覧（`actors`）と対象外の業務は[システム概要](10_requirements/system-overview.yaml)に記載します。
アクターは `ACT-*` のIDを持ち、ユースケース一覧の `actors` から実行できるアクターを参照します。
本人操作と総務部の代理操作は同じユースケースとし、権限・期限の違いを概要と詳細定義書に記述します。
照会・確認だけで完了する目的は独立したユースケースとし、予約操作中の照会・確認は各ユースケースの内部手順に含めます。
開始前通知は独立したユースケースに分けず、予約に付随する補助フローとして定義します。

ユースケースの詳細定義は `10_requirements/usecases/` にあります。
追加要件のドメインへの割当て、画面・API・処理への導出、試験への展開には未整備の部分があります。
検証では、この未整備による関係・導出・試験の不足も警告されます。
外部連携・自動通知には画面を必須としないため、詳細化時に画面導出チェックの適用範囲も見直す必要があります。

## 業務フロー

[業務フロー一覧](10_requirements/business-flow-list.yaml)が目的と概要の原本で、
同じ `BF-*` のIDを持つ[業務フロー定義](10_requirements/business-flows/)に契機・完了結果・工程・遷移を記述します。
予約と利用、代理対応、会議室管理、休日管理、実績集計、外部連携を業務フローとし、リマインドを補助フローにしています。

- 四角はユースケース単位の工程です。工程の `usecase` と `actor` で実行する業務と実行者を参照します。
- ひし形は判断・分岐、丸い枠は契機・完了状態・システム外の行為・付随的な自動処理です。
- レーンは登場アクターの業務上の役割です。判断・状態にも担当する `actor` を指定できます。主体のない工程はレーン外に置きます。
- システム外の電話連絡や実際の会議室利用を、システムのユースケースへ分解しません。
- 工程間の遷移と開始・完了点は同じフロー内のノードを参照します。IDはワークスペース内で一意にします。
- 利用停止・休日追加で影響を受ける既存予約の扱いは、該当フローの補足に未決事項として記載します。

BF-001は本人による予約・確認・変更・取消・利用と、期限後の総務部への引継ぎまでを扱います。
引継ぎ後の代理取消はBF-002にまとめ、各操作の入力エラーや再入力はユースケースの内部手順に置きます。

フロー表示は既存の `kind: flow` と `projection`（Construct）で原本を射影します。
アクターの適合はQuery＋Predicate、到達性・行き止まりは既存のflow検査で判定します。
専用コードや派生図ファイルは持たず、図の詳細編集は原本へ対応付けます。

### フローの確認

1. `labs/sysdev-template` を開き、「台帳」→「フロー」→「業務フロー図」を選びます。
2. 対象文書で `10_requirements/business-flows/BF-001.yaml` を選びます。
3. 「全体」で図を画面に収め、予約後の分岐、確認・変更からの戻り、期限後の総務部への引継ぎを確認します。
4. 工程または矢印をクリックし、右側でユースケース・アクターの参照や遷移条件を確認します。
5. 文書を直接開いた場合は「業務フロー定義書」の図でも確認できます。補助フローは `BF-007.yaml` を選びます。
6. 図の上部の「レイアウト」でグループ・スイムレーン・レーンなしを切り替え、「方向」で上下・左右の流れを比較します。
   BF-001の担当者は一般社員1人なので、スイムレーンも1本です。BF-002では総務部の帯と、担当未設定の工程の配置を比較できます。

一覧は「台帳」の「業務フロー一覧」ビューで確認できます。
CLIでの確認例:

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat runCli -q --args="view labs/sysdev-template business-flow 10_requirements/business-flows/BF-001.yaml"
.\gradlew.bat runCli -q --args="view labs/sysdev-template business-flow-list"
```

## 1. ディレクトリ構成と工程

| ディレクトリ | 工程 | 文書（スキーマ） | ID体系 |
|---|---|---|---|
| `00_input/` | インプット | ビジネスルール台帳（business-rule）——フリーフォーマット原文をルール単位に最小構造化 | BR-* |
| `10_requirements/` | 要件定義 | システム概要・アクター一覧／ユースケース一覧・定義書／業務フロー一覧・定義書 | ACT-* / UC-* / BF-* / BFN-* / BFT-* |
| `20_architecture/` | アーキテクチャ設計 | 方式設計書（arch-policy。オンライン／バッチ／ログを kind の cases で切替） | POL-* |
| `30_basic-design/` | 基本設計 | ドメイン一覧／定義書（domain-list / domain-detail。**UCをグルーピング**し、エンティティ・ユーザアクション・システムイベントを定義）／エンティティ定義書（entity-detail＝論理ER。**属性 EA-\* を定義**）／**業務データ型一覧（data-type-list）**／画面一覧・**画面遷移（screen-transition＝原本）**・画面定義（screen-list / screen-def。**画面項目 SI-\* を定義**）／外部・内部API一覧・定義（ext/int-api-list / -def） | DOM-* / ENT-* / EA-* / UA-* / EV-* / SCR-* / SI-* / DT-* / EAPI-* / IAPI-* |
| `40_detail-design/` | 詳細設計 | 画面設計書（screen-design。**物理項目 SPI-\* を定義**）／外部・内部API設計書（ext/int-api-design）／テーブル定義書（table-design＝物理ER。**カラム COL-\* を定義**）／**物理型マッピング表（type-mapping）**／処理設計書（process-design） | TBL-* / COL-* / SPI-* / MAP-* / PROC-* |
| `50_code-design/` | コード設計 | （未同梱。詳細設計と単体試験の間に置く、実装単位の設計の置き場） | — |
| `60_unit-test/` | 単体試験（**詳細設計向け**: 処理・API） | テスト項目一覧（test-items.yaml。対象×観点）／テストケース（testcases/）／実施結果（results.yaml） | TI-1xx / TC-1xx / RES-* |
| `70_integration-test/` | 結合試験（**基本設計向け**: 画面・遷移） | 同上 | TI-2xx / TC-2xx |
| `80_e2e-test/` | 総合試験＝E2E（**要件向け**: UC） | 同上 | TI-3xx / TC-3xx |
| `90_management/` | 管理 | 試験観点台帳（test-viewpoints.yaml。3階層で共通。test-viewpoint） | VP-* |

**論理→物理は同一IDの詳細化**: 画面（一覧→画面定義→画面設計書）・API（一覧→定義→設計書）は
同じIDの出現を重ねて詳細化します。ID横ぐしビューで結合表示され、存在の不足はカバレッジが検出します。

**画面遷移の原本は画面遷移文書**（`30_basic-design/screen-transitions.yaml`）: 人間レビューで
確定させるシンプルな遷移一覧（遷移元・遷移先・契機）で、レビューの流れは
「画面一覧＋画面遷移を確定 → 画面定義へ」。画面別の逆引きはビュー `screen-transition`（射影）。
バリデーション用の詳細な遷移モデル（到達性・デッドエンド）は R28-04 で検討。

**依存方向は下位工程→上位工程**: ドメイン定義書（基本設計）が UC（要件）を `groups` 参照で
グルーピングする（要件文書は下位工程を参照しない）。レジストリの inMin/inMax 1 により
「どのドメインにも取り込まれないUC」「複数ドメインに取り込まれたUC」が検出される。

**テストケースの消化宣言は手順ごと**: `steps[].items` にその手順が消化するテスト項目を書く
（どの手順がどの項目を消化するかが追える）。

**項目レベルのトレーサビリティ（v3）**: 属性・画面項目・カラムはIDを持つエンティティで、
対応は文字列（logical_name の一致）でなく**参照**で宣言する:
画面項目（SI-\*）→`source`→属性（EA-\*）←`implements`←カラム（COL-\*）、
物理画面項目（SPI-\*）→`item`→SI-\*。対応しない項目は enum の cases で構造的に逃がす
（画面: ボタン・合成表示／物理項目: `origin: 技術項目`＋理由／カラム: `origin: 技術カラム`＋理由）。

**業務データ型（v3）**: 属性・カラムの型は「業務型＝基本型＋制約」の台帳
（`30_basic-design/data-types.yaml`。DT-\*）への `dtype` 参照で宣言する。制約欄は基本型の
cases で変わる（文字列→最大桁・形式／整数→範囲／区分→区分値）。物理型（DDL型）はカラムに
書かず、**物理型マッピング表**（`40_detail-design/type-mapping.yaml`）が単一真実源。

## 2. 機械検証の一覧（このテンプレートの本体）

`validate` 実行・検証パネル・エージェント提出ゲートで、以下がすべて自動判定されます。

### スキーマ・フェーズ（記入規約）

- 各文書の必須項目・型・enum（方式設計書は種別ごとの必須項目＝ `cases`）
- 画面一覧のフェーズ（基本設計→詳細設計）。詳細設計に進むと画面設計書の存在チェックが有効化

### 参照・型・多重度（関係レジストリ `_schemas/relations.yaml`）

- 未解決参照・ID重複・未宣言 relation の使用禁止・定義出現の一意性（enforcement）
- 全ユースケースはシステム概要のアクター一覧から実行アクターを参照する（performed-by outMin 1）
- 業務フローは詳細定義を持ち、工程の実行アクター・到達性・行き止まりを検査する
- 全ユースケースはちょうど1つのドメイン定義書に取り込まれる（groups inMin/inMax 1。参照方向は基本設計→要件）
- 画面・APIは導出元（ユーザアクション／UC）を必ず持つ（derived-from outMin 1）＝「導出」規約の宣言化
- 処理はユーザアクション／システムイベントに紐づく（realizes outMin 1）
- 全エンティティはいずれかのテーブルに実装される（implements-entity inMin 1）
- **対象外（excluded）でない**全テスト項目はテストケースの手順に消化される（各項目スキーマの制約 ti-covered。excluded: true の項目は消化不要＝除外行の流儀）
- テスト項目の検証対象は試験階層に対応する工程の成果物のみ（単体=処理・API／結合=画面／総合=UC。scope で強制）
- 画面が呼べるのは内部APIのみ（calls の対象型宣言＋制約 screen-no-ext-api＝error）
- **入力・選択の画面項目は対応する属性（EA-\*）を必ず持つ**（kind の cases で必須化＝過剰・宣言漏れの構造的防止）
- **全論理画面項目は詳細設計で物理化される**（field-basic inMin 1＝画面 基本↔詳細の不足検出）
- **エンティティ由来のカラムは実装する属性（EA-\*）を必ず持つ**（origin の cases で必須化。技術カラムは理由必須で逃がす）
- **属性・カラムの業務型はちょうど1つ**（dtype outMax 1・スキーマ必須）
- **全業務型は物理型マッピング行を持ち、二重マッピングは禁止**（maps-type inMin/inMax 1）

### 詳細化の存在（カバレッジ detailIn）

- UC→ユースケース定義書、DOM→ドメイン定義書、ENT→エンティティ定義書、
  画面→画面定義／画面設計書（後者は詳細設計フェーズから）、API→API定義

### パターン制約（`constraints:`＝グラフパターンの存在／不在）

| 制約 | 内容 | レベル |
|---|---|---|
| br-referenced | 承認済みBRは UC・処理・テスト項目のいずれかから根拠参照されること（**取りこぼし検出**） | warning |
| br-unapproved-referenced | 承認済み**でない**BR（起草中・廃止など）が参照され続けていないこと（`notEquals`。R28-02） | error |
| uc-realized-by-screen | UCは UA を介していずれかの画面へ導出されていること（多段トレース） | warning |
| uc-tested | UCは**総合**テスト項目→テストケースまで到達していること（要件向け試験） | warning |
| proc-tested | 処理は**単体**テスト項目→テストケースまで到達していること（詳細設計向け試験） | warning |
| proc-unit-min2 | 各処理の単体テスト項目は**2件以上**あること（`count: {min: 2}`。R28-02） | warning |
| ti-covered（各項目スキーマ） | excluded でない全テスト項目はケースの手順に消化されること（`where` の `exists: false` で除外行を逃がす） | warning |
| attr-implemented | 導出属性（transient）でない全属性は、いずれかのカラムに実装されること（エンティティ↔テーブルの不足検出） | warning |
| col-type-consistent | エンティティ由来のカラムの業務型（dtype）は、実装する属性の業務型と一致すること（**型不一致検出**。ノード一致 `same:`＝R28-07 で2経路の合流を照合） | error |

### 網羅マトリクス（`matrix:`。R28-03）

| ルール | 内容 | レベル |
|---|---|---|
| proc-viewpoint-matrix | **各処理 × 全試験観点**の組合せに単体テスト項目（証人）が存在すること。「テスト項目は対象×観点で機械的に抽出する」規約の宣言化。あえて試験しないセルは `excluded: true` の項目行として残す（TI-107 が実例: バッチ×排他は対象外・理由つき） | warning |
| iapi-called | 内部APIはいずれかの画面から呼ばれること（死にAPI検出） | warning |
| screen-no-ext-api | 画面は外部APIを直接呼ばないこと（レイヤ規約） | error |
| eapi-auth-for-pii | 個人情報テーブル（pii）を使用する外部APIは認可方式（auth）必須（**条件付き義務**） | error |

## 3. サンプルに仕込んだ「意図的な不備」

検証がどう見えるかのデモとして、以下を意図的に残しています:

1. **UC-003（空き状況照会）の総合テスト項目がない** → uc-tested の警告。
   `80_e2e-test/test-items.yaml` に UC-003 の項目を足し、ケースの手順で消化すれば解消
2. **SCR-RESERVE-DETAIL の画面設計書が空**（項目未記入） → 論理項目 SI-RESERVE-DETAIL-CONTENT /
   -CANCEL の2件に「物理化されていない」警告（field-basic inMin 1）。
   警告クリックのメニュー**「参照の追加先を開く: 40_detail-design/screens/SCR-RESERVE-DETAIL.yaml」**で
   設計書を開き、SPI-\* 項目を書けば解消（マッピング漏れ警告→マッピング表も同じ導線が出る）

上記のほか、VP-004（異常値）観点に対する単体テストの不足（PROC-001/002）と、
「要件一覧の整備範囲」に記載した追加ユースケースの未整備が検証対象です。

壊すデモ:

- `10_requirements/usecases/UC-001.yaml` と `UC-009.yaml` の `based_on` から BR-004 を外す →
  承認済みルールの取りこぼしとして br-referenced の警告
- `30_basic-design/ext-apis/EAPI-001.yaml` の `auth: api-key` を消す → 個人情報テーブル
  （TBL-MEMBER: pii）使用×認可なしで **エラー**（eapi-auth-for-pii）
- `60_unit-test/test-items.yaml` の TI-107（excluded の除外行）を消す →
  PROC-002 に「網羅マトリクスに不足セルがあります（1 件）: VP-003（排他・競合）」の警告（proc-viewpoint-matrix）
- `40_detail-design/tables/TBL-MEMBER.yaml` の COL-MEMBER-MAIL 行を消す →
  ENT-MEMBER.yaml に「EA-MEMBER-MAIL がどのカラムにも実装されていない」警告（attr-implemented）
- `30_basic-design/screens/SCR-ROOM-LIST.yaml` の SI-ROOM-LIST-DATE から `source` を消す →
  入力項目の対応属性が未設定で **エラー**（必須項目。kind の cases）
- `40_detail-design/type-mapping.yaml` の MAP-PURPOSE 行を消す →
  DT-PURPOSE に「物理型マッピングの被参照が不足」警告（maps-type inMin 1）
- `40_detail-design/tables/TBL-MEMBER.yaml` の COL-MEMBER-MAIL の `dtype` を
  `DT-PERSON-NAME` に変える → 実装する属性（EA-MEMBER-MAIL＝DT-MAIL-ADDRESS）と
  型不一致で **エラー**（col-type-consistent）

## 4. よく使う操作

```bash
./gradlew runCli -q --args="validate labs/sysdev-template"          # 全検証（上記すべて）
./gradlew runCli -q --args="catalog labs/sysdev-template"           # 文書カタログ
./gradlew run --args="labs/sysdev-template"                          # GUIで開く
```

GUI では、台帳スペースのビュー「ビジネスルールのトレーサビリティ」「テスト項目マトリクス」
「画面遷移図（一覧）」で逆引きを確認できます。

## 5. 拡張のポイント（P28 の実験台として）

- 成果物の追加は「スキーマ＋ID体系＋relation 宣言＋カバレッジ／制約」のセットで足す
- ビジネスルールの原文→台帳の取り込み、テスト項目の機械抽出（対象×観点）は
  AIアクティビティ化の候補（`_activities` は未同梱）
- **項目レベルのモデル化（v3 で実装済み・2026-09-05）**: 属性 EA-\*・画面項目 SI-\*/SPI-\*・
  カラム COL-\* のID化と参照による対応宣言、業務データ型台帳 DT-\*＋物理型マッピング表 MAP-\*。
  対応の過不足（画面 基本↔詳細・属性↔カラム・型のマッピング漏れ）は上記 §2 の検査で検出される
- **画面遷移の2段階**（オーナー検討中）: 人間レビューで確定するシンプルな遷移原本（現状の
  screen-transitions.yaml）＋バリデーション用の詳細な遷移モデル（到達性・デッドエンド判定＝R28-04）
- **R28-02/03 で解消した不足**: 件数の下限・上限（`count`＝proc-unit-min2）・値の否定（`notEquals`＝
  br-unapproved-referenced）・網羅マトリクスの不足セル列挙（`matrix:`＝proc-viewpoint-matrix。除外は excluded 行）
- **R28-07 で解消した不足**: 型不一致の検出（カラムの dtype ≠ 実装する属性の dtype）——
  ノード一致 `same:`（2経路が同一ノードへ到達する照合）を追加し、`col-type-consistent`
  制約（table-design・error）で判定。画面項目の型貫通も同じ語彙で書ける（現状は対象外）
- **残っている不足**（→ ROADMAP P28 の後続）:
  - 束縛同士の値比較（例: 画面設計書の max_length ≦ 業務型の最大桁）——ノード一致で足りないケースのみ後続検討
  - 画面遷移の到達不能・デッドエンド判定（グラフ全体の性質）——R28-04
