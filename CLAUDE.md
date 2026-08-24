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
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | 開発規約（スタイル・Compose作法・テスト・ドキュメント更新規約・UXフィードバック運用） |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 将来構想（サンドボックス型AI実行環境化）とフェーズ別ロードマップ |
| [docs/BACKLOG.md](docs/BACKLOG.md) | 指摘台帳: UX試験等で見つかった気になりポイントの即時記録（対応判断は後で個別に） |
| [docs/UX-SCENARIOS.md](docs/UX-SCENARIOS.md) | UX試験のシナリオ台帳: 触り心地試験の台本（全体通し S1 ほか）・実施トリガ・実施履歴 |
| [docs/THREE-LAYER-CORE-MIGRATION-PLAN.md](docs/THREE-LAYER-CORE-MIGRATION-PLAN.md) | 3層コア移行の計画: フェーズ・完了条件・現行ファイルの移行先対応表 |
| [docs/MIGRATION-LEDGER.md](docs/MIGRATION-LEDGER.md) | 移行台帳: ベースライン記録・作業単位（YM/SM/WS-xx）の状態 |
| [docs/decisions/](docs/decisions/README.md) | ADR（設計決定 0001〜0008）と意図的差分一覧（INTENTIONAL-DIFFS.md） |

### 外部仕様化ワークスペース（`specs/`）

`specs/`は、製品の宣言言語、意味、振る舞い、表示と操作を、実装から独立した外部契約として検討・管理するワークスペースである。`specs/`に関する議論、調査、作成、更新、または仕様に基づく実装検討へ着手するときは、**最初に [specs/SPECIFICATION-GUIDE.md](specs/SPECIFICATION-GUIDE.md) を全文読み、その手順と情報の位置づけに従うこと**。仕様化の依頼だけから、開発本線のコード変更を推測しない。

**作業のワークフロー:**

1. **着手前**: docs/FEATURES.md で対象機能の位置（実装ファイル・テスト有無）を確認し、docs/ARCHITECTURE.md の該当する設計方針（特に §4）を読む。
2. **実装中**: docs/CONVENTIONS.md に従う。重要: UI文言・コメント・エラーメッセージは日本語／純粋ロジック層に Compose を持ち込まない／ツリー変更には `markEdited()`。
3. **完了前**: `./gradlew test` を通し、変更内容に応じてドキュメントを更新する（対応表は docs/CONVENTIONS.md §6）。機能を追加・変更したのにドキュメントが差分に含まれない場合は、更新漏れを疑うこと。`/sync-docs` スキルでチェックできる。
4. **完了報告**: 機能を追加・変更したら、報告に**試し方**（具体的な操作手順＋見てほしい観点）を必ず含める（docs/CONVENTIONS.md §7）。利用者が触った感想からチューニングの要否を決める。作業中に見つけた本題と異なる気になりポイントは、直しに行かず docs/BACKLOG.md へ即時登録する。

## 3層コア（コアに触る前に必読）

in-repoマルチモジュール化による3層コア切り出しは**計画の全フェーズ（Phase 0〜12・M1〜M5）完了**（計画: [docs/THREE-LAYER-CORE-MIGRATION-PLAN.md](docs/THREE-LAYER-CORE-MIGRATION-PLAN.md)・記録: [docs/MIGRATION-LEDGER.md](docs/MIGRATION-LEDGER.md)）。現在は**併存期**——アプリ（UI）は旧経路のまま動き、新経路はコア3モジュール＋`cli` が提供する（委譲シェルで一致を担保）。残課題（derive/メタスキーマreader・イベントwatch・UIの新API接続）は移行後テーマ（docs/POST-MIGRATION-REFACTORING-PLAN.md）として個別判断——6E仮想文書は完了（RC-01〜03。ビューの解決・テキスト表現はカーネル `semantic/VirtualViews.kt`、root `core/Views.kt` は変換シェル）。コアを触る際の規約は docs/CONVENTIONS.md §8 と以下＝**引き続き有効**:

