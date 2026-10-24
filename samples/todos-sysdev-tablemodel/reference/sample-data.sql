-- 手書きの検討例。データ入力スキーマや自動移送を定めるものではありません。
-- 参照先→TODO→コメントの順に値を用意する例です。実行検証は行っていません。
INSERT INTO categories (category_id, name) VALUES ('CAT-001', '学習');

INSERT INTO todos (todo_id, title, summary, category_id, status, created_on)
VALUES ('TODO-001', 'データモデルを読む', '概念と論理の対応を確認する', 'CAT-001', 'OPEN', '2026-10-01');

INSERT INTO comments (todo_id, comment_no, recorded_on, body)
VALUES ('TODO-001', 1, '2026-10-02', 'テーブル定義との対応も確認する。');
