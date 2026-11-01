# ローカル接続の基盤

Ktor/CIO と公式 MCP Kotlin SDK を応用層から隔離する組込み JVM モジュール。
設計契約は [ローカル接続の ADR](../docs/decisions/0016-local-connections-and-shared-host.md)、現在の構成は [ローカル接続の設計](../docs/architecture/local-connections.md)にある。

初期値はすべて OFF。デスクトップの設定から loopback 待受を明示起動し、認証済み API で参照・提案・直接確定・定義済み操作を行う。
`server.client` は CLI の明示接続用で、通信だけを担い、待受を起動しない。MCP ツール・Web 画面の業務経路は未接続。
モジュールがクラスパスにあるだけではポートを開かない。

検査は `./gradlew :local-server:test`。
HTTP と MCP の契約はインメモリで、CIO の起動・停止・ポート解放と CLI 用クライアントの通信は一時的な loopback ポートで確かめる。
`LocalServerDistributionSmoke` はテストにだけ置き、`verifyWindowsDistribution` が同梱 JAR と縮小ランタイムでも実行する。
検査用の経路・ツールは製品へ含めない。

依存の宣言は `gradle/libs.versions.toml`、解決結果は `gradle.lockfile`。
依存を変更したときだけ `./gradlew :local-server:test :local-server:dependencies --configuration runtimeClasspath --write-locks` で更新し、差分を確認する。
その後に `./gradlew test` で既存機能への影響も検査する。
Windows 配布を検査する場合は `verifyWindowsDistribution --no-parallel` も通す。