- [docs/decisions/](docs/decisions/README.md) のADRは本書・CONVENTIONS と同格の規約。特に: 依存方向 `yaml-core ← semantic-core ← workspace-core ← cli／アプリ`（coreからアプリ・Compose・`Path` への逆依存禁止。ADR-0002）／可変YNode＋copy-on-write規律（ADR-0003）／Undo・履歴はworkspace-core単一所有（ADR-0006）／表記→意味の禁止（ADR-0008）。
- 移行は**移動であって複製ではない**。移動後もアプリ含む `./gradlew test` を緑に保つ。移行中のパッケージ改名は禁止（`model`・`yaml` 等のパッケージ名のままモジュールへ移す）。
- 台帳で done の単位はモジュール側にファイルがある（例: `model/`・`yaml/` → `yaml-core/src/`）。変更は移行先で行う。
- 既存挙動を変えるときは実装より先に [INTENTIONAL-DIFFS.md](docs/decisions/INTENTIONAL-DIFFS.md) へ登録する（登録なき挙動差分は移植ミスとして扱う）。
- `test-fixtures/` は読み取り専用の共通fixture（期待結果は [test-fixtures/README.md](test-fixtures/README.md)）。変更を伴うテストは一時ディレクトリへコピーして使う。

## 落とし穴（要注意）

