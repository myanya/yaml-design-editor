# ADR-0004: 意味グラフは派生物・semantic-coreはI/Oなし

- 日付: 2026-08-16
- 状態: 承認済み

## 決定

- YAMLファイルを正とし、索引（IdIndex）・参照グラフ・entityグラフは常に再構築可能な派生物とする。
- semantic-coreはファイルI/Oを行わず、与えられた文書snapshot（`SemanticInput`）から結果（`SemanticSnapshot`）を導出する純粋エンジンとする。
- すべての結果は入力の `sourceVersion` と原本座標（file相当の `DocumentId` と `YPath`）を持つ。鮮度は必ずversion比較で判断し、staleな結果をcurrentとして公開しない。
- 増分更新は全再構築と結果一致することを比較テストで保証してから導入する（初期は毎回全再構築）。

## 帰結

- ファイルシステムなしで複数文書の意味解析がテストできる。
- 将来の意味スキーマ柔軟化（ユーザ定義宣言）は、エンジンの拡張ではなく宣言（`SemanticDeclarations`）の拡張として行える。
