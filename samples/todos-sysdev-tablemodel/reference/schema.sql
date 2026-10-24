-- 手書きの検討例。自動生成物でも、全業務制約を網羅する完成DDLでもありません。
-- 業務型のID形式・改行禁止・実在日付などのSQL化はDDL-STUDY.mdの検討事項です。
-- SQLiteを仮置きし、空のDBへ適用する形で記述しています。実行検証は行っていません。
PRAGMA foreign_keys = ON;

CREATE TABLE categories (
    category_id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100)
);

CREATE TABLE todos (
    todo_id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 100),
    summary TEXT NOT NULL CHECK (length(summary) BETWEEN 1 AND 100),
    category_id TEXT,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'CLOSED', 'CANCELED')),
    created_on TEXT NOT NULL,
    due_on TEXT,
    completed_on TEXT,
    details TEXT,
    result TEXT,
    FOREIGN KEY (category_id) REFERENCES categories(category_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_todos_due CHECK (due_on IS NULL OR due_on >= created_on),
    CONSTRAINT ck_todos_completed CHECK (completed_on IS NULL OR completed_on >= created_on),
    CONSTRAINT ck_todos_completion CHECK (
        (status = 'CLOSED' AND completed_on IS NOT NULL
            AND result IS NOT NULL AND length(trim(result)) > 0)
        OR (status <> 'CLOSED' AND completed_on IS NULL)
    )
);

CREATE INDEX idx_todos_category ON todos(category_id);
CREATE INDEX idx_todos_due ON todos(due_on);

CREATE TABLE comments (
    todo_id TEXT NOT NULL,
    comment_no INTEGER NOT NULL CHECK (comment_no >= 1),
    recorded_on TEXT NOT NULL,
    body TEXT NOT NULL CHECK (length(body) > 0),
    PRIMARY KEY (todo_id, comment_no),
    FOREIGN KEY (todo_id) REFERENCES todos(todo_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