- `YNode` は Compose の State ではない。ビューは `doc.version` / `state.indexVersion` を読んで再コンポーズを購読する。読み忘れると画面が更新されない。
- `IdIndex` はディスク基準（正）。未保存編集の参照解決は `DocScanner.scan` によるローカルIDを重ねて判定する二層構造（docs/ARCHITECTURE.md §4.5）。更新は変更ファイル単位の増分（`update`）だが、**スキーマが変わったら必ず `rebuild`**（走査規則が変わるため）。保存・外部変更でのこの振り分けは `Workspace.noteDiskChanges` に一元化されている（R9-02）——AppState 側に振り分けを書かない。**UI層は `state.index` でなく `state.overlayIndex()`**（dirty文書を重ねたオーバーレイ索引＝「見たまま判定」。UX-03）を読み、`remember` のキーに `state.editVersion` を含める。ヘッドレス経路（CLI・エージェント）はディスク索引のまま。
- YAML はコメント・表記スタイルを保持する（R8-01）。ノードを**置換**する経路では `inheritMetaFrom` で引き継ぐ（`Slot.set` / `Patcher` は対応済み）。構造の比較・派生成果物の出力は `dump` ではなく `YamlIO.dumpPlain`（コメント・スタイルで揺れるため。docs/ARCHITECTURE.md §4.17）。複数ドキュメントYAMLの書込は `OpenDocument`（`parseAll`/`dumpAll`）に閉じ、読み取り専用経路だけ `parseFirst` を使う。
- スキーマの `FieldDef` を使う際は必ず `schema.resolve(field)` を通す（`$ref` 解決）。object の子フィールドの列挙は必ず `schema.effectiveFields(fields, node)` を通す（enum の `cases` 合成。docs/ARCHITECTURE.md §4.7）。
- フィールド型を追加する場合の変更箇所は docs/ARCHITECTURE.md §6 に列挙されている（4箇所以上に分岐が必要）。
- フィールドの実効必須判定は素の `field.required` を見ず、必ず `schema.requiredAt(field, phaseIndex)` を通す（`requiredFrom` によるフェーズ累積必須を見落とす。`phaseIndex` は `schema.currentPhaseIndex(root)`。docs/ARCHITECTURE.md §4.15）。カバレッジの `activeFrom` も同様に `coverageStatuses` の `definingRoot` 引数を省略しない。
- 要素の1行要約（タイトル・サブタイトル・畳み込みヘッダ）は `schema/Display` に一元化されている（R10-01）。一覧・候補・要約の表示に独自のタイトル抽出を書かない。`IdEntry.title` は表示解決の結果（`role` ＞ 予約語）。enum のリッチ選択肢（`{value, label, description}`）の label / description は表示専用で、検証・`cases`・AI制約は常に `value`（docs/ARCHITECTURE.md §4.22）。
- フォーム系の新しい編集UIは `FormCtx` を通し、閲覧専用モード（`ctx.readOnly`）の表示分岐を入れる。`readOnly` 中の `markEdited` / スカラー書込は check で拒否される。
- UIシェルはスペース制（`app.UiSpace`: 編集・台帳・実行・設計。R12-01）。`uiSpace` の書き換えは `showSpace` / `showWorkspaceView` / ナビゲーション系メソッド経由に限る（ジャンプは編集／台帳へ着地する規約）。新しい画面をモーダル（AlertDialog）で作らない——確認ダイアログを除き、スペース内の常設画面にする。エージェント実行の状態は `RunController` に置き、コンポーザブルの `remember` へ戻さない（docs/ARCHITECTURE.md §4.24）。
- エージェントの編集は必ず `agent/Staging`（オーバーレイ）経由。原本への書込は人間の承認（`Workspace.apply`）のみ。書込権限・予約ディレクトリ禁止は `AgentToolbox` が強制する（docs/ARCHITECTURE.md §4.10）。
- ワークスペースの配置判定（予約ディレクトリ・台帳範囲・索引除外）は `schema/WorkspaceLayout` に一元化されている（R13-02）。機能側で `startsWith("_")` やディレクトリ名の直書き判定を書かない。`.yde/` 構成（定義は `.yde/schemas` 等・`ledger:` で台帳宣言）とレガシー構成（ルートの `_schemas` 等）の2モードがあり、`ledger` 宣言時は台帳外のYAMLが走査・索引・エージェントの対象外になる（エディタ層のスキーマ適用・単文書検証だけが効く。ARCHITECTURE §4.25）。`_runs/`（runs ゾーン）は索引対象外。
- `_schemas/` 配下の文書にはメタスキーマ（`builtin-schema`）**だけ**が適用される（`Workspace.schemaFor`。一般スキーマの match は及ばない）。派生スキーマ（`derive:`）の**導出元文書**の変更はスキーマ変更と同じ扱い（`noteDiskChanges` が全再構築へ振り分け、自動適用は承認待ちへ降格）。derive の写像項目を増やすときは `SchemaLoader.parseDerive`・`DerivedSchemas`・メタスキーマの3箇所を対で更新する（docs/ARCHITECTURE.md §4.23）。
- 定義文書（`_agents`/`_activities`/`_evals`/`_skills`/`_views`）への書込は `meta: true` のアクティビティのみ。`meta` かつ `approval: auto` はロード時に定義エラー。`_schemas`/`_runs` は meta でも書込不可。
- ビュー（`_views`）はデータを持たない射影。セルは原本座標 `(file, YPath)` に解決され、UIは docCache ツリー直接編集、エージェントは `read_view`→既存 `apply_patch`。ビュー専用の書込経路を作らない（docs/ARCHITECTURE.md §4.14）。定義項目の追加は `core/Views.kt` の `parseView` と `BuiltinSchemas` の `builtin-view` を対で更新する。
- skill は宣言型（import / export）が既定。コード実行skill（kind: run）は `skill/Runner.kt` の `Approval`（人間の事前承認。定義変更で失効）経由でしか実行できず、エージェント・自動実行からは起動不可（docs/ARCHITECTURE.md §4.21）。インポートは直接書かず変更セット（`Workspace.apply`）経由、エクスポートのYAML出力は禁止（原本主義）。入出力形式の追加は `skill/Tables.kt` に閉じる。
- AI呼び出しのHTTP送信は `ai/Http.kt`（`AiHttp.post`）に一元化されている（プロキシ・Basic認証）。OpenAI互換の接続先（Azure・ベースURL上書き）は `ai/Connection.kt` が解決する。独自に `HttpClient` を作らない（docs/ARCHITECTURE.md §4.18）。
- 評価（`EvalRunner`）はワークスペースの一時コピー上で実行する。原本に書くのはレポート（`_runs/eval-*.yaml`）だけ。実行記録の形式を変えたら `RunLog` と `RunMetrics` の両方を直す。
- カーネル（`AgentKernel`）のテストは疑似セッション注入で書く（`KernelTest` 参照）。実APIを叩くテストは書かない。
- 自動化エンジン（`AutomationEngine`）のテストは `tick(now)` / `processOnce()` を直接呼ぶ（タイマー・sleep を使わない。`AutomationTest` 参照）。自動適用後はスタンプ基準を更新し、自己変更で on-change を再発火させないこと。
