# semantic-core（意味の計算）で守ること

スキーマの意味・検証・ビューの計算（`semantic`・`semantic.schema`）を書く・直すときの決まり。全体の決まりはリポジトリの根の [AGENTS.md](../AGENTS.md)。説明は各行の設計文書の「規律」にある。

- 構造の検証は `FieldDef` を直接読まず、導出した構造スキーマ（yaml-core `structure`）で行う。ID・参照・フェーズ・表示に関わるものを yaml-core へ戻さない → architecture/schema.md「構造スキーマの導出」
- ダッシュボードの共有の入力（`inputs`）とビューの `params` は「空＝絞らない」。外すのは `ActionRequire.prune`（where / check の項目）の 1 か所で、`VirtualViews.bindParams` もそれを使う。値の式の列（`all`）には渡らない → architecture/dashboards.md「規律」
- object を使う側の `fields` は同じキーを狭め新しいキーを足す（`SchemaTree.mergeFields`）。エンティティ型は要素の object の `entity` が、文書スキーマの `entity:`（ID の形）より優先。型の `constraints` は要素ごとの検査（`SemanticElementChecks`。グラフを使わない） → architecture/graph-query.md「エンティティ型と関係レジストリ」・architecture/checks.md「文書 1 つの検証」
