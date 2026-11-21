# アプリの生成と独立ビルド

現在は、独立した Spring Boot プロジェクトの基本構造、物理テーブルの Java 行モデル、Mapper と XML、共通 DDL、状態型と業務例外を生成する。
H2 で実際の保存・検索・更新・削除を確認できる。Service の操作本体・Controller・画面は、[作業計画](application-generation-plan.md)の後続で追加する。
設計書のレビュー完了や業務アプリの完成を意味しない。

## 入力と出力

業務モデルから導けないアプリ名・パッケージ・依存版・起動ポートは、方式による追加設定として持つ。
人が書くのは `design/detail/inputs/application/settings.yaml`。入力検査を通した値だけを `design/detail/final/application.yaml` に保存する。
`.yde/reports/application/` は、その確定値を生成用ビューから読み、文字のひな形で出力する。未確定の入力は直接読まない。

| 対象 | 現在の出力 |
|---|---|
| ビルド | settings・build・gradle.properties・専用 Wrapper |
| 起動 | Java の起動クラス・application.properties |
| データの型 | 物理テーブルごとの Java 行モデル（`data/model/`） |
| データアクセス | `data/mapper/` のインターフェースと入力・結果の型、`resources/mappers/` の MyBatis XML |
| DB | レビュー用 DDL と同じレポートから出す `resources/schema.sql` |
| 状態と例外 | `service/state/` の状態型と、`service/error/` の違反・業務例外 |
| 検証 | Spring の起動、DB 初期化、Mapper の読込みを確かめるテスト |
| 利用 | README・.gitignore |

基本構造に、テーブルの行モデル、Mapper と対応する XML、DDL、状態型と業務例外を加える。
Wrapper の JAR とスクリプトは、公式タスクで準備した固定資源をコピーする。
業務の処理・画面はまだこの出力に含めない。Mapper の直接呼出しは、業務の前提や状態の検査を代行しない。
空の DB には起動時に DDL を適用し、必要なテーブルがそろっている既存 DB はそのまま使う。必要なテーブルが欠けていれば起動を止める。
Flyway は使わない。既存の列・制約の比較や変更・データ移行は未対応なので、設計変更の検証には別の DB 名を使う。

## 型と出力単位の確定

`inputs/application/java-types.yaml` の方式を検査し、`final/java-types.yaml` に確定する。
Form の設計と行モデルはこの同じ対応を読む。文字・ID・参照・区分は `String`、整数は `Long`、数は `BigDecimal`、真偽値は `Boolean`、日付は `LocalDate`。
省略された値と `false`・`0` を区別するため、プリミティブ型へ変換しない。Form の配列は要素の型を使う。
未知の基底型や対応表の不足を `String` で補わず、生成前の検査で止める。

`final/application-data-model.yaml` は、確定した物理列と基本設計の値から、クラス・プロパティ・Java 型・SQL 型を導く。
親のキーは元の型を引き継ぐ。複合の親キーには整数の並び順や文字のマップキーも含まれる。列名の補正もここへ届く。
Java 名の衝突・予約語、未対応の SQL と Java の組合せを検査する。SQL の対応範囲は VARCHAR・BOOLEAN・INTEGER・BIGINT・DECIMAL・NUMERIC・DATE。
行モデルは入れ子を組み立てるドメインオブジェクトではなく、物理テーブルの一行を表す。

初期値は `{value, literal}` として保持し、空文字・`false`・`0` と未設定を分ける。
行モデルのフィールド初期化には使わない。挿入時に値が無く、DB の初期値がある列には `DEFAULT` を出す。`false`・`0`・空文字の入力はそのまま渡す。
更新では未指定の項目を保ち、設計のクリア操作で明示した列に `NULL` を書く。同じ列の通常指定とクリアが重なると、クリアを優先する。
SQL・XML・Java の文字列のエスケープはレポートの出力形式を使う。Service の初期化式と業務の検査は後続で接続する。

`final/application-output-plan.yaml` は、実行するレポートと引数を列挙する。
レポート `application-outputs` が実行用の一覧に写し、スクリプトはその一覧だけを実行する。
Java クラス名・型・対象数を PowerShell で計算しない。行モデルの増減も、既存の生成記録・競合検査・削除の経路で適用する。
レビュー用ビュー `application-row-models` で、物理名・Java 名・両方の型・初期値を照合できる。

