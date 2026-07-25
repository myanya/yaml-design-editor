# CLAUDE.md

Kotlin + Compose for Desktop 製のスキーマ駆動YAMLエディタ。ワークスペース内のYAML文書を、`_schemas/*.yaml` のスキーマに基づくフォーム／ソース／差分で編集し、ID索引・参照解決・検証・AI生成支援を提供する。

## コマンド

```bash
./gradlew test                              # 単体テスト（コミット前に必ず実行）
./gradlew run                               # スタート画面で起動（履歴からワークスペース選択）
./gradlew run --args="/path/to/workspace"   # ワークスペース指定で起動
./gradlew runCli -q --args="help"           # ヘッドレスCLI（探索・検索・パッチ・検証・エージェント実行・評価・入出力変換。README §16）
./gradlew packageDistributionForCurrentOS   # 配布物作成
```

要件: JDK 17+。GUIアプリのため `run` は対話的に閉じる必要がある（検証はテスト・ビルド・`runCli` で行う）。

## ドキュメント体系（必ず参照・更新する）

| ドキュメント | 役割 |
|---|---|
| [README.md](README.md) | 利用者マニュアル（操作・スキーマ定義リファレンス・制約） |
| [docs/FEATURES.md](docs/FEATURES.md) | 機能台帳: 全機能の一覧・実装状況・テスト有無・未実装項目 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | クラス設計・設計方針・データフロー・拡張ポイント |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | 開発規約（スタイル・Compose作法・テスト・ドキュメント更新規約） |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 将来構想（サンドボックス型AI実行環境化）とフェーズ別ロードマップ |

**作業のワークフロー:**

1. **着手前**: docs/FEATURES.md で対象機能の位置（実装ファイル・テスト有無）を確認し、docs/ARCHITECTURE.md の該当する設計方針（特に §4）を読む。
2. **実装中**: docs/CONVENTIONS.md に従う。重要: UI文言・コメント・エラーメッセージは日本語／純粋ロジック層に Compose を持ち込まない／ツリー変更には `markEdited()`。
3. **完了前**: `./gradlew test` を通し、変更内容に応じてドキュメントを更新する（対応表は docs/CONVENTIONS.md §6）。機能を追加・変更したのにドキュメントが差分に含まれない場合は、更新漏れを疑うこと。`/sync-docs` スキルでチェックできる。

## 落とし穴（要注意）

- `YNode` は Compose の State ではない。ビューは `doc.version` / `state.indexVersion` を読んで再コンポーズを購読する。読み忘れると画面が更新されない。
- `IdIndex` はディスク基準。未保存編集の参照解決は `DocScanner.scan` によるローカルIDを重ねて判定する二層構造（docs/ARCHITECTURE.md §4.5）。更新は変更ファイル単位の増分（`update`）だが、**スキーマが変わったら必ず `rebuild`**（走査規則が変わるため）。保存・外部変更でのこの振り分けは `Workspace.noteDiskChanges` に一元化されている（R9-02）——AppState 側に振り分けを書かない。
- YAML はコメント・表記スタイルを保持する（R8-01）。ノードを**置換**する経路では `inheritMetaFrom` で引き継ぐ（`Slot.set` / `Patcher` は対応済み）。構造の比較・派生成果物の出力は `dump` ではなく `YamlIO.dumpPlain`（コメント・スタイルで揺れるため。docs/ARCHITECTURE.md §4.17）。複数ドキュメントYAMLの書込は `OpenDocument`（`parseAll`/`dumpAll`）に閉じ、読み取り専用経路だけ `parseFirst` を使う。
- スキーマの `FieldDef` を使う際は必ず `schema.resolve(field)` を通す（`$ref` 解決）。object の子フィールドの列挙は必ず `schema.effectiveFields(fields, node)` を通す（enum の `cases` 合成。docs/ARCHITECTURE.md §4.7）。
- フィールド型を追加する場合の変更箇所は docs/ARCHITECTURE.md §6 に列挙されている（4箇所以上に分岐が必要）。
- フィールドの実効必須判定は素の `field.required` を見ず、必ず `schema.requiredAt(field, phaseIndex)` を通す（`requiredFrom` によるフェーズ累積必須を見落とす。`phaseIndex` は `schema.currentPhaseIndex(root)`。docs/ARCHITECTURE.md §4.15）。カバレッジの `activeFrom` も同様に `coverageStatuses` の `definingRoot` 引数を省略しない。
- 要素の1行要約（タイトル・サブタイトル・畳み込みヘッダ）は `schema/Display` に一元化されている（R10-01）。一覧・候補・要約の表示に独自のタイトル抽出を書かない。`IdEntry.title` は表示解決の結果（`role` ＞ 予約語）。enum のリッチ選択肢（`{value, label, description}`）の label / description は表示専用で、検証・`cases`・AI制約は常に `value`（docs/ARCHITECTURE.md §4.22）。
- フォーム系の新しい編集UIは `FormCtx` を通し、閲覧専用モード（`ctx.readOnly`）の表示分岐を入れる。`readOnly` 中の `markEdited` / スカラー書込は check で拒否される。
- エージェントの編集は必ず `agent/Staging`（オーバーレイ）経由。原本への書込は人間の承認（`Workspace.apply`）のみ。書込権限・予約ディレクトリ禁止は `AgentToolbox` が強制する（docs/ARCHITECTURE.md §4.10）。
- `_runs/` は索引対象外の予約ディレクトリ。予約ディレクトリの定数は `SchemaLoader` にある（`_evals` を含む）。
- 定義文書（`_agents`/`_activities`/`_evals`/`_skills`/`_views`）への書込は `meta: true` のアクティビティのみ。`meta` かつ `approval: auto` はロード時に定義エラー。`_schemas`/`_runs` は meta でも書込不可。
- ビュー（`_views`）はデータを持たない射影。セルは原本座標 `(file, YPath)` に解決され、UIは docCache ツリー直接編集、エージェントは `read_view`→既存 `apply_patch`。ビュー専用の書込経路を作らない（docs/ARCHITECTURE.md §4.14）。定義項目の追加は `core/Views.kt` の `parseView` と `BuiltinSchemas` の `builtin-view` を対で更新する。
- skill は宣言型（import / export）が既定。コード実行skill（kind: run）は `skill/Runner.kt` の `Approval`（人間の事前承認。定義変更で失効）経由でしか実行できず、エージェント・自動実行からは起動不可（docs/ARCHITECTURE.md §4.21）。インポートは直接書かず変更セット（`Workspace.apply`）経由、エクスポートのYAML出力は禁止（原本主義）。入出力形式の追加は `skill/Tables.kt` に閉じる。
- AI呼び出しのHTTP送信は `ai/Http.kt`（`AiHttp.post`）に一元化されている（プロキシ・Basic認証）。OpenAI互換の接続先（Azure・ベースURL上書き）は `ai/Connection.kt` が解決する。独自に `HttpClient` を作らない（docs/ARCHITECTURE.md §4.18）。
- 評価（`EvalRunner`）はワークスペースの一時コピー上で実行する。原本に書くのはレポート（`_runs/eval-*.yaml`）だけ。実行記録の形式を変えたら `RunLog` と `RunMetrics` の両方を直す。
- カーネル（`AgentKernel`）のテストは疑似セッション注入で書く（`KernelTest` 参照）。実APIを叩くテストは書かない。
- 自動化エンジン（`AutomationEngine`）のテストは `tick(now)` / `processOnce()` を直接呼ぶ（タイマー・sleep を使わない。`AutomationTest` 参照）。自動適用後はスタンプ基準を更新し、自己変更で on-change を再発火させないこと。
