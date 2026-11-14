-- 手書きの検討例。データ入力スキーマや自動移送を定めるものではありません。
-- 参照先→TODO→コメント・状態の履歴の順に値を用意する例です。実行検証は行っていません。
INSERT INTO categories (category_id, name) VALUES ('CAT-001', '学習');

INSERT INTO todos (todo_id, title, summary, category_id, status, created_on, updated_on)
VALUES ('TODO-001', 'データモデルを読む', '概念と論理の対応を確認する', 'CAT-001', 'IN_PROGRESS', '2026-10-01', '2026-10-02');

INSERT INTO comments (todo_id, comment_no, recorded_on, body)
VALUES ('TODO-001', 1, '2026-10-02', 'テーブル定義との対応も確認する。');

-- 登録と着手。状態の更新と履歴の追記は、同じトランザクションで行う
INSERT INTO todo_status_history (todo_id, history_no, changed_on, action, from_status, to_status, note)
VALUES ('TODO-001', 1, '2026-10-01', 'register-todo', NULL, 'OPEN', NULL),
       ('TODO-001', 2, '2026-10-02', 'start-todo', 'OPEN', 'IN_PROGRESS', NULL);
