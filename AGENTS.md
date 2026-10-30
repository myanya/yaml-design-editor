# AGENTS.md

AI コーディングエージェント向けの作業ガイド。どのエージェントもこの 1 枚が正で、Claude Code は [CLAUDE.md](CLAUDE.md) から取り込んで読む。

Kotlin + Compose for Desktop 製のスキーマ駆動 YAML エディタ。ワークスペース内の YAML 文書を `_schemas/*.yaml` のスキーマに基づくフォーム／ソース／差分で編集し、ID 索引・参照解決・検証・AI 生成支援を提供する。

## コマンド

```bash
./gradlew test                              # 単体テスト（コミット前に必ず実行）
./gradlew test --tests '*WorkspaceInitTest*'  # 絞って実行（一致しないモジュールは失敗にしない）
git config core.hooksPath scripts/git-hooks   # コミット前フック（クローンごとに 1 回。docs-lint と規則・参照のテスト。全テストは GitHub Actions）
./gradlew fileTreeBenchmark                 # 任意: 一時領域で1000・10万ファイルの一覧・検索を計測
./gradlew run                               # スタート画面で起動（履歴からワークスペース選択）
./gradlew run --args="/path/to/workspace"   # ワークスペース指定で起動
./gradlew hotRun                            # 開発時: Compose Hot Reload 付きで起動（保存で画面に反映。JBR 21 は自動取得）
./gradlew runCli -q --args="help"           # ヘッドレス CLI（docs/manual/cli.md）
./gradlew runCli -q --args="init my-ws --template issues"  # 新しいワークスペースを作る（テンプレートは templates/。引数なしで一覧）
./gradlew runCli -q --args="version"        # バージョン表示（外部＝appVersion・内部＝yyyymmdd.ビルド番号）
./gradlew packageDistributionForCurrentOS   # 配布物作成
./gradlew verifyWindowsDistribution --no-parallel # Windows同梱ランタイム・主要機能・ライセンスの検査
./gradlew windowsRelease --no-parallel       # 全テスト＋Windows ZIP・SHA-256・リリース本文（docs/releasing.md）
scripts/docs-lint.sh                        # 文書の形の機械チェック
```

要件: ビルドと実行は JDK 21（toolchain。無ければ Gradle が自動で取得する。`./gradlew` 自体は JDK 17 以上で動く）。初回ビルドは等幅フォント Cica の取得でネットワークが要る。GUI アプリのため `run` / `hotRun` は対話的に閉じる必要がある（検証はテスト・ビルド・`runCli` で行い、画面の確認は `.mcp.json` の Hot Reload MCP サーバで行い、手順は skill `ui-check`＝`.claude/skills/ui-check/SKILL.md`）。
環境の癖（Python の呼び方・CLI 出力の文字コード・画面確認の手順・`labs/` と一時ファイルの扱い）は下の「この Windows 環境」にある。

## 文書

- 役割と更新のタイミングは [docs/README.md](docs/README.md) の表が正。規則は [docs/CONVENTIONS.md](docs/CONVENTIONS.md)「ドキュメント更新規約」。
- 着手前に読むのは [docs/FEATURES.md](docs/FEATURES.md) の該当行と [docs/architecture/](docs/architecture/README.md) の該当する主題。
- このプロジェクトで決めた語（定義体・保有・受け手・射影など）は [docs/GLOSSARY.md](docs/GLOSSARY.md) で引く。
- 記録（[docs/archive/](docs/archive/README.md)）は経緯を調べるときだけ読む。

## ワークフロー

