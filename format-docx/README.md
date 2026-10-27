# format-docx

Word（.docx）文書の中の表を読む拡張です（Apache POI）。公開するのは `FormatCodec` の実装 `DocxFormat` だけで、入口（desktop・cli）の登録の一覧に並べて使います。

- 文書の中の何番目の表か（`input.table`。1 始まり）を選び、1 行目をヘッダにします。値はすべて文字です。
- 書くこと（Word への書き出し）はできません。

skill 定義の例:

```yaml
kind: import
input: { format: docx, table: 1 }
```

足し方は [docs/architecture/agents.md](../docs/architecture/agents.md)「拡張ポイント」。
