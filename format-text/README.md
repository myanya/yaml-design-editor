# format-text

文字の形式（csv・json・markdown・html）で表を読み書きする拡張です。公開するのは `FormatCodec` の実装 4 つ（`CsvFormat`・`JsonFormat`・`MarkdownFormat`・`HtmlFormat`）だけで、入口（desktop・cli）の登録の一覧に並べて使います。

- 読むときは skill の `input.encoding`（既定 UTF-8）で厳密に解読します。合わない文字コードは化けた表にせず誤りとして知らせます。
- csv は RFC 4180（引用符・埋め込み改行・引用符の二重化）。`input.columns` で見出しの無い csv も読めます。
- json はオブジェクトの配列（`input.path` でルートの中の配列を辿る）。値の型（数・真偽）を保ちます。
- markdown は `|` で始まる表（1 行目ヘッダ・2 行目区切り）。
- html は書くだけ（単体で開ける派生成果物）。

skill 定義の例（取り込み）:

```yaml
kind: import
input: { format: csv, encoding: windows-31j, columns: [日付, 摘要, 金額] }
```

足し方は [docs/architecture/agents.md](../docs/architecture/agents.md)「拡張ポイント」。