1. **着手前**: FEATURES で対象機能の実装ファイルとテストを引き、architecture/ の該当する主題（部品・流れ・規律・拡張ポイント）を読む。
2. **実装中**: CONVENTIONS に従う。UI 文言・コメント・エラーメッセージは日本語。純粋ロジック層に Compose を持ち込まない。ツリー変更には `markEdited()`。
3. **完了前**: `./gradlew test` を通し、docs/README.md の表に従って文書を更新し、完了したものは archive へ移す。skill `sync-docs`（[.claude/skills/sync-docs/SKILL.md](.claude/skills/sync-docs/SKILL.md)）と `scripts/docs-lint.sh` で確かめる。
4. **完了報告**: 機能を追加・変更したら**試し方**（操作手順＋見てほしい観点）を必ず含める。本題と異なる気になりポイントは直しに行かず [docs/BACKLOG.md](docs/BACKLOG.md) へ即時登録する。

## 規約の要点

- 依存方向は `yaml-core ← semantic-core ← workspace-core ← app-core ← agent ← desktop`（cli は app-core・agent・拡張まで・拡張 format-office は app-core まで）。コアからアプリ・Compose・`Path` へ逆依存しない。root にソースを置かない（規則テストと入口をまたぐテストは repo-tests）。ADR（[docs/decisions/](docs/decisions/README.md)）は規約と同格。
- パッケージは `dev.toros.yde.<モジュール>.*`。文書とコメントでは接頭辞を省いた短い形（`schema.FieldDef`・`validate/Validator.kt`）で書く。
- 可変 `YNode` ＋ copy-on-write の規律。Undo・履歴は workspace-core の単一所有。表記から意味を決めない。
- 移動であって複製ではない。`test-fixtures/` は読み取り専用で、変更を伴うテストは一時ディレクトリへコピーして使う。
- 既存挙動を変えるときはコミットメッセージに書く。設計の決定を変えるなら新しい ADR で上書きを宣言する。
- 新しい決定論的機能は Core Calculus を一段挟む（CONVENTIONS §9）。検査はまず YAML の定義体で、YAML の組み立てはまず変換定義で書けるかを考える。

## 場所ごとの決まり

そのモジュールの中でだけ起きる間違いは、モジュールの `AGENTS.md` に書く。ファイルを書く・直す前に読む（Claude Code は同じ場所の `CLAUDE.md` から自動で読む。ほかのエージェントは自分で開く）。

| 場所 | 中身 |
|---|---|
| [desktop/AGENTS.md](desktop/AGENTS.md) | 画面: 購読・索引の読み方・フォームと浮いたパネル・見え方の覚え・ソースとの同期 |
| [semantic-core/AGENTS.md](semantic-core/AGENTS.md) | 意味の計算: 構造スキーマの導出・共有の入力の外し方・object の fields とエンティティ型 |

## 落とし穴

説明は docs/architecture/ の各ファイルの「規律」にある。機械で確かめられるものは repo-tests の `SourceRulesTest` が守る（落ちたらメッセージの「説明」の見出しを読む。規則を足すなら `SourceRules` の表に 1 つ） → architecture/README.md「ソース規則テスト」