## Mapper の確定と対応範囲

`final/table-access/` と行モデルから `final/application-access.yaml` を作る。条件・列・型を解決し、検査を通った値だけをレポートとレビュー用ビューへ渡す。
検索の件数は `whereFrom` で元のメソッド ID を指定し、同じ条件を共有する。挿入の全列指定は `{mode: all}` で表す。
ビュー `application-mapper-methods`（「Java の Mapper と SQL の対応」）で、48 メソッドの入力型・取得項目・条件・更新内容を確認できる。

- 現在の検索、件数、最大値、挿入、更新、削除を生成する。引数は型付きの Input、取得結果は行モデルを継承する Result にする。
- 配列の絞り込みは `EXISTS` を使い、複数タグに一致しても親を重複させない。空入力を省く条件と、省かず不一致にする条件を区別する。
- 子の取得は親キーで対応付け、指定した並び順を保つ。参照先の表示名と、一覧の参照件数・状態別件数・作業数の表示を生成する。
- 値は MyBatis の引数として渡す。設計に書かれた定数は型に応じて出力し、列名・Java 名・演算子・未解決の参照・必須の更新条件を生成前に検査する。
- ID の最大値は文字数と文字の順、子の並び順は数の最大値を使う。ID の組み立てと採番の同時実行制御は、Service 側の作業に残る。

