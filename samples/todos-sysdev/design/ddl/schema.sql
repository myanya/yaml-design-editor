-- 生成物（レポート ddl）: テーブル設計書（詳細設計項目一覧・詳細設計項目台帳・型対応表・補正一覧（詳細設計））と基本設計項目一覧・基本設計項目台帳から作る。手で直さない

-- カテゴリ（マスタ）
CREATE TABLE category (
  id VARCHAR NOT NULL,
  name VARCHAR(100) NOT NULL,
  CONSTRAINT pk_category PRIMARY KEY (id)
);

-- タグ（マスタ）
CREATE TABLE tag (
  id VARCHAR NOT NULL,
  name VARCHAR(100) NOT NULL,
  CONSTRAINT pk_tag PRIMARY KEY (id)
);

-- TODO（トランザクション）
CREATE TABLE todo (
  id VARCHAR NOT NULL,
  title VARCHAR(100) NOT NULL,
  summary VARCHAR(100) NOT NULL,
  category VARCHAR,
  follows VARCHAR,
  status VARCHAR DEFAULT 'OPEN' NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'SUSPENDED', 'CLOSED', 'CANCELED')),
  created_on DATE NOT NULL,
  updated_on DATE NOT NULL,
  due_on DATE,
  completed_on DATE,
  details VARCHAR,
  result VARCHAR,
  CONSTRAINT pk_todo PRIMARY KEY (id)
);
CREATE INDEX ix_todo_category ON todo (category);
CREATE INDEX ix_todo_follows ON todo (follows);
CREATE INDEX ix_todo_status_due_on ON todo (status, due_on);

-- 作業項目（トランザクション）
CREATE TABLE todo_task (
  id VARCHAR NOT NULL,
  todo_id VARCHAR NOT NULL,
  seq INTEGER NOT NULL,
  title VARCHAR(100) NOT NULL,
  done BOOLEAN DEFAULT FALSE NOT NULL,
  CONSTRAINT pk_todo_task PRIMARY KEY (id)
);
CREATE INDEX ix_todo_task_todo_id ON todo_task (todo_id);

-- TODOのタグ（トランザクション）
CREATE TABLE todo_tags (
  todo_id VARCHAR NOT NULL,
  seq INTEGER NOT NULL,
  tag_id VARCHAR NOT NULL,
  CONSTRAINT pk_todo_tags PRIMARY KEY (todo_id, seq)
);
CREATE UNIQUE INDEX ux_todo_tags_tag_id ON todo_tags (todo_id, tag_id);
CREATE INDEX ix_todo_tags_tag_id ON todo_tags (tag_id);

-- TODOのコメント一覧（トランザクション）
CREATE TABLE todo_comments (
  todo_id VARCHAR NOT NULL,
  seq INTEGER NOT NULL,
  date DATE NOT NULL,
  body VARCHAR NOT NULL,
  CONSTRAINT pk_todo_comments PRIMARY KEY (todo_id, seq)
);

-- TODOの状態の履歴（トランザクション）
CREATE TABLE todo_history (
  todo_id VARCHAR NOT NULL,
  seq INTEGER NOT NULL,
  date DATE NOT NULL,
  action VARCHAR NOT NULL CHECK (action IN ('register-todo', 'start-todo', 'complete-todo', 'suspend-todo', 'resume-todo', 'cancel-todo')),
  from_ VARCHAR CHECK (from_ IN ('OPEN', 'IN_PROGRESS', 'SUSPENDED', 'CLOSED', 'CANCELED')),
  to_ VARCHAR NOT NULL CHECK (to_ IN ('OPEN', 'IN_PROGRESS', 'SUSPENDED', 'CLOSED', 'CANCELED')),
  note VARCHAR,
  resume_on DATE,
  CONSTRAINT pk_todo_history PRIMARY KEY (todo_id, seq)
);

-- 外部キー（すべてのテーブルを作った後に足す）
ALTER TABLE todo ADD CONSTRAINT fk_todo_category FOREIGN KEY (category) REFERENCES category (id);
ALTER TABLE todo ADD CONSTRAINT fk_todo_follows FOREIGN KEY (follows) REFERENCES todo (id);
ALTER TABLE todo_task ADD CONSTRAINT fk_todo_task_todo FOREIGN KEY (todo_id) REFERENCES todo (id) ON DELETE CASCADE;
ALTER TABLE todo_tags ADD CONSTRAINT fk_todo_tags_todo FOREIGN KEY (todo_id) REFERENCES todo (id) ON DELETE CASCADE;
ALTER TABLE todo_tags ADD CONSTRAINT fk_todo_tags_tag_id FOREIGN KEY (tag_id) REFERENCES tag (id);
ALTER TABLE todo_comments ADD CONSTRAINT fk_todo_comments_todo FOREIGN KEY (todo_id) REFERENCES todo (id) ON DELETE CASCADE;
ALTER TABLE todo_history ADD CONSTRAINT fk_todo_history_todo FOREIGN KEY (todo_id) REFERENCES todo (id) ON DELETE CASCADE;
