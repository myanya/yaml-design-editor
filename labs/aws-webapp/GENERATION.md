# CloudFormation生成入力の契約

## 原本と4種類の入力

`model/resources/`は1資源1文書です。`resourceType`は実際のCFn型、`properties`は実際のプロパティ名です。
Listener、Rule、Target Group、Scaling、Subnet Group、Parameter Group、Policy等も個別の資源として定義しています。

| 変換定義 | 出力 |
|---|---|
| [deployment-input](transforms/deployment-input.yaml) | 資源の`properties`・Logical ID・配備単位を結合した配列。contractVersion 2 |
| [application-input](transforms/application-input.yaml) | API/Workerの接続・認証・セッション・ジョブ・ログ設定 |
| [foundation-input](transforms/foundation-input.yaml) | Inspector、ECR、GuardDuty組織設定、Config開始のAPI要求 |
| [parameters-input](transforms/parameters-input.yaml) | 配備時入力の状態・実値・例。`unbound`の例を実値として使わない |

```yaml
id: RES-RULE-API
resourceType: AWS::ElasticLoadBalancingV2::ListenerRule
logicalId: RuleApi
stack: STK-APP
properties:
  ListenerArn: {ref: RES-LISTENER}
  Priority: 100
  Conditions:
    - Field: path-pattern
      PathPatternConfig: {Values: [/api/*, /auth/*]}
  Actions:
    - Type: forward
      TargetGroupArn: {ref: RES-TG}
```

上は原本の抜粋です。変換は`properties`を欠落させずに写し、`stack`を参照先の配備情報へ展開します。
**この出力は完成したCloudFormationテンプレートではありません。** 下記の参照束縛と出力mapの組立ては次の工程です。

## 設計参照の展開

`ref`は本ツールの参照型です。ARNらしい文字列を図の接続として推測しません。
`attribute`の省略はCFn `Ref`の返値を意図し、`suffix`は展開後の文字列の末尾へ付けます。
固定文字列と参照が同じAWS項目に現れる箇所は、固定側を`{literal: ...}`で記述します。

| attribute | 生成側の契約 |
|---|---|
| `Ref` | 資源型のCFn Ref返値。すべての資源でARNが返るとはみなさない |
| `Arn`・`DNSName`・`Endpoint.Address`等 | 対象型で実在するGetAtt属性。仕様の抜粋に対して照合する |
| `Parameter` | `model/inputs.yaml`の`state: bound`かつ実値を持つ入力。未束縛なら生成を止める |
| `Literal` | 同じDistributionに属するOriginのネイティブ`Id`。Behaviorからの参照を保って文字列にする |
| `ApplicationSettings` | 参照先のアプリ設定内の参照を解決し、APP_CONFIG用のJSON文字列にする。秘密の本文は埋め込まない |
| `SecretString:password` | Secrets ManagerのJSON key `password`を実行時／動的参照として束縛する。平文へ展開して保存しない |
| `EcsServiceResourceId` | ServiceのClusterと実Service名から`service/<cluster-name>/<service-name>`を組む |
| `CloudFrontArn` | Partition・配備Account・Distribution IDからCloudFront ARNを組む。架空のGetAtt Arnにしない |
| `SesIdentityArn` | Partition・Region・Account・EmailIdentityからSES identity ARNを組む |
| `S3Uri` | Bucketの実名から`s3://<name>`を組む |
| `ConfiguredName` | Logs DeliverySourceの`properties.Name`を読む。名前の展開に加えて作成依存を残す |

最後の各変換は生成器が実装すべき契約で、現在のYDE変換はシンボルを保持します。
設計用の`id`を追加したCacheBehavior／DefaultCacheBehaviorでは、そのメタデータだけをCFn出力から外します。Originの`Id`はAWSの項目なので残します。

## 配備単位と未束縛値

`RES-*`等は設計ID、`logicalId`はCFnのLogical ID、`ACC-*`はAccountの設計IDです。
AccountとAZの実値は`context.yaml`から`inputs.yaml`を参照します。API・Worker・ADOTのimageはdigest固定の入力です。
ドメイン名は`example.com`の例で、公開URL・ACM・DNS・Cognito callback・CORSの整合を保って差し替えます。

CloudFront用証明書・WAF・ログ配送定義はus-east-1、業務資源は東京、復旧保管は大阪です。
別Region・別Accountの参照を、同一StackのRefやImportValueだけで解決した扱いにはしません。

## 次の生成工程の受入条件

1. 未束縛の実値・未対応属性・参照の欠落を拒否し、どの原本項目が原因か返す。
2. AWSのProperties型へ参照を展開し、CloudFormationにない設計メタデータを除く。
3. Stack境界・物理名・実行順序を解決する。資源の依存と、共通基盤の準備順である`prerequisites`を区別する。
4. Policy・Grant等を含む依存循環を検査する。例えばS3/OAC/Distributionの相互依存は、Bucket作成とPolicy適用の段階や配置を分ける必要がある。現在の配備単位はそのまま実行順序を保証しない。
5. 配列からLogical IDをキーにする`Resources` mapを組み、キーの衝突を拒否する。現在のConstructには任意の動的キーへのmap組替えがない。
6. cfn-lint、Guard、IAM解析、ChangeSet、検証環境への配備、疎通・障害・復元試験を実施する。

実行可能テンプレートをまだ出していないため、cfn-lintやAWS配備が通ったとは扱いません。
YAML変更を生成物だけへ書き戻さず、設定の原本へ戻します。検証範囲は[VERIFICATION.md](VERIFICATION.md)を参照してください。
