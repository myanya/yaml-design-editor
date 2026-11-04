# WEBアプリの構成設計

申請・承認、企業認証、添付ファイル、非同期ジョブ、業務監査を持つポータルを想定します。
本番は東京2AZ、バックアップコピー先は別Accountの大阪です。稼働性能・SLO・復旧時間は設計目標で、実測結果ではありません。

## サービスと設定

| 範囲 | 採用サービスと設定 | 設定の入口 |
|---|---|---|
| 組織・責務 | 管理・セキュリティ・ログ・本番・復旧Accountを分離。Organizations、SCP、Identity Centerの閲覧Permission Setと割当 | [context](model/context.yaml)、[SCP](model/resources/RES-SCP.yaml) |
| ネットワーク | VPC `10.40.0.0/16`、公開／アプリ／データSubnetを各2AZ。AZごとのNAT、IGW、経路表と関連付け、Interface/Gateway Endpoint | [VPC](model/resources/RES-VPC.yaml)、[Subnet A](model/resources/RES-SUB-APP-A.yaml) |
| 通信許可 | CloudFront origin-facing Prefix List→ALB:443、ALB→API:8080、API/Worker→DB:5432、API→Valkey:6379。SG本体とIngress/Egress資源を分離 | [ALBの受信規則](model/resources/RES-SG-ALB-IN-1.yaml)、[APIの受信規則](model/resources/RES-SG-APP-IN-1.yaml) |
| 配信・入口 | Route 53 A/AAAA、CloudFront、WAF、ACM。既定はS3/OAC、`/api/*`・`/auth/*`はVPC Origin→内部ALB。API用Cache PolicyはTTL 0 | [CloudFront](model/resources/RES-CDN.yaml)、[Cache Policy](model/resources/RES-CACHE-API.yaml) |
| ALBの設計 | HTTPS:443、TLSポリシー、証明書、既定404。Ruleの優先度100とパス条件→Target Group。HTTP:8080、`/health/ready`、間隔30秒・timeout5秒・正常2回／異常3回、draining60秒 | [Listener](model/resources/RES-LISTENER.yaml)、[Rule](model/resources/RES-RULE-API.yaml)、[TG](model/resources/RES-TG.yaml) |
| アプリ | ECS Fargate ARM64、API/Worker各2タスク、1vCPU/2GiB、public IPなし、Circuit Breaker rollback、AZ再分散。APIは2〜10、Workerは2〜6へCPU60%で追従 | [Service](model/resources/RES-SERVICE-API.yaml)、[Task](model/resources/RES-TASK-API.yaml)、[Scaling](model/resources/RES-SCALE-API.yaml) |
| 業務DB | Aurora PostgreSQL 17.7、別AZの2インスタンス、db.r6g.large、暗号化・削除保護、35日PITR、保守時間、Parameter GroupでTLS強制、拡張監視 | [Cluster](model/resources/RES-DB.yaml)、[Parameter Group](model/resources/RES-DB-PARAMETERS.yaml) |
| セッション | Valkey 8.2、cache.r7g.large、2ノード、Multi-AZ、自動切替、TLS、暗号化、専用ACLユーザー、noeviction。アプリ側で無操作30分・最大8時間・ログイン時ID更新・ログアウト失効 | [Valkey](model/resources/RES-SESSION.yaml)、[API設定](model/apps/api.yaml) |
| 認証 | Cognito→企業SAML IdP。コードフロー＋PKCE S256、state/nonce検証。public clientでClient Secretなし。MFAは企業IdPで必須とする | [User Pool](model/resources/RES-COGNITO.yaml)、[Client](model/resources/RES-COGNITO-CLIENT.yaml) |
| S3 | 静的配信、隔離、検査済み添付、レポート、監査、ログ、配備成果物を用途で分離。公開禁止、版管理、保持・削除、暗号化、Bucket Policy、必要なCORS | [添付](model/resources/RES-S3-FILES.yaml)、[隔離](model/resources/RES-S3-QUARANTINE.yaml) |
| 添付検査 | 署名付きPUTは5分・最大10MiB・checksum必須・`uploads/`配下。GuardDuty Malware Protection→EventBridge→検査結果Queue。WorkerはNO_THREATS_FOUNDの同一versionだけ昇格 | [検査](model/resources/RES-MALWARE.yaml)、[Worker設定](model/apps/worker.yaml) |
| 非同期・通知 | Standard SQS、ジョブ／検査結果QueueとDLQ、長いpoll、visibility更新、DB commit後の削除と冪等性。SchedulerでOutbox排出、SESと配信結果SNS | [Queue](model/resources/RES-QUEUE-JOBS.yaml)、[SES](model/resources/RES-SES.yaml) |
| 権限・秘密 | Task実行RoleとAPI/Worker Roleを分離。S3/SQS/Secrets/KMS等の対象を指定。生成Secretの本文をYAMLに保存しない。KMSのrotationを365日で設定 | [API Role](model/resources/RES-ROLE-API.yaml)、[実行Role](model/resources/RES-ROLE-EXEC.yaml)、[鍵](model/resources/RES-KMS-DATA.yaml) |
| ログ・トレース | コンテナJSONログ、ADOT→X-Ray、ALBのS3ログ、CloudFront標準ログv2、WAFのマスク、VPC Flow Logs、RDS PostgreSQLログ、組織CloudTrail、Config配送 | [Task](model/resources/RES-TASK-API.yaml)、[CloudFront配送](model/resources/RES-CDN-LOG-DELIVERY.yaml)、[Trail](model/resources/RES-TRAIL.yaml) |
| 監視 | Syntheticsの公開入口監視、ALB遅延・5xx率、ECSタスク数、DB CPU/メモリ/接続/遅延、Valkeyメモリ/eviction、Queue滞留/DLQ、検査・監査Outboxの独自指標。指標・Dimensions・評価・閾値・欠測・SNS通知を定義 | [5xx率](model/resources/ALM-ERROR.yaml)、[滞留](model/resources/ALM-QUEUE.yaml)、[通知](model/resources/RES-ALERTS.yaml) |
| セキュリティ運用 | 本番と委任管理側のGuardDuty・Security Hub、Configと規則、Inspector/ECR拡張検査。停止・異常FindingをEventBridgeから通知 | [本番Detector](model/resources/RES-GD-PROD.yaml)、[サービス設定](model/foundation/RES-INSPECTOR.yaml) |
| 復旧 | Aurora・添付・業務監査をAWS Backupで日次保護。35日保持、別Account大阪へ90日コピー。Vault Policy・Key Policy・実行Roleを定義。四半期ごとの実復元を手順化 | [Backup Plan](model/resources/BKP-PORTAL.yaml)、[保護対象](model/resources/RES-BACKUP-SELECTION.yaml)、[復元手順](model/operations.yaml) |
| 配備・費用 | ECR immutable tags、CodeBuildで試験とARM64ビルド、CodePipelineで手動承認とECS配備、失敗通知。Budgetと費用異常通知 | [Pipeline](model/resources/RES-PIPELINE.yaml)、[Build](model/resources/RES-BUILD.yaml)、[Budget](model/resources/RES-BUDGET.yaml) |

