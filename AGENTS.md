# AGENTS.md

AIコーディングエージェント向けの作業ガイドは [CLAUDE.md](CLAUDE.md) に集約しています（ツールを問わず同じ内容を参照してください）。

- プロジェクト概要・コマンド・落とし穴: [CLAUDE.md](CLAUDE.md)
- 機能一覧と実装状況: [docs/FEATURES.md](docs/FEATURES.md)
- クラス設計・設計方針: [docs/architecture/](docs/architecture/README.md)
- 開発規約（ドキュメント更新規約を含む）: [docs/CONVENTIONS.md](docs/CONVENTIONS.md)

## Codex からのビルド（この Windows 環境）

Codex から Gradle を実行するときは、既存のキャッシュを明示してください。
Java の `user.home` がサンドボックス用ユーザーを指すことがあり、通常のユーザーのキャッシュを自動では参照しないためです。

リポジトリのルートで、同じ PowerShell プロセス内で設定して実行します。

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat build --no-daemon --console=plain
```

- `test` や `runCli` の実行にも同じキャッシュ指定を使ってください。
- キャッシュへの書き込みや依存関係の取得がサンドボックスで制限される場合は、実行ツールの権限昇格（`require_escalated`）を使用してください。
- 権限昇格は実行環境の承認手続きに従い、必要な Gradle コマンドに限定してください。
- 空の `.gradle-user-home` へ切り替えて `--offline` で実行し、その失敗をプロジェクトの依存関係不足と判断しないでください。
- この環境では上記の方法で `build` と `runCli` の実行を確認済みです。別の環境では、その環境の既存キャッシュの場所に読み替えてください。