現在の `todos` にある条件・件数と表示用の計算が対象。任意の式からの SQL・Java 生成や、H2 以外の DB での動作は未対応。
設計の SQL 文字列定数に MyBatis の予約記法 `#{`・`${` が含まれる場合は生成を止める。実行時の入力値は引数で渡すので、この制限はない。
入力・結果の型と SQL の対応は [MyBatis の XML](https://mybatis.org/mybatis-3/sqlmap-xml.html) と [動的 SQL](https://mybatis.org/mybatis-3/dynamic-sql.html) の規則に従う。

## 処理の指定と状態型

処理設計の `transactionMode: atomic` は、操作全体を一つのトランザクションにする指定。
`checkMode` は状態・現在値・更新後の値・循環・件数を区別し、`entity` と `at` が検査対象、`phase` が書込み前後を示す。
採番した値は `{generated: numberedId}`、既存行を読むキーは `{param: id}`、親のキーは `{self: todo_id}` のように保持する。
明細の並び順は `{mode: position, start: 1}` または `{mode: next, call, key}`、値の中の採番は `{sequence: {call, key}}`。
説明文の中から呼出名や値の意味を取り出さない。並び順の呼び先にも Mapper の補正を適用する。

`composed/application/process-contract.yaml` は、確定した処理設計のトランザクション指定・検査区分・呼出先を照合する記録。
採番を含む呼び先が未解決ならアプリの生成を止める。式のすべてを Java にできることを保証する検査ではない。
更新全体には最初の代入の条件を付けず、各代入が条件を持つ。配列のクリアも明細の削除として保持する。

`final/application-runtime-model.yaml` は確定した状態設計とエラー設計を読み、状態型と例外クラスを組み立てる。
状態の群・終端・操作の可否・次の状態を Java の enum に生成する。未定義の状態、同じ操作と開始状態に重なる遷移、Java 名の不正は生成前に拒否する。
ビュー「Java の状態型と遷移」で同じ確定値を確認できる。
`BusinessViolation` はメッセージのキー・欄・埋め込み値を保持し、確認時の戻り値と実行時の `RefusedException` で共有するための型。
状態型と例外はまだ Service の操作本体へ接続していない。実際の更新、確認時の非更新、拒否時の取り消しは次の実装・検証対象。

これらは Query で確定値を選び、Construct で生成値を組み立て、Predicate で検査し、既存レポートで Java に出力する。
YDE 本体に業務固有の評価器や書込経路を追加しない。

## 生成する

Windows の PowerShell 7 で、YDE リポジトリのルートから実行する。
設計を変更したときは、先に通常の `generate samples/todos-sysdev --apply` で確定項目を更新する。
デスクトップがワークスペースを開いている場合は、作業用コピーで生成して確認する。

```powershell
# 作成・更新・削除の予定を確認する（アプリには書かない）
./samples/todos-sysdev/scripts/generate-app.ps1
# 予定を検査して適用する
./samples/todos-sysdev/scripts/generate-app.ps1 -Apply
# 同じ入力で差分がないことを確かめる（差分があれば失敗）
./samples/todos-sysdev/scripts/generate-app.ps1 -Check
```

スクリプトは YDE CLI を一度ビルドしてから、設計だけを一時領域へコピーする。
検証・確定項目の最新性・全レポート・固定資源を確かめ、生成先の重複と埋まらない穴も検出する。
`-Workspace <場所>` で作業用コピーを指定できる。CLI が最新の検証では `-SkipCliBuild` でそのビルドを省ける。

ファイルの一覧と SHA-256 は `design/detail/reports/application-files.json` に保存する。
前回の内容から変更・削除されたファイルや、生成記録のない異なる内容のファイルがあれば上書きせず止める。
新たな出力と同じ内容の既存ファイルだけは、生成記録へ登録できる。不要な出力は、記録と現在の内容が一致するものだけ削除する。
適用中に書込が失敗した場合は、今回の変更を戻す。別の処理による変更や復元自体の失敗は、該当ファイルを報告する。
電源断からの自動復旧は、この手順では扱わない。

## ビルド・起動して確かめる

Java 21 を用意し、`app/` を作業場所にする。初回は依存関係の取得にネットワークが必要。

```powershell
cd samples/todos-sysdev/app
./gradlew.bat test bootJar projects
java -jar build/libs/todos-generated.jar
```

`projects` に `todos-generated` だけが出ることと、テストの成功を確認する。
起動時は 8085 番ポートを使う。まだ業務画面がないため `/` は 404 になる。終了は起動した端末で Ctrl+C。
DB・ビルド成果物はアプリ内の `data/`・`build/`・`.gradle/` に置かれ、設計の入力にも Git にも入らない。
`app/` だけを別の場所へコピーして同じ手順で実行できる。macOS・Linux では `sh ./gradlew test bootJar projects` を使う。

標準の todos 設計について全 Mapper の入出力まで確認する場合は、リポジトリのルートで次を実行する。

```powershell
./samples/todos-sysdev/scripts/verify-app.ps1
```

アプリだけを `scratchpad/application-verification/<作業ID>/` にコピーし、`verification/` の Mapper と状態型・例外のテストを検証用コピーへ追加して実行する。
この検証用データは標準の `example.todos` と現在の業務項目を対象とする。生成したアプリ自体のテストは、パッケージやテーブルを補正した場合も確定項目から作る。
検証結果はコピー内の `build/reports/tests/test/index.html` に残す。元のアプリとその DB には書かない。

## 検証の分担と固定資源

- `TodosApplicationTest`: 確定設定と生成物の一致、未確定値を読まないこと、方式の変更と不正な入力の拒否。
- `TodosJavaModelsTest`: Form の要素型、物理列の網羅、補正の伝達、型の不足・SQL の不一致・Java 名の衝突による停止。
- `TodosMappersTest`: 全メソッドの網羅、検索と件数の一致、補正の伝達、未解決の条件・列・演算子による停止。
- `TodosServiceFoundationTest`: 処理の個別条件・明細の削除・親の検査対象、状態型・例外・採番の呼出名の補正、未解決の状態と呼び先による停止。標準型と同名に補正した Java のコンパイルも確認する。
- `scripts/verify-app.ps1`: 全 Mapper の実行、DB の初期値、子の順序、絞り込み、集計、変更・クリア・削除、状態の遷移と違反の値の保持、独立ビルド。
- `scripts/test-application-files.ps1`: 再生成の一致、空ファイル、競合、書込失敗時の復元、名前変更・削除の範囲。
- `.github/workflows/todos-application.yml`: YDE の通常テストと別のジョブで、生成の一致とアプリ単独のビルドを確認する。

固定する版は Java 21・Spring Boot 4.0.9・MyBatis Starter 4.0.1・Gradle 9.3.0。
[Spring Boot の対応範囲](https://docs.spring.io/spring-boot/4.0/system-requirements.html)と、
[MyBatis の対応範囲](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/index.html)を基に、独立ビルドで検証する。
対応版を変えるときは設定の形式と固定資源も更新し、同じ検証をやり直す。
Wrapper の取得元・作り直し方・ハッシュは [固定資源の記録](../assets/gradle-wrapper/README.md)を参照する。
