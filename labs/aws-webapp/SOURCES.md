# AWS設計の根拠資料

確認基準日: 2026-11-04。AWSの制限・対応リージョン・料金・サービス名は変更されるため、実配備の前に再確認します。
資料の説明をそのまま稼働保証へ置き換えず、この題材で採用した条件をYAMLの補足と設計判断へ記載しています。

AWS公式[Resource Specification](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/cfn-resource-specification.html)の版270.0.0を取得し、採用した87資源型のプロパティ名・構造型・必須・GetAtt属性を照合しています。
検査用の抜粋は[reference/cloudformation-spec.json](reference/cloudformation-spec.json)、取得URL・基準日・SHA-256は[source.json](reference/source.json)です。
サービスの全値制約、リージョンでの提供状況、IAMの実効権限はこの仕様ファイルだけでは検証できません。

| 今回具体化した設定 | 一次資料 |
|---|---|
| ListenerのTLS・既定動作 | [Listener](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-elasticloadbalancingv2-listener.html) |
| 条件と転送先 | [ListenerRule](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-elasticloadbalancingv2-listenerrule.html) |
| ポート・ヘルスチェック | [TargetGroup](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-elasticloadbalancingv2-targetgroup.html) |
| ECSのTG登録 | [Service LoadBalancer](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-ecs-service-loadbalancer.html) |
| Cognito IdPのRef返値 | [UserPoolIdentityProvider](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-cognito-userpoolidentityprovider.html) |
| CloudFront標準ログv2の配送資源 | [DeliverySource](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-logs-deliverysource.html) |
| Auroraの採用エンジン版 | [Aurora PostgreSQL updates](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraPostgreSQLReleaseNotes/AuroraPostgreSQL.Updates.html) |
| Valkeyの採用エンジン版 | [Engine versions](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/engine-versions.html) |
| Canaryのruntime | [Node.js/Puppeteer runtimes](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Synthetics_Library_nodejs_puppeteer.html) |
| BackupのAccount間コピーと鍵・Role | [Cross-account backup](https://docs.aws.amazon.com/aws-backup/latest/devguide/create-cross-account-backup.html) |
| コピーの資源・Region別対応 | [Feature availability](https://docs.aws.amazon.com/aws-backup/latest/devguide/backup-feature-availability.html) |

| 設計箇所 | AWSの一次資料 | このlabへの反映 |
|---|---|---|
| CloudFront VPC Origin | [VPC origins](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html) | 内部ALB、IGWアタッチ、サービス管理SG、対応AZの確認。東京のapne1-az3を除外 |
| VPC OriginのIaC | [AWS::CloudFront::VpcOrigin](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-cloudfront-vpcorigin.html) | CFn型への対応を生成入力に明記 |
| 証明書 | [HTTPS requirements](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/cnames-and-https-requirements.html) | CloudFront用ACMはus-east-1、ALB用は東京で分離 |
| WAFの配備先 | [AWS::WAFv2::WebACL](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-wafv2-webacl.html) | CLOUDFRONTのScopeはus-east-1に作成 |
| 動的要求のキャッシュ | [Cache policies](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/cache-key-understand-cache-policy.html) | API/認証経路のTTLを0。Cookieを転送し、個人別応答を共有しない |
| S3の非公開配信 | [Restrict access to an S3 origin](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html) | S3 REST origin＋OAC、Bucket/Key policyをDistributionに限定 |
| ECSのAZ分散 | [Availability Zone rebalancing](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-rebalancing.html) | AZ再分散を明示し、希望台数だけで均等配置を保証したことにしない |
| Fargateネットワーク | [Task networking](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/fargate-task-networking.html) | Public IPなし、Subnet/SG/Endpoint/NATを設計 |
| ECR取得経路 | [ECR VPC endpoints](https://docs.aws.amazon.com/AmazonECR/latest/userguide/vpc-endpoints.html) | ecr.api/ecr.dkr/S3、初回pull等の外部経路を確認 |
| Aurora HA | [High availability](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Concepts.AuroraHighAvailability.html) | writer/readerを別AZへ。初期役割と切替後の役割を区別 |
| Valkeyの切替 | [Multi-AZ and automatic failover](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/AutoFailover.html) | replica、Multi-AZ、切替、直近更新喪失の可能性。失効を復活させない設計 |
| 企業IdP | [SAML identity providers](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-saml-idp.html) | Cognitoと企業IdPの責務、MFA/失効の連携契約 |
| キューの重複 | [At-least-once delivery](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues-at-least-once-delivery.html) | 冪等キー、Outbox、visibility延長、DLQと段階的redrive |
| 添付の検査 | [Malware Protection for S3](https://docs.aws.amazon.com/guardduty/latest/ug/gdu-malware-protection-s3.html) | 検査結果がクリーンの版だけ昇格。検査機能だけでダウンロード拒否が完成したと扱わない |
| ALBログ | [Enable access logs](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/enable-access-logging.html) | 従来のS3配送先は同一リージョン・SSE-S3。この例では他のS3と暗号化を区別 |
| 分散トレース | [Application Signals for ECS](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Application-Signals-Enable-ECSMain.html) | ADOT/権限/収集範囲の実装を配備工程に含める |
| 合成監視 | [CloudWatch Synthetics](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Synthetics_Canaries.html) | 利用者経路と監視自体の欠測を確認 |
| 組織セキュリティ | [AWS Security Reference Architecture](https://docs.aws.amazon.com/prescriptive-guidance/latest/security-reference-architecture/phases.html) | 管理・セキュリティ・ログ・本番・バックアップの責務分離 |
| 分離バックアップ | [Cross-account backup](https://docs.aws.amazon.com/aws-backup/latest/devguide/create-cross-account-backup.html) | Organizations・CMK・両側の権限を前提にする |
| コピー対応 | [Feature availability](https://docs.aws.amazon.com/aws-backup/latest/devguide/backup-feature-availability.html) | 資源ごとに同時コピー可否を確認し、必要なら二段階コピー |
| 復元試験 | [Restore testing](https://docs.aws.amazon.com/aws-backup/latest/devguide/restore-testing.html) | 復元可能性、業務整合、RTO/RPOを実測 |
| 配備の切戻し | [ECS deployment circuit breaker](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/deployment-circuit-breaker.html) | circuit breakerとalarm、DB migrationの別扱い |
| CFnの出力形 | [Resources section](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/resources-section-structure.html) | Logical IDをキーにするmapと、型ごとのPropertiesへの展開が必要 |
| 生成結果の検査 | [cfn-lint](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cfn-lint.html) | 生成後の静的検査を実配備・運用試験と分ける |

## 採用しなかった選択肢

- EKSは、Kubernetesを必要としない業務アプリに対して運用範囲が広いため初期構成では採用していません。
- RDS Proxy、Aurora Serverless v2、Global Database、3AZ化は、負荷と復旧目標の測定後に比較する候補です。
- インターネット公開ALBは採用せず、CloudFront VPC Originに対応する機能・リージョンの範囲を前提にしています。
- Network Firewallや外向きプロキシは、FQDN制限等の契約が必要になった場合の追加候補です。SGだけで外向きFQDN制限を実現したことにはしません。

エンジン版とruntimeは資料上の提供を確認した設計値です。配備Accountの提供版・互換性は別途確認します。
見積は実際の容量・要求数・ログ量・保持期間で作成し、Budgetの通知値を見積額として扱いません。
