# 保存時に自動で入る値

項目の印 `auto`（`created` / `updated` / `revision`）で、保存という出来事を項目の型に合う値で記録する（更新日・作成日・版）。
値の式にはしない（式は決定的で、今日の日付を入れない。[schema.md「値の式」](schema.md)）。ファイルの更新日時も使わない（要素の単位にならず、Git の checkout で変わる）。
samples/todos の「更新日はコメントの最大日付」（ビューの列の値の式）は出来事がログになっている文書の形で、こちらは出来事がログになっていない文書の形。両方が共存する。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `AutoValue` / `FieldDef.auto` | semantic-core/schema/Fields.kt | 印（created / updated / revision）。`SchemaTreeParser` が読み、型の連鎖で引き継ぎ（`SchemaTree.narrow`・使う側に書けば優先）、`FieldYaml` が書き出す |
| `SemanticAutoValues` | semantic-core/semantic/AutoValues.kt | 計画（`plan`）と木への適用（`stamp`）、新しい要素の初期値（`initialValue`）。時計を持たない |
| `ValueSemantics.stamp` | semantic-core/schema/ValueSemantics.kt | 「今」を日付・時刻・日時の正準の形の文字にする（`2026-10-05`・`14:30:15`・`2026-10-05 14:30:15`） |
| `SchemaScaffold.defaultTree(now)` | semantic-core/schema/SchemaScaffold.kt | now を渡すと、新しい要素の自動の項目を初期値（今の日付・時刻／版 1）で埋める |
| `WorkspaceSession.applyAutoValues` | workspace-core/workspace/WorkspaceSession.kt | 保存の直前に、対象の文書の計画をまとめて 1 つのトランザクション（履歴 1 件）として当てる |
| `ChangeSetApplier` | workspace-core/workspace/ChangeSetApplier.kt | 操作を当てた木へ、検証の前に `stamp`（`now` 引数） |
| `SchemaDefinitionChecks` | workspace-core/workspace/SchemaDefinitionChecks.kt | 型との適合（created / updated は日付・時刻・日時、revision は int）と `expression` との併用の誤り |
| メタスキーマ `auto` | workspace-core/resources/builtin-schemas/schema.yaml | 項目のキー `auto` の選択肢と説明 |
| `AppState.save` / `saveAll` / `createDocumentFile` | app/AppState.kt | 保存の直前に当てて UI の木を写し直す・新しい文書を新しい要素の値で埋める・状態表示 |
| `WorkspaceRecords.bulkUpdateViaSession` | core/Documents.kt | 一括変更も内容の更新として、保存の前に当てる |
| `FormView` / `SchemaEditor` | ui/FormView.kt・ui/SchemaEditor.kt | 項目の説明の下の案内文（`autoValueHint`）・設計 UI の選択（型に合う印だけ） |

## 流れ

- **計画は Rewrite**（`SemanticAutoValues.plan(schema, base, working, now)`）: 印を持つ項目の親の object を要素として `SemanticRewrite.walkDocument` で列挙し（Query）、保存済みの内容 `base` の対応する要素と `YamlIO.dumpPlain` で比べ（Predicate）、違えば `SetScalar`（Edit）。「今」は引数で、体系の外から渡す。
- **要素の対応**: 配列の要素は、その object に ID 型の項目があって値が書かれていれば ID、無ければ位置。ルートは常に対応する。
- **「変わった」**: 値と構造の比較で、コメントや表記の違いは数えない。自動の項目（入れ子の要素のものも）は比較から外す。
- **値**: created / updated は `ValueSemantics.stamp(now)`、revision は保存済みの値 +1（読めなければ 1）。新しい要素（base に無い）は値が空の項目だけ埋める（取り込みやエージェントが書いた値は残る）。入れる値が今の値と同じなら操作を作らない（同じ日に何度保存しても履歴は増えない）。
- **当てる場所は 3 つ**:
  - ①セッションの `applyAutoValues(ids, now)`＝保存の直前の 1 トランザクション。`AppState.save` / `saveAll`・一括変更が呼び、入った文書の UI の木は `refreshDocsFromSession` で写し直す。
  - ②`ChangeSetApplier`＝操作を当てた木へ検証の前に `stamp`。必須の更新日が無い新しい要素も通り、同じトランザクションで Undo 1 回。
  - ③新しい文書（`AppState.createDocumentFile`）と配列の「追加」（`defaultTree(now)`）＝新しい要素の値で埋める。
- **当てない場所**: リファクタリング（内容の更新ではない）・Undo / Redo・外部変更の取り込み。
- **履歴**: 保存の直前の分は「自動の値を設定: 文書（場所: 項目…）」の意図を持つ履歴 1 件。保存直後の Undo は日付と版だけ戻す。

## 規律

- 印を読むのは計画・ひな型の初期値・フォームの案内・定義の点検だけ。検証・走査・検査は読まない。
- 計画は `SemanticAutoValues` の 1 実装で、当てる場所は上の 3 つに限る。保存・適用・作成の経路に日付や版の書き込みを直書きしない。
- 「今」は引数で渡す。semantic-core は時計を持たず、テストは固定の時刻を使う。
- 値の形は `ValueSemantics` の 1 か所（直列化は正準の文字列。見せ方は `format`＝`ValueFormat`）。

## 拡張ポイント

- **印の種類の追加**: `AutoValue` に足し、`SemanticAutoValues.plan` の値の決め方と `initialValue`・`SchemaDefinitionChecks` の型の適合・メタスキーマの選択肢・`autoValueHint` を対で更新する。新しい当てる場所を作らない。
- **当てる経路の追加**（内容の更新と見なす操作が増えたとき）: セッションのトランザクションの後・保存の前に `applyAutoValues` を呼ぶ。操作だけを受け取って同じトランザクションに同梱する形が要るなら、`applyAutoValues` に操作を返す口を足す（BACKLOG）。
