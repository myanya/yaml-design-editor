# format-xlsx

Excel（.xlsx）の表を読み書きする拡張です（Apache POI）。公開するのは `FormatCodec` の実装 `XlsxFormat` だけで、入口（desktop・cli）の登録の一覧に並べて使います。

- 読むときはシート名（`input.sheet`。省略時は先頭）とヘッダ行（`input.headerRow`。1 始まり）を使います。数値は整数なら Long に、日付の書式のセルは `YYYY-MM-DD` の文字にします。
- 書くときはシート名（`output.sheet`）で 1 シートを作り、数・真偽・文字をそのまま入れます。

skill 定義の例:

```yaml
kind: import
input: { format: xlsx, sheet: 課題一覧, headerRow: 1 }
---
kind: export
output: { file: out/issues.xlsx, sheet: 課題一覧 }
```

足し方は [docs/architecture/agents.md](../docs/architecture/agents.md)「拡張ポイント」。
