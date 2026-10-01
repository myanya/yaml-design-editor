# CLAUDE.md

Kotlin + Compose for Desktop 製のスキーマ駆動 YAML エディタ。ワークスペース内の YAML 文書を `_schemas/*.yaml` のスキーマに基づくフォーム／ソース／差分で編集し、ID 索引・参照解決・検証・AI 生成支援を提供する。

## コマンド

```bash
./gradlew test                              # 単体テスト（コミット前に必ず実行）
./gradlew run                               # スタート画面で起動（履歴からワークスペース選択）
./gradlew run --args="/path/to/workspace"   # ワークスペース指定で起動
./gradlew runCli -q --args="help"           # ヘッドレス CLI（docs/manual/cli.md）
./gradlew runCli -q --args="version"        # バージョン表示（外部＝appVersion・内部＝yyyymmdd.ビルド番号）
./gradlew packageDistributionForCurrentOS   # 配布物作成
scripts/docs-lint.sh                        # 文書の形の機械チェック
```

要件: JDK 17+。GUI アプリのため `run` は対話的に閉じる必要がある（検証はテスト・ビルド・`runCli` で行う）。

## 文書

- 役割と更新のタイミングは [docs/README.md](docs/README.md) の表が正。規則は [docs/CONVENTIONS.md](docs/CONVENTIONS.md)「ドキュメント更新規約」。
- 着手前に読むのは [docs/FEATURES.md](docs/FEATURES.md) の該当行と [docs/architecture/](docs/architecture/README.md) の該当する主題。
- 記録（[docs/archive/](docs/archive/README.md)）は経緯を調べるときだけ読む。

## ワークフロー

1. **着手前**: FEATURES で対象機能の実装ファイルとテストを引き、architecture/ の該当する主題（部品・流れ・規律・拡張ポイント）を読む。
2. **実装中**: CONVENTIONS に従う。UI 文言・コメント・エラーメッセージは日本語。純粋ロジック層に Compose を持ち込まない。ツリー変更には `markEdited()`。
3. **完了前**: `./gradlew test` を通し、docs/README.md の表に従って文書を更新し、完了したものは archive へ移す。`/sync-docs` と `scripts/docs-lint.sh` で確かめる。
4. **完了報告**: 機能を追加・変更したら**試し方**（操作手順＋見てほしい観点）を必ず含める。本題と異なる気になりポイントは直しに行かず [docs/BACKLOG.md](docs/BACKLOG.md) へ即時登録する。

## 規約の要点

- 依存方向は `yaml-core ← semantic-core ← workspace-core ← cli／アプリ`。コアからアプリ・Compose・`Path` へ逆依存しない。ADR（[docs/decisions/](docs/decisions/README.md)）は規約と同格。
- パッケージは `dev.toros.yde.<モジュール>.*`。文書とコメントでは接頭辞を省いた短い形（`schema.FieldDef`・`validate/Validator.kt`）で書く。
- 可変 `YNode` ＋ copy-on-write の規律。Undo・履歴は workspace-core の単一所有。表記から意味を決めない。
- 移動であって複製ではない。`test-fixtures/` は読み取り専用で、変更を伴うテストは一時ディレクトリへコピーして使う。
- 既存挙動を変えるときはコミットメッセージに書く。設計の決定を変えるなら新しい ADR で上書きを宣言する。
- 新しい決定論的機能は Core Calculus を一段挟む（CONVENTIONS §9）。検査はまず YAML の定義体で、YAML の組み立てはまず変換定義で書けるかを考える。

## 落とし穴

説明は docs/architecture/ の各ファイルの「規律」にある。

