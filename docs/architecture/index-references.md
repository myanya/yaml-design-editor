# 索引と参照

ID 索引はディスク基準（正）で、UI は未保存の編集を重ねたオーバーレイ索引で「見たまま」判定する。索引の中身はカーネルのセッションの意味解析の結果そのもので、写しを持たない。
場所はワークスペース相対の文書ID（`DocumentId`）で表し、`Path` はファイルを開く・ディスクから読む端だけで使う。要素の 1 行要約は表示解決（`Display`）に一元化する。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `IdIndex` | index/IdIndex.kt | `id → 出現一覧` と全参照（中身はセッションの `SemanticSnapshot` そのもの）。上に持つのは覚え書き（被参照の索引・検査の評価の覚え書き・宣言のない関係名の指摘）だけ |
| `IdEntry` / `RefEntry` | index/IdIndex.kt | カーネルの `ScannedId` / `ScannedRef` の別名。エンティティ型・役割（定義／詳細化）・属性付き参照の属性を運ぶ |
| `DocScanner` | index/IdIndex.kt | 1 文書からの ID・参照の抽出（スキーマ有無で検出規則を切替、未知キー境界で走査を止める）。参照に `relation` / `sourceSchemaId` を付与 |
| `IdIndex.resolve` / `backlinksTo` / `sourceEntryOf` | index/IdIndex.kt | ID の解決（定義出現を優先）・被参照・参照を書いた最内の要素 |
| `AppState.overlayIndex()` / `overlayIndexWith` | app/AppState.kt（計算は session/DocumentSession.kt） | dirty 文書を重ねた索引。提案の内容を重ねた索引。画面側は版を読んで購読し、計算はセッション側 |
| `Display` | schema/Display.kt | 表示解決（`summarize` / `titleOf` / `headerText` / `seqHeaderText` / `inline` / `tableColumns`） |
| `IdSuggest` | schema/IdSuggest.kt | 既存 ID 集合からの次の連番の決定的提案（skill の取り込みの採番は、接頭辞を決めた系列の次 `nextInSeries`）。形式の源は `pattern`・entity 宣言・ビュー行の idPattern |
| `RefNaming` | schema/SchemaInference.kt | 参照の命名規約（`ref` / `*_ref` / `refs` / `depends_on` など）の共有実体 |
| `Search` | core/Search.kt | 構造検索（本文とキーの部分一致・スキーマと glob 条件・参照検索・未解決参照） |
| `Workspace.fileOf` / `documentOf` / `treeOf`・`AppState.fileOf` / `documentOf` / `unsavedFirstTree()` | core/Workspace.kt・app/AppState.kt | 文書ID と `Path` の行き来の入口 |

## 索引の二層

- `IdIndex` は保存済みファイルから構築される（ディスク索引＝正）。`Workspace.index` はエージェント・自動実行・CLI の基準。
- 未保存編集中の参照解決は、編集中の木を走査したローカル ID を索引に重ねて判定する（`FormCtx.resolvable`・`Validator`）。この二層を崩さない。
- 更新は増分。保存・外部変更・変更セット適用では、セッションが変更・削除された文書だけを走査し直し、コストは変更ファイル数に比例する。スキーマが変わったときは全ファイルの走査規則が変わるため全再構築（振り分けは [workspace.md「外部変更の取り込み」](workspace.md)）。
- アプリはディスクを映すセッション（`core.Workspace` の内部）と編集用のセッション（`AppState`。未保存の編集と Undo 履歴を持つ）の 2 つを持つ。この 2 つが二層そのもので、1 つにまとめない。読み込みは 1 回で、編集用はディスク側を読み直した直後に `WorkspaceSession.fork()` で起こす（[workspace.md「セッションの fork」](workspace.md)）。

## オーバーレイ

- 人間の UI は「見たままの状態で判定する」（未保存で確認 → OK なら保存）。`AppState.overlayIndex()` が dirty な文書キャッシュの編集中の木だけを重ねた索引を返す（`(indexVersion, editVersion)` でキャッシュ）。
- UI 層（検証・カバレッジ・ビュー行集合・エンティティページ・参照解決・被参照）は `state.index` ではなく `state.overlayIndex()` を読む。ビューグリッドでは未保存の新規 ID も行に現れる。
- 本文の読み出し（`unsavedFirstLoader`）は文書キャッシュ、編集用セッション、ディスクの順に参照する。未表示文書の Undo/Redo も一覧・検索へ反映し、セッションからの木は `copyDocument` でコピーする。
- オーバーレイ由来の値を `remember` するときはキーに `state.editVersion` を含める（含めないと他文書の未保存編集に追随しない）。
- まだ文書に入れていない内容（AI 生成のプレビューなど）は、その内容を重ねた索引（`overlayIndexWith`）で検証する。
- ヘッドレス経路（CLI・エージェント・エクスポート）はディスク索引のまま。エージェントはステージングのオーバーレイ索引を別に持つ。

## ID と参照の意味

- 同一 ID の複数文書出現は「同一論理要素の段階的詳細化」として正当。エラーは同一文書内の重複だけ。
- 参照の解決可否は `idPatterns` の層（型の連鎖で重ねた絞り込み `idPatternLayers`。全ての層に合うこと）× `scope.schemaIds` × `scope.documentIds` の AND（各配列内は OR）。候補表示・検証・AI 制約（`refCandidateIds`）が同じ判定 `IdEntry.isAllowedBy` を使う。
- 参照には関係種別 `relation` を付けられる。解決可否には影響せず、表示と検査（多重度・対象型など）が使う。
- `IdIndex.resolve(id)` は定義出現があればそれを優先する（表示解決・ジャンプ先が定義に安定する）。
- 文書参照（`target: document`）は文書ノードへの参照で、エンティティ参照の解決・バックリンク・カバレッジには混ぜない（[graph-query.md「エンティティ型と関係レジストリ」](graph-query.md)）。

