# 検証範囲と再実行

基準日: 2026-11-04。検査は作業コピーで実施し、AWS APIを呼ばず、実資源を作成していません。

製品の全テスト、文書参照・ソース規則テスト、CLIを33回実行するラボの受入試験はすべて成功しました。
文書lintは失敗0件です（既存の助言98件）。
反映後のワークスペース検証も、280文書・台帳228件に対してエラー・警告・保留は0件でした。

## 機械で確かめる内容

| 検査 | 対象 |
|---|---|
| 製品の全テスト | `gradlew test`。複数文書flowの追加を含む全モジュール |
| ラボの構造・意味 | 211資源、87 ResourceType、参照、28件の資源間検査、アプリ設定・入力の型検査 |
| AWS仕様との照合 | 取得済みResource Specification 270.0.0に対するプロパティ名・必須・構造型・GetAtt属性。生成用の独自属性は対象型も検査 |
| 全体構成図 | 16ノード・18辺、設定の来歴、単一原本の編集適格性、未解決・表示対象外なし |
| 入口詳細図 | 14ノード・14辺。Behavior→Origin→VPC Origin→ALB→Listener→Rule→TG→Service→Taskの経路 |
| 表・設計書 | 18種類の全設定表、原本の各Propertiesを保持する全資源の設計書、全区画が非空で来歴を持つこと |
| 生成入力 | 資源211件、アプリ2件、サービスAPI設定4件、未束縛入力33件。Propertiesの欠落なし |
| 処理・運用の図 | 6シナリオの解決・参照・診断 |

受入試験では、作業コピーに別のTarget GroupとECS Serviceを追加し、**Ruleの転送先だけを変更**します。
ALB→ECSの全体図、詳細図のRule→TG、転送Ruleの設定表、CFn生成入力、再生成した設計書が新しい設定を示し、旧経路が消えることを確認します。
表示用の接続や図の原本は変更しません。

反例は、登録ポート、タスク数、S3公開、セッションTTL、DBポート、参照先種別、同一AZ、Logical ID重複、未入力の束縛済み宣言、構造型違反です。
未解決の転送先は、詳細図に未解決端点、全体図に経路を辿れない診断が出ることも確認します。

## 画面での確認

Hot Reload MCPの意味ツリーで、次を操作して確認しました。

- ダッシュボードのサービス別全設定表と行数。
- ALBの全設定表にScheme・Subnets・SecurityGroups・LoadBalancerAttributesが現れ、詳細から原本のサービス設定へ進めること。
- 全体構成図と入口詳細図を選択し、対象が`model/system.yaml`になり、配置処理が完了すること。

ノード名・線・アイコンの実際の配置はCanvas上にあり、意味ツリーには現れません。
ノード選択の実クリック、PNG保存、ラベルの重なりは今回の意味ツリー確認の対象外です。
データの来歴・編集適格性・未保存変更とUndoは製品の統合テスト、図の内容はCLIの解決結果で検査しています。

## 再実行

リポジトリのルートでJDK 21を指定し、CLIを用意します。Web依存は通常のビルド時に取得されます。

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat :cli:installDist --no-daemon --console=plain
node labs/aws-webapp/scripts/validate-contract.cjs
node labs/aws-webapp/scripts/verify.cjs
```

`verify.cjs`は`scratchpad/aws-webapp-verify-…/`へコピーしてCLIを実行します。
`--keep`を付けると、CLI出力と作業コピーを確認用に残します。原本のハッシュが変わっていないことも確認します。

CLIで手動確認する例:

```powershell
.\gradlew.bat runCli -q --args="workspace validate labs/aws-webapp"
.\gradlew.bat runCli -q --args="view labs/aws-webapp entry-flow"
.\gradlew.bat runCli -q --args="report labs/aws-webapp parameter-book --print"
.\gradlew.bat runCli -q --args="transform labs/aws-webapp labs/aws-webapp/transforms/deployment-input.yaml"
```

## 成功の意味

上記の検証は、設定と表示・生成入力が一致し、定義した制約を満たすことの確認です。
未束縛入力が存在するため、検査エラー0を配備可能・運用可能という判定には使いません。
CFn生成器、cfn-lint、IAMの実効評価、実配備、負荷・障害・復元・費用の実測は[次の生成工程](GENERATION.md#次の生成工程の受入条件)です。
