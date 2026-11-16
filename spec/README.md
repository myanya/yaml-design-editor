# 仕様（spec）

YDE で YDE 自身の仕様を管理するワークスペースです。定義に書ける語（キー）・語を書ける位置・基底の種類の**原本**を、1 件 1 ファイルの YAML で持ちます。
[docs/spec/](../docs/spec/README.md) の参照文書は、ここから生成する派生物です。

いまの仕様は、**今の実装が読むものの記述**です。実装（パーサとメタスキーマ）と食い違えばテストが止めるので、語を足す・変えるときは実装と仕様を同じコミットで直します。

## 中身

| 場所 | 内容 |
|---|---|
| `language/contexts/CTX-*.yaml` | 位置。語を書ける場所（いまは項目定義だけ） |
| `language/base-types/BT-*.yaml` | 基底の種類。項目の `type` に書ける 15 種 |
| `language/keywords/KW-*.yaml` | 語。1 つの位置に書けるキー 1 つ。書ける基底の種類ごとの値の形と意味（`forms`）を持ちます |
| `.yde/schemas/common.yaml` | 型（ID・分類・状態）と、位置・基底の種類・語の形と検査 |
| `.yde/schemas/context.yaml`・`base-type.yaml`・`keyword.yaml`・`relations.yaml` | 文書のスキーマと、語から位置への関係（書ける語）・語どうしの関係（別の書き方） |
| `.yde/views/` | ダッシュボード「仕様」と一覧。`doc-` で始まるものは生成用 |
| `.yde/reports/` | docs/spec/ の参照文書を作るレポート（定義とひな形） |

## ID の付け方

ID は番号ではなく名前で付けます。別のブランチで並行して足しても、番号の取り合いになりません。

| 種類 | 形 | 例 |
|---|---|---|
| 位置 | `CTX-` ＋ 位置の名前 | `CTX-field` |
| 基底の種類 | `BT-` ＋ `type` に書く名前 | `BT-string` |
| 語 | `KW-` ＋ 位置の名前 ＋ `.` ＋ キー | `KW-field.required` |

語の ID が位置とキーから決まる形と違うと、検査のエラーになります。

## 運用

1. **足す・直す**: `language/` の YAML を書きます。同じキーでも、基底の種類によって値の形や意味が変わるときは `forms` に分けて書きます。種類を限らない形は `applies_to` を省きます。
2. **確かめる**: `./gradlew runCli -q --args="validate spec"` で検証します。
3. **作り直す**: `./gradlew specDocs` で docs/spec/ の参照文書を作り直し、YAML と一緒にコミットします（先に検証し、エラーがあれば書きません）。

- 生成物（docs/spec/ の参照文書）を直接直しません。repo-tests の `SpecTest` が、作り直していないものを止めます。
- `SpecTest` は、仕様と実装の一致も見ます。項目定義の語と書ける基底の種類はメタスキーマ（`builtin-schemas/schema.yaml` の `definitions.field`）と、基底の種類は実装（`FieldType`）と同じでなければなりません。
- 仕様を書いていて見つけた実装のずれ（メタスキーマに無い語・説明の不足）は、ここで直さず [devtrack/](../devtrack/README.md) に登録します。

## 見る

```bash
./gradlew run --args="spec"                                        # ダッシュボード「仕様」から位置・語・基底の種類へ
./gradlew runCli -q --args="report spec schema-fields-md --print"  # 参照文書を書かずに表示
```

## 範囲

いま載っているのは、スキーマの項目定義（`CTX-field`）の語と基底の種類です。スキーマ文書のトップレベル・関係・振る舞い・検査・操作、値の式などの小さな言語、ビューやレポートなどほかの定義は、位置を足しながら広げます。
