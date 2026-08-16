# ADR-0006: Undo/Redo・履歴はworkspace-coreが単一所有する

- 日付: 2026-08-16
- 状態: 承認済み

## 文脈

単文書レベル（yaml-core）とworkspaceレベルの二重Undoスタックは調停不能になりやすい。undoはdirty・保存状態と不可分であり、それらはworkspace-coreの持ち物である。

## 決定

- Undo/Redoスタック・dirty判定・保存状態はworkspace-coreだけが所有する。
- yaml-coreは「操作を適用すると逆操作が得られる」機構（語彙と適用器）までを提供し、履歴を持たない。
- 単文書の編集も文書1件の `ChangeRecord` としてworkspace履歴で扱い、第二のUndoスタックを作らない。
- パース不能テキスト状態（現行 `EditBuffer` の `rawText` / `parseError`）は型付き操作で表現できないため、ソース編集中のテキストレベルUndoは将来のUI層の責務とし、coreの履歴に含めない。

## 帰結

- entity改名のような複数文書変更が1回のUndoで戻る。
- 単文書だけを扱う利用者は、文書1件のworkspaceを `InMemoryDocumentStore` 上に作る。