## つながりの意味

CloudFrontのBehaviorは`TargetOriginId.ref`でOriginを選び、Originは`VpcOriginId.ref`でVPC Originを選びます。
VPC OriginはALBを参照し、ListenerはALBを、RuleはListenerとTarget Groupを参照します。ECS Serviceの`LoadBalancers`が同じTarget Groupへコンテナを登録します。
全体図はこの経路を辿って中間要素をまとめ、詳細図は中間要素も表示します。原本への来歴は残ります。

DBやセッションの利用は、Taskへ渡す`APP_CONFIG`の参照先、`model/apps/`の接続設定から辿ります。
SGは通信の許可、IAMはAPI操作の許可です。これらの許可だけをアプリの接続実態として扱いません。
処理シナリオに書く順序・失敗処理も、AWSの転送設定とは別の意味です。

## 採用条件と配備前に埋める事項

- ALB→コンテナはプライベートSubnetのHTTP:8080です。全区間TLSが要件なら、コンテナ終端・TG・ヘルスチェックまで一緒に設計変更します。
- Valkey障害時は認証を閉じ、失われたセッションは再認証します。Cookie属性だけでCSRF対策が完成した扱いにせず、BFFのtoken検証も設定しています。
- DBユーザーと権限・DDL・Outboxの初期化、Secret更新とコンテナ再配備、企業IdPのMFA・退職時失効は、アプリ／運用の実装工程です。ローテーションLambdaは同梱していません。
- Syntheticsは公開入口の200応答を確認する設定です。企業IdPを通る業務トランザクションの合成試験は追加が必要です。
- 委任管理者の登録、組織のtrusted access、BackupのクロスAccount機能、SES本番利用とDNS検証、CodeConnections承認は実環境の共通基盤で確認します。
- 日次コピーではコピー完了遅延が実効RPOに加わります。目標RPO24時間・RTO8時間の達成を仮定せず、復元・添付versionの突合・切替の実測で判定します。
- stg/devはAccountと環境の境界だけを定義しています。本番設定のoverlay解決・各環境の資源生成は未実装です。
- 料金見積もりではありません。Budgetの1500 USDは通知用の例示値です。採用版・リージョン・AZ対応・クォータは配備先で再確認します。

採用値の根拠は[一次資料](SOURCES.md)、生成工程の未実装範囲は[生成契約](GENERATION.md)に記載しています。
