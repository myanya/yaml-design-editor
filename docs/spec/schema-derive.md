# 派生スキーマ
    
    <!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/schema-derive-md.md -->
    
    派生スキーマの宣言（文書のスキーマの `derive`）に書ける語の一覧。導出元の文書（例 表の定義書）から、検証用のスキーマを読み込みのときに作る。
    今の実装が読む語を書いたもので、スキーマ文書のトップレベルの語は [schema-document.md](schema-document.md)、項目定義の語は [schema-fields.md](schema-fields.md)。
    **原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。
    
    ## 派生の宣言
    
    文書のスキーマの derive。書いたスキーマには match を書けず、root は省けます。
    
    - メタスキーマ: builtin-schemas/schema.yaml の root.derive
    - 読む実装: semantic-core/schema/SchemaDerivation.kt
    
    | 語 | 必須 | 値の形 | 省いたとき | 意味 |
    |---|---|---|---|---|
    | `from` | 必須 | glob の文字列 |  | 導出元の文書。 |
    | `each` |  | パスの文字列 | 文書のルート | 1 つの文書の複数の要素から導出するときの、配列の場所。 |
    | `id` | 必須 | {パス} を含む文字列 |  | 導出するスキーマの ID。穴は導出元の要素の値で埋めます（例 record-{id}）。 |
    | `name` |  | 文字列 |  | 導出するスキーマの表示名。 |
    | `match` | 必須 | glob の文字列 |  | 導出するスキーマを当てる文書。 |
    | `fields` | 必須 | {source, key, label, description, required, type} |  | 項目の写し方。 |
    
    ## 項目の写し方
    
    derive の fields。導出元の配列の要素 1 つを、項目 1 つにします。
    
    - メタスキーマ: builtin-schemas/schema.yaml の root.derive.fields
    - 読む実装: semantic-core/schema/SchemaDerivation.kt
    
    | 語 | 必須 | 値の形 | 省いたとき | 意味 |
    |---|---|---|---|---|
    | `source` | 必須 | パスの文字列 |  | 項目にする配列の場所。 |
    | `key` | 必須 | パスの文字列 |  | 項目のキーにする値の場所（配列の要素からの相対パス）。 |
    | `label` |  | パスの文字列 |  | 表示名にする値の場所。 |
    | `description` |  | パスの文字列 |  | 説明にする値の場所。 |
    | `required` |  | パスの文字列 |  | 必須かどうかにする値の場所。 |
    | `type` |  | {path, map, default} |  | 型の決め方。 |
    
    ## 型の決め方
    
    fields の type。
    
    - メタスキーマ: builtin-schemas/schema.yaml の root.derive.fields.type
    - 読む実装: semantic-core/schema/SchemaDerivation.kt
    
    | 語 | 必須 | 値の形 | 省いたとき | 意味 |
    |---|---|---|---|---|
    | `path` | 必須 | パスの文字列 |  | 型を決める値の場所。 |
    | `map` |  | 導出元の値 → 型 のマップ |  | 値から項目の型への辞書（例 varchar → string）。 |
    | `default` |  | 型の名前の文字列 |  | 辞書に無い値のときの型。 |
    
    