# AWS WEBアプリ設計ラボ

企業向けの申請・承認ポータルを題材に、**AWSサービスの具体的な設定を原本として、構成図・パラメータ表・CloudFormation生成入力を導く**ワークスペースです。
東京2AZのWEBアプリに、添付ファイル、セッション、非同期処理、ログ、監視、組織統制、バックアップ、配備パイプラインを含めています。

211資源・87種類のCloudFormation ResourceType、APIとWorkerの実行設定、4件のサービスAPI設定を定義しています。
AWSへの接続や資源作成は行いません。実環境への33入力は未束縛です。AWS上での動作・性能・復元は未検証です。

## 最初に試す

このフォルダ全体をコピーし、YAML Design Editorでコピーをワークスペースとして開きます。
複数文書flowの`sources`対応を含む、このリポジトリのビルドを使用してください。

1. ダッシュボード **「AWS WEBアプリの構成設計」**を開きます。「サービス一覧」から各資源の原本を開けます。
2. **「ALBの全設定」「Listenerの全設定」「転送Ruleの全設定」「Target Groupの全設定」**を順に見ます。表には、そのサービスに採用したPropertiesの全項目が並びます。配列・objectのセルから詳細へ進めます。
3. `model/resources/RES-ALB.yaml`を開き、「ビュー」→ **「全設定・参照元」**で、配置・サービスの全設定・参照元を確認します。「フォーム」「ソース」でも同じ原本を編集できます。
4. **台帳 → フロー →「01 WEBアプリの全体構成（設定から導出）」**を開きます。対象は`model/system.yaml`です。利用者、CloudFront、ALB、ECS、DB、Valkey等の16要素を表示します。
5. **「02 入口の詳細（Behavior・転送条件・登録先）」**へ切り替えます。Behavior → Origin → VPC Origin → ALB → Listener → Rule → Target Group → ECS Service → Task Definitionを確認します。
6. **グラフレンズ**の記法を **「AWS設定の参照を辿る」**にします。`RES-SERVICE-API`や`RES-SESSION`を起点に、設定からの参照を辿ります。範囲・型を絞って確認してください。
7. **レポート →「全サービスのパラメータ設計書」**で、全資源・実行設定・配備入力を一冊に展開します。保存先は派生物の`reports/parameter-book.md`です。

ソースから起動する場合:

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat run --args="コピー先の絶対パス"
```

## パラメータはどこにあるか

**設定の正は`model/resources/`の各YAMLの`properties`です。** 表・図・レポートはこの値を読みます。
「配備時の入力」はAccount IDやimage digest等の外部入力であり、サービスの設計パラメータ全体とは別です。

| 確認するもの | 原本 |
|---|---|
| ALBのSubnet・SG・アクセスログ・idle timeout・削除保護 | [RES-ALB.yaml](model/resources/RES-ALB.yaml) |
| HTTPS・証明書・TLSポリシー・既定404 | [RES-LISTENER.yaml](model/resources/RES-LISTENER.yaml) |
| `/api/*`・`/auth/*`の転送先と優先度 | [RES-RULE-API.yaml](model/resources/RES-RULE-API.yaml) |
| ポート・ヘルスチェック・draining・stickiness | [RES-TG.yaml](model/resources/RES-TG.yaml) |
| タスク数・配置・ネットワーク・Target Groupへの登録 | [RES-SERVICE-API.yaml](model/resources/RES-SERVICE-API.yaml) |
| image・CPU/メモリ・環境変数・Secrets・ログ・ADOT | [RES-TASK-API.yaml](model/resources/RES-TASK-API.yaml) |
| DB接続・セッションTTL・Cookie・認証・添付の扱い | [api.yaml](model/apps/api.yaml) |
| Queue受信・検査済み添付の昇格・監査・メール | [worker.yaml](model/apps/worker.yaml) |
| Account ID・AZ ID・image digest等の実環境入力 | [inputs.yaml](model/inputs.yaml) |

18種類のサービス別表に加え、残りの資源も「サービス一覧」→原本の「全設定・参照元」で全項目を確認できます。
スキーマはこの設計で採用した設定を対象にしています。AWSの全オプションを網羅した汎用スキーマではありません。

## 設定を変えて確かめる

コピー上で`RES-RULE-API.yaml`の`properties.Priority`を100から150へ変更します。
全体図のALB→ECSのラベル、転送Ruleの表、生成入力、再生成した設計書へ反映されます。図の線を別途編集する必要はありません。

`RES-SERVICE-API.yaml`の`properties.LoadBalancers[0].ContainerPort`だけを9090へ変更すると、Target Group・Taskの設定との不一致を検出します。演習後はUndoで戻してください。
転送先を別のTarget GroupとServiceへ変更する受入試験も、[verify.cjs](scripts/verify.cjs)に含めています。

## 構成の範囲と検証

- [設計の一覧と採用条件](DESIGN.md): サービスの役割・具体的な設定・アプリとの連携。
- [生成入力の契約](GENERATION.md): native Properties、設計参照、外部入力、次の生成工程。
- [本ツールで扱える範囲と不足](CAPABILITIES.md): 実装済みの射影と残る拡張。
- [検証結果と再実行](VERIFICATION.md): 正例・反例・画面で確認した範囲。
- [再設計の結果](REDESIGN.md): 旧モデルから変更した点。
- [AWSの根拠資料](SOURCES.md)、[アイコンの帰属](assets/README.md)。

処理順序の図は`model/scenarios/`にあります。構成図は設定から、処理図は業務・運用手順から導きます。
CloudFormationの実行可能テンプレート、業務アプリの実装、実環境への配備はこのラボに含めていません。
