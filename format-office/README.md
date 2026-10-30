# format-office

Excel（.xlsx）と Word（.docx）の表を読み書きする拡張です（Apache POI）。公開するのは `FormatCodec` の実装 2 つ（`XlsxFormat`・`DocxFormat`）だけで、入口（desktop・cli）の登録の一覧に並べて使います。
POI は大きなライブラリなので、中核（app-core）に入れずこのモジュールに閉じています。文字の形式（csv・json・markdown・html）は app-core の組込みです。

## Excel（`format: xlsx`）

- 読むときはシート名（`input.sheet`。省略時は先頭）とヘッダ行（`input.headerRow`。1 始まり）を使います。数値は整数なら Long に、日付の書式のセルは `YYYY-MM-DD` の文字にします。
- 書くときはシート名（`output.sheet`）で 1 シートを作り、数・真偽・文字をそのまま入れます。

```yaml
kind: import
input: { format: xlsx, sheet: 課題一覧, headerRow: 1 }
---
kind: export
output: { file: out/issues.xlsx, sheet: 課題一覧 }
```

## Word（`format: docx`）

- 文書の中の何番目の表か（`input.table`。1 始まり）を選び、1 行目をヘッダにします。値はすべて文字です。
- 書くこと（Word への書き出し）はできません。

```yaml
kind: import
input: { format: docx, table: 1 }
```

足し方は [docs/architecture/agents.md](../docs/architecture/agents.md)「拡張ポイント」。