- `YNode` は Compose の State ではない。ビューは `doc.version` / `state.indexVersion` を読んで購読する → architecture/ui.md「UI シェル」
- UI は `state.index` でなく `state.overlayIndex()` を読み、`remember` のキーに `state.editVersion` を含める → architecture/index-references.md「索引の二層」
- ディスク変更の取り込みの振り分けは `Workspace.noteDiskChanges` だけ。ディスクを映すセッションと編集用セッションは 1 つにまとめない → architecture/index-references.md「索引の二層」
- 場所はワークスペース相対の `DocumentId`。`DocumentId == Path` は常に偽。`Validator` の `loader` を省くと他の文書を読まない → architecture/index-references.md「場所は文書ID」
- ノードの置換は `inheritMetaFrom` で表記を引き継ぐ。構造の比較・派生出力は `YamlIO.dumpPlain`。複数文書は `OpenDocument` に閉じる → architecture/yaml-model.md「コメントと表記の保持」
- 構造の検証は `FieldDef` を直接読まず、導出した構造スキーマ（yaml-core `structure`）で行う。ID・参照・フェーズ・表示に関わるものを yaml-core へ戻さない → architecture/schema.md「構造スキーマの導出」
- スキーマを読むのはカーネル（`WorkspaceReader`）だけ。`SchemaDef` は薄い窓口で data class ではない。表示のヒントは `FieldHints` に置き検証から読まない → architecture/schema.md「$ref と実効フィールド」
- `FieldDef` は `schema.resolve`、子フィールドは `schema.effectiveFields`、必須は `schema.requiredAt(field, phaseIndex)` を通す → architecture/schema.md「$ref と実効フィールド」・architecture/schema.md「フェーズ」
- 値の式は項目の印 `expression: true` だけが決める。読み取り `ExpressionFields`・評価 `SemanticValues`。独自の `{…}` 穴埋めを書かない。`[*]` を含むパスは `PathPattern`（`-1` 置換を書かない）。値は項で、集計は配列を受け取る関数、集合から値へは `all` だけ → architecture/schema.md「値の式」
- 要素の 1 行要約は `schema/Display` に一元化。enum の label は表示専用で、検証と AI 制約は `value` → architecture/index-references.md「表示解決」
- フォームの編集 UI は `FormCtx` を通し `ctx.readOnly` の分岐を入れる。画面はモーダルでなくスペース内の常設画面。全体のショートカットは `Main.kt` の `handleShortcut` → architecture/ui.md「UI シェル」
- エージェントの編集は `agent/Staging` 経由。適用の本体はカーネルの `ChangeSetApplier` で、root に適用規則を書かない → architecture/workspace.md「変更セットの適用」
- 検査はまず定義体（`builtin-definitions/*.yaml`）とメタスキーマで書く。検証の組み立てと配り方はカーネルにあり、root の `Validator` に検査を書き足さない → architecture/checks.md「定義層の 3 段」・architecture/checks.md「文書 1 つの検証」
- 検査の結果は 4 つ（違反・充足・保留・対象外）。「指摘が出ない＝充足」と判定しない。同じ指摘かは `IssueSource.identity` で見る → architecture/checks.md「4 つの結果」
- 全体を回す検証は `shareEvaluations = true` を渡す。ただし索引に重ねていない内容の検証では渡さない → architecture/checks.md「評価は 1 回」
- クエリの線は `graph.ownRefs`（直接書いた参照）で辿る。判定に `graph.outgoingRefs` を使わない → architecture/graph-query.md「線の意味」
- 文書の新規作成は `AppState.createDocumentFile` の単一経路。`Files.writeString` ＋ `reload()` は Undo 履歴を全て失う → architecture/schema.md「リファクタリング」
- 配置判定は `WorkspaceLayout` に一元化。`_schemas/` にはメタスキーマだけ。定義文書への書込は `meta: true` のアクティビティのみ → architecture/workspace.md「ワークスペース構造と layout」・architecture/schema.md「推測・メタスキーマ・派生」・architecture/agents.md「エージェント実行と Staging」
- ビューは射影で、専用の書込経路を作らない。AI の HTTP は `AiHttp.post` に一元化 → architecture/views.md「ビュー機構」・architecture/agents.md「AI 接続」
- カーネルのテストは疑似セッション注入、自動化のテストは `tick(now)` を直接呼ぶ。実 API を叩かない → architecture/agents.md「エージェント実行と Staging」・architecture/agents.md「自動実行」
- 型は解決した項目の連鎖で判定する（`field.isA(BuiltinTypes.TEXT)`）。`FieldType` は基底の種類だけで、組込みの型は semantic-core の資源 `builtin-types/` の YAML。`FieldType.TEXT` のような分岐を足さない。使う側の決まりは狭めるだけで、合成は `SchemaTree.resolve`、参照の絞り込みは `idPatternLayers` を見る → architecture/schema.md「型の連鎖と狭める規則」
