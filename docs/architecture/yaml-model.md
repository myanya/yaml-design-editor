# YAML の木

YAML 文書は可変の木 `YNode` として扱い、位置は `YPath` で表す。コメントと表記は編集の意味に影響しないメタ情報として木に付随し、開く → 編集 → 保存の往復で保持される。
変更は 6 種の基礎命令に正規化され、内部では型付き操作、外部との交換ではパッチと変更セットとして表す。置き場は yaml-core（ID・参照・関係・フェーズ・表示のヒントを知らない）。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `YNode`（Scalar / Seq / Map） | yaml-core/model/YNode.kt | 可変の木（位置は `YPath`＝String はキー・Int は索引）。コメント（`YComments`）と表記スタイルのヒントを保持 |
| `YamlIO` | yaml-core/yaml/YamlIO.kt | テキストと `YNode` の変換。`parse`（単一文書）・`parseAll` / `dumpAll`（複数文書）・`parseFirst`（読み取り専用の寛容パース）・`dumpPlain`（コメント・スタイル抜きの正規化ダンプ） |
| `PlainDateResolver` | yaml-core/yaml/YamlIO.kt | TIMESTAMP を暗黙解決しない Resolver。引用符なしの日付・日時を文字列のまま往復させる |
| `YamlOperation` | yaml-core/edit | 変更の基礎命令（Set / Insert / Delete / Move / RenameKey / ReorderKeys）の型付き操作。逆操作を持つ内部の正準 |
| `PatchOp` / `Patcher` | yaml-core/patch/Patch.kt | 同じ基礎命令の交換形式（YPath パッチ）。エージェント・CLI・skill が使う |
| `Slot`（Map / Seq / Root） | ui/Common.kt | 「値の置き場所」の抽象（get / set / remove）。`slotAt` が絶対 `YPath` から `Slot` を得る |
| `parseYPath` | yaml-core/model/YNode.kt | YPath 文字列のパース |

## 木が単一の正

- `OpenDocument.root: YNode` が唯一の編集対象。フォームは木を直接ミューテートし、`markEdited()` で再コンポーズを駆動する。
- `YNode` は意図的に「Compose の State ではない単純な可変オブジェクト」。UI は `doc.version` / `state.indexVersion` を読んで購読する。
- ソース編集はテキストを持つが、パース成功のたびに木へ合流させる（[editing-history.md「ソースとの同期」](editing-history.md)）。保存は常に `canonicalText()`（木の正規化ダンプ）を書き出す。

## YPath

- 要素位置は `YPath = List<Any>` で統一する。検証結果・ハイライト・ジャンプ・AI 生成対象はすべて YPath で紐付く。
- 検証やパッチの場所は、文書ID（[index-references.md「場所は文書ID」](index-references.md)）と YPath の組。

## Slot

- マップのキー（`MapSlot`）・配列要素（`SeqSlot`）・文書ルート（`RootSlot`）を `Slot` で統一し、スキーマフォームと汎用エディタがエディタ部品を共有する。
- 新しい「値の置き場所」が必要なら `Slot` の実装を追加する。ビューグリッドとエンティティページも `slotAt` を共有する。

## コメントと表記の保持

- メタ情報は編集の意味に影響しない。コメントとスタイルヒント（引用符・リテラル・フロー表記）は値の比較・検証・検索・AI 生成の入力構造に関与させない。
- ノード置換時はメタ情報を引き継ぐ。値の置換（`Slot.set` / `Patcher` の set）は `YNode.inheritMetaFrom` で旧ノードのコメントとスタイルを引き継ぐ。スカラーをその場で書き換えるフォーム編集は自然に保持される。
- 残る正規化は仕様。アンカーとエイリアスは展開、インデントは 2 固定、ブロック配列要素の前行コメントは SnakeYAML エミッタの制約で `- # コメント` の形に後置される（内容は失われず冪等）。直すにはエミッタのフォークが要るため、安易に手を出さない。
- 日付・日時は文字列のまま。`PlainDateResolver` を読み書きの両側で使い、`java.util.Date` へ型付けしない（型付けすると検証の現在値表示と保存形式が壊れる）。int / boolean の型付けは従来どおり。

## 複数ドキュメント

- `parse` は複数ドキュメントを拒否する。読み取り専用の経路（索引・検索・開示・ビュー）だけが `parseFirst` で先頭ドキュメントを読む。
- 複数ドキュメントの編集と保存は `OpenDocument`（`parseAll` / `dumpAll`）に閉じる。フォームは先頭ドキュメントを編集し、後続は保持して保存時に再出力する。

## 変更の基礎命令と 2 層

- 基礎命令は 6 種（Set / Insert / Delete / Move / RenameKey / ReorderKeys）。RenameKey と ReorderKeys は順序とコメントを保つ。
- 内部の正準は型付き操作 `edit.YamlOperation`（逆操作つき）と `WorkspaceTransaction`（版の検査・履歴・原子適用）。
- `patch.PatchOp` と変更セット（`changeset.ChangeSet`。基準ハッシュで衝突検出）は同じ基礎命令の外部との交換形式で、適用の入口（`Workspace.applyInternal`・エージェントのステージング）でだけ解釈される。

## 規律

- ノードを置換する経路では `inheritMetaFrom` でコメントと表記を引き継ぐ。
- 構造の比較・派生成果物の出力は `dump` ではなく `YamlIO.dumpPlain`（コメント・スタイルで揺れるため）。`Importer` の変化なし判定・`Exporter` のセル値・`Evals` の期待結果比較がこれを使う。
- 複数ドキュメント YAML の書込は `OpenDocument`（`parseAll` / `dumpAll`）に閉じ、読み取り専用経路だけ `parseFirst` を使う。
- 内部で計画を作るコードは `PatchOp` を作らず、型付き操作で生成する（[editing-history.md「編集の経路」](editing-history.md)）。
- yaml-core に置くのは、YAML の項とパス・変更の基礎命令・最小の構造のスキーマとその検証（[schema.md「構造スキーマの導出」](schema.md)）だけ。ID・参照・関係・フェーズ・表示のヒントに関わるものを yaml-core へ入れない。

## 拡張ポイント

- **YAML 表層仕様の変更**: 挙動を変える場合も `YamlIO` に閉じる。構造比較や派生成果物には `dumpPlain` を使い、通常ダンプの表層情報に依存させない。
- **新しい「値の置き場所」**: `Slot` の実装を追加する。
- **変更の基礎命令の追加**: `YamlOperation`（逆操作を含む）と `PatchOp` / `Patcher` の両方に足し、CLI・エージェントの patch 語彙に載せる。