## 段階的詳細化の規約

- 1:1 の詳細化は同一 ID の複数文書出現。詳細文書の存在は出現ベースのカバレッジ（`detailIn`）で検証する。
- 1:N の分裂（例: 1 エンティティ → 単票と明細の 2 テーブル）は新 ID ＋ 親への `ref`（`relation` で種別を宣言）で逆リンクする。親 → 分裂先の一覧はビューのバックリンク列で表示する。
- 被参照数の正準は関係レジストリの多重度（1 ホップ・無条件）と `constraints` の `count` / `exists`（条件付き・多段）。参照ベースのカバレッジは無い（書くと移行先を案内する定義エラー）。
- 詳細化出現の存在は「同一 ID の出現集合への集約」で、参照の線では書けない演算面の専用語彙（`detailIn`）。

## 場所は文書ID

- 索引・検証・ビュー・クエリ・エンティティの全体像は、場所をワークスペース相対の文書ID（`model.DocumentId`。例 `screens/index.yaml`）と YPath で表す。
- root の `IdEntry` / `RefEntry` / `EntityGraph` / `Issue` / `IssueResponse` / `ValidationResult` / `ViewRow` / `QueryResult` / `AspectField` はカーネルの型の別名（`typealias`）。写しの型を持たない。
- `Path` が要るのはファイルを開く・文書キャッシュを引く・ディスクから読む端だけ。行き来は `Workspace.fileOf` / `documentOf` / `treeOf` と `AppState.fileOf` / `documentOf` / `unsavedFirstTree()` を通す。
- 他の文書の中身を読む関数（loader）は `(DocumentId) -> YNode?` で、`Validator`・`Query`・`Views`・`Aspects`・`Traverse` は呼び出し側から必ず受け取る。省いたときにディスクから読む既定は無い（どの内容で判定するかを呼び出し側が決める）。

## 表示解決

- 要素の 1 行要約（タイトル・サブタイトル・ID・畳み込みヘッダ）は `schema/Display` だけが解決する。優先順位はスキーマの `FieldDef.role: title / subtitle`（`$ref` 合成・library 共有に乗る）＞ 予約語（title / name / label、description / summary）＞ id ＞ キー数。
- タイトルの索引借用: `titleLookup` 注入で、要素自身にタイトルがないとき同一論理 ID の他出現から借りる（UI はオーバーレイ索引を渡す）。`headerText` はサブタイトルを併記し、`inline` はタイトルがなければ subtitle で補う。`inline` はエンティティ参照の値（`type: ref`。文書参照は除く）にも `titleLookup` で参照先の名前を添える（畳んだ参照の配列の要約「n 件: ID 名前, …」）。
- 配置は schema パッケージ。索引のタイトル抽出（`DocScanner` → `IdEntry.title`）が使うため、依存方向上 core に置けない。`IdEntry.title` は表示解決の結果で、参照候補・ID 検索・ビュー行タイトル・エージェントの要約が自動で追随する（人間と AI が同じ要約を見る共有契約）。
- バックリンクの表示は「参照を書いた要素」。参照値の位置を含む最内の ID 出現を `sourceEntryOf` で解決し、その ID とタイトルを主表示にする。
- enum のリッチ選択肢（`{value, label, description}`）の label / description は表示専用。検証・`cases` のキー・AI 生成の制約・保存されるデータは常に `value`。
- 配列と map をテーブルにするかの適否と列集合も `Display.tableColumns` が決める（[ui.md「テーブル表示」](ui.md)）。

## 規律

- UI 層は `state.index` でなく `state.overlayIndex()` を読み、`remember` のキーに `state.editVersion` を含める。ヘッドレス経路はディスク索引のまま。
- 索引の中身はセッションの意味解析の結果そのもの。走査・集約を root に書き足さない。
- ディスクを映すセッションと編集用のセッションを 1 つにまとめない。
- root に `Path` を持つ写しの型を作り直さない。`Path.of(d.value)` と書かない。別名を通して入れ子の型は引けない（`IssueResponse.Jump` ではなく `CheckResponse.Jump`）。
- `DocumentId == Path` の比較はコンパイルが通って常に偽。`state.documentOf(doc.file)` に直してから比べる。
- `Validator` の `loader` を省くと他の文書を読まない。ディスクから読むなら `Workspace::treeOf`、未保存の編集を優先するなら `AppState.unsavedFirstTree()` を渡す。
- 一覧・候補・要約の表示に独自のタイトル抽出を書かない。
- 参照の解決可否の判定を増やすときは、候補・検証・AI 制約の三箇所で一貫させる（`IdEntry.isAllowedBy`）。

## 拡張ポイント

- **参照認識の規約の追加**: `RefNaming` に足す（`DocScanner` とスキーマ推測が共有）。
- **表示の役割の追加**: `FieldDef.role` と `Display` に閉じ、消費側に分岐を書かない。
- **索引の上の覚え書きの追加**: `IdIndex` に置き、走査結果の更新（rebuild / update / overlay）で無効化する。
