# AGENTS.md

AIコーディングエージェント向けの作業ガイドは [CLAUDE.md](CLAUDE.md) に集約しています（ツールを問わず同じ内容を参照してください）。

- プロジェクト概要・コマンド・落とし穴: [CLAUDE.md](CLAUDE.md)
- 機能一覧と実装状況: [docs/FEATURES.md](docs/FEATURES.md)
- クラス設計・設計方針: [docs/architecture/](docs/architecture/README.md)
- 開発規約（ドキュメント更新規約を含む）: [docs/CONVENTIONS.md](docs/CONVENTIONS.md)

## この Windows 環境（どのエージェントも）

- **Python** は `py -3` で呼ぶ（`python` は Microsoft Store のスタブで動かない）。
- **CLI の出力を機械で読むとき**は、Gradle の呼び出し側に UTF-8 を指定する。Gradle が出力を端末の文字コード（MS932）で返すため、そのままファイルに落とすと文字化けする。

  ```bash
  GRADLE_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8" ./gradlew runCli -q --args="validate samples/orders" > out.txt
  ```

  PowerShell なら同じプロセスで `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` と `$env:GRADLE_OPTS = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"` を設定してから `.\gradlew.bat` を呼ぶ（実測済み）。人が端末で読むだけなら指定しない（端末が MS932 のため）。
- **テストを絞る**: `./gradlew test --tests '*WorkspaceInitTest*'`。一致するテストが無いモジュールは失敗にしない設定なので、ルートから回せる。
- **画面の確認**は `./gradlew hotRun --args="labs/sysdev-template"` を背景で起動し、Hot Reload の MCP（`.mcp.json`）の `status` が connected になってから行う。先に `status` を見ること。オーナーが別セッションで起動したアプリにつながっていることがあり、その場合は `reload` で自分の変更を差し込む。スクリーンショットは手前のウィンドウが写るので、確認は `get_semantic_tree` の text / selected / toggleableState で行い、触った状態（畳み・チェック）は終わったら戻す。
- **コミット前のフックと CI**。クローンごとに 1 回 `git config core.hooksPath scripts/git-hooks` を実行する。コミットのたびに docs-lint とソースの規則・文書の参照のテストだけが回る（30 秒ほど）。全モジュールの単体テストは GitHub Actions（`.github/workflows/test.yml`）が push ごとに回す。手元でも `./gradlew test` をコミット前に通す決まりは変わらない。
- **`labs/` の未コミット差分に触れない**。オーナーが別セッションで整備していることがある。自分の変更と混ぜず、コミットにも含めない。
- **一時ファイル**はリポジトリの `scratchpad/`（`.gitignore` 済み）か、エージェントの一時領域に置く。背景で動かすコマンドの出力先は先に作る。長いスクリプトや文書は、シェルのヒアドキュメントに入れず、ファイルに書いてから実行する（長文のヒアドキュメントは失敗することがある）。

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