- file 型の値（ファイルの場所）の書き方は `FilePath` で判定する（`./` や拡張子や `\` の判定を直書きしない）。存在と許可は `CodedDocumentChecks.fileIssues` と `.yde/workspace.yaml` の `files:`（`FilePolicy`）で判定する → architecture/schema.md「規律」・architecture/checks.md「文書 1 つの検証」
- ディスク変更の取り込みの振り分けは `Workspace.noteDiskChanges` だけ。ディスクを映すセッションと編集用セッションは 1 つにまとめない → architecture/index-references.md「索引の二層」
- 場所はワークスペース相対の `DocumentId`。`DocumentId == Path` は常に偽。`Validator` の `loader` を省くと他の文書を読まない → architecture/index-references.md「場所は文書ID」
- ノードの置換は `inheritMetaFrom` で表記を引き継ぐ。構造の比較・派生出力は `YamlIO.dumpPlain`。複数文書は `OpenDocument` に閉じる → architecture/yaml-model.md「コメントと表記の保持」
- スキーマを読むのはカーネル（`WorkspaceReader`）だけ。`SchemaDef` は薄い窓口で data class ではない。表示のヒントは `FieldHints` に置き検証から読まない → architecture/schema.md「$ref と実効フィールド」
- `FieldDef` は `schema.resolve`、子フィールドは `schema.effectiveFields`、必須は `schema.requiredAt(field, phaseIndex)` を通す → architecture/schema.md「$ref と実効フィールド」・architecture/schema.md「フェーズ」
- 保存時に自動で入る値（更新日・版）は `SemanticAutoValues` の計画をセッションの `applyAutoValues`（保存の直前）か `ChangeSetApplier`（検証の前）で当てる。保存・適用の経路に日付や版の書き込みを直書きしない。「今」は引数で渡す → architecture/auto-values.md「流れ」
- 値の式は項目の印 `expression: true` だけが決める。読み取り `ExpressionFields`・評価 `SemanticValues`。独自の `{…}` 穴埋めを書かない。`[*]` を含むパスは `PathPattern`（`-1` 置換を書かない）。値は項で、集計は配列を受け取る関数、集合から値へは `all` だけ。順序の種類（数値・日付など）は読んだ項目の型から（環境の `fieldAt`。表記から決めない） → architecture/schema.md「値の式」
- 要素の 1 行要約は `schema/Display` に一元化。enum の label は表示専用で、検証と AI 制約は `value` → architecture/index-references.md「表示解決」
- エージェントの編集は `agent/run/Staging`（app-core の作業コピー `WorkingCopy`）経由。ツールの本体は app-core の `tools/Toolset`（入口は包むだけ）。適用の本体はカーネルの `ChangeSetApplier` で、app-core と入口に適用規則を書かない → architecture/workspace.md「変更セットの適用」
- 検査はまず定義体（`builtin-definitions/*.yaml`）とメタスキーマで書く。検証の組み立てと配り方はカーネルにあり、app-core の `Validator` に検査を書き足さない → architecture/checks.md「定義層の 3 段」・architecture/checks.md「文書 1 つの検証」
- 検査の結果は 4 つ（違反・充足・保留・対象外）。「指摘が出ない＝充足」と判定しない。同じ指摘かは `IssueSource.identity` で見る → architecture/checks.md「4 つの結果」
- 全体を回す検証は `shareEvaluations = true` を渡す。ただし索引に重ねていない内容の検証では渡さない → architecture/checks.md「評価は 1 回」
- クエリの線は `graph.ownRefs`（直接書いた参照）で辿る。判定に `graph.outgoingRefs` を使わない → architecture/graph-query.md「線の意味」
- 保有（入れ子で書かれた要素）の線は `graph.holdingsOf / holdersOf`。名前は走査が決める（`ScannedId.holderKey / holderRelation`）。`ScannedRef` として索引の参照に混ぜない → architecture/graph-query.md「線の意味」
- 文書の新規作成は `DocumentSession.createDocument` の単一経路（作った文書を開くのは `AppState` の入口）。`Files.writeString` ＋ `reload()` では作成の Undo を記録できない → architecture/editing-history.md「文書の作成と削除」
- 新しいワークスペースの初期化だけは例外で、開く前に `WorkspaceInit` が書く（Undo なし・既存は上書きしない）。計画は `WorkspaceInit` の 1 つで、画面（`InitWizardHost`）と CLI `init` に組み立てを書かない。テンプレートは `templates/` のファイル（Kotlin に埋め込まない）で、答えからの組み立ては変換定義のひな型 → architecture/workspace.md「ワークスペースの初期化」
- 配置判定は `WorkspaceLayout` に一元化。`_schemas/` にはメタスキーマだけ。定義文書への書込は `meta: true` のアクティビティのみ → architecture/workspace.md「ワークスペース構造と layout」・architecture/schema.md「推測・メタスキーマ・派生」・architecture/agents.md「エージェント実行と Staging」
- ビューは射影で、専用の書込経路を作らない → architecture/views.md「ビュー機構」
- レポート（YAML から文字へ）は 2 段＝変換（`$view` でビューの行）→ `TextTemplate`（穴・繰り返し・条件だけ）。文字の組み立てを Kotlin に書かず、出力は派生物（`output`。YAML と `.yde/` の下には書かない） → architecture/transform.md「レポート」
- チャート（`kind: chart`）に集計を書かない（集計は元の表）。軸を値の式で広げたクロス集計は `lower` で列が決まらないので、ヘッダと書き出しの見出しは `view.columns` でなく `columnsOf(view, rows)` から取る。図の部品（Vico）は `ui/view/Charts.kt` に閉じる → architecture/views.md「ビュー機構」
- カーネルのテストは疑似セッション注入、自動化のテストは `tick(now)` を直接呼ぶ。実 API を叩かない → architecture/agents.md「エージェント実行と Staging」・architecture/agents.md「自動実行」
- 型はヒントと基底の種類で判定する（`field.multiline`・`field.placeholder`・`field.type == FieldType.DATE`）。`FieldType` は基底の種類（表現の基底と、値を持つ意味の基底型＝`ValueSemantics`）だけで、組込みの定義体（text / line / word）は `builtin-types/` の YAML。使う側の決まりは狭めるだけ → architecture/schema.md「型の連鎖と狭める規則」
- スキーマの図（レンズの「型」の段）の導出は `SemanticSchemaGraph` の 1 つで、レンズ・CLI でスキーマの木を歩き直さない。矢印の向きのような見え方は `_views` の `kind: lens`（`VirtualLensDef`）に置き、スキーマ・レジストリに足さない（反転は `LensGraphs` の 1 か所） → architecture/graph-query.md「スキーマレンズ（型の段）」・architecture/views.md「規律」
- 操作（型の `methods`・スキーマの `actions`）の計画は `ActionPlanner` の 1 つで、結果は変更セット → `ChangeSetApplier`。入口（CLI・画面・エージェント）に計画や適用の規則を書かない。型の操作は受け手の部分木に閉じる。受け手のキーは `target`（`on` は YAML が真偽値に読む）。閲覧専用が封じるのは編集で、操作は通す → architecture/actions.md「規律」
- スキーマ語彙を増やすときはメタスキーマと設計UIも確認する。入力欄だけでなく構成ツリーの子項目・選択・連動も確認する → CONVENTIONS「スキーマ語彙と編集UIの同期」。

## この Windows 環境（どのエージェントも）

- **Python** は `py -3` で呼ぶ（`python` は Microsoft Store のスタブで動かない）。
- **CLI の出力を機械で読むとき**は、Gradle の呼び出し側に UTF-8 を指定する。Gradle が出力を端末の文字コード（MS932）で返すため、そのままファイルに落とすと文字化けする。

  ```bash
  GRADLE_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8" ./gradlew runCli -q --args="validate samples/orders" > out.txt
  ```

  PowerShell なら同じプロセスで `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` と `$env:GRADLE_OPTS = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"` を設定してから `.\gradlew.bat` を呼ぶ（実測済み）。人が端末で読むだけなら指定しない（端末が MS932 のため）。
- **テストを絞る**: `./gradlew test --tests '*WorkspaceInitTest*'`。一致するテストが無いモジュールは失敗にしない設定なので、ルートから回せる。
- **画面の確認**は [.claude/skills/ui-check/SKILL.md](.claude/skills/ui-check/SKILL.md) の手順で行う（Claude Code では skill `ui-check`。ほかのエージェントも同じ文書を読む）。ワークスペースを写して hotRun で起動し、Hot Reload の MCP（`.mcp.json`）で画面の木を読んで操作し、最後にアプリとこのセッションの MCP サーバを止める（`stop.ps1 -Mcp`）。MCP サーバは `scripts/hot-mcp-server.ps1` から起動し、セッションが終わると一緒に止まる。
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
