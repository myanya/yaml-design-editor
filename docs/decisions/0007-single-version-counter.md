# ADR-0007: バージョンは単一のLong連番だけにする

- 日付: 2026-08-16
- 状態: 承認済み

## 文脈

エージェント基盤向けに「意味のある変更のまとまり」をバージョン体系（DocumentRevision / WorkspaceVersionToken / ContentVersion 等の型族）で表現する構想は複雑になりすぎた。移行では現行実装以上のことを行わない。

## 決定

- バージョンは `typealias WorkspaceVersion = Long` の単一連番のみ。working snapshotが変わるたび（apply・undo・redo・reload）に+1。undoで内容が過去と一致してもversionは巻き戻さない。
- 文書ごとのrevision型は持たない。dirty判定は保存済みテキストと現在のdump結果の比較（現行 `EditBuffer` と同水準）。
- 楽観ロック（ContentVersion）は導入しない。外部変更はタイムスタンプ基準の検出・通知・reloadまでとし、保存は最終書込み優先（現行 `FileWatcher` 水準）。
- トランザクションのbaseVersion確認とリファクタリングplanのsourceVersion確認は、この連番の比較だけで行う。

## 帰結

- 鮮度判定・stale plan拒否は1つの比較で済む。
- 競合検出・変更まとまりのバージョン化は、移行後に各層の役割を整理してから再設計する（計画書§11）。
