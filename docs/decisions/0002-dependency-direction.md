# ADR-0002: 3層の依存方向

- 日付: 2026-08-16
- 状態: 承認済み

## 決定

依存は次の一方向に固定する。

```text
yaml-core ← semantic-core ← workspace-core ← cli / アプリ（ルート）
```

禁止する依存:

- yaml-core から semantic-core / workspace-core への依存
- semantic-core から workspace-core への依存
- 3つのcoreから cli・ルート（アプリ）への依存
- 3つのcoreから Compose・UI・AI・agent・skill への依存
- yaml-core と semantic-core から `java.nio.file.Path` への依存（`Path` はworkspace-coreのI/O境界に閉じ、下位層には `DocumentId` を渡す）

## 帰結

- SnakeYAML依存はyaml-coreだけに置き、上位層の公開APIにSnakeYAMLの型を出さない。
- ルートのアプリは移行期間中、3coreすべてへ直接依存してよい（移動済みコードのimportを満たすため）。
