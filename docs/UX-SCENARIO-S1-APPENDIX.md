# S1 付録: 貼り付け用データと完成形リファレンス

[UX-SCENARIOS.md](UX-SCENARIOS.md) の S1（全体通しシナリオ）で貼り付ける入力データと、各幕の仕上げの目標形。台本の「付録 A-n」はここを指す。

### A-1 `issues/00-issues.yaml`（幕2で貼り付け）

```yaml
issues:
  - id: ISS-001
    title: ログイン画面でパスワード誤入力時のメッセージが不親切
    status: open
    priority: high
    assignee: MEM-001
    due: 2026-08-21
    summary: 誤入力時に「エラー」とだけ表示され、原因が分からない。
  - id: ISS-002
    title: 一覧画面の読み込みが遅い
    status: in-progress
    priority: medium
    assignee: MEM-002
    due: 2026-08-28
    summary: 課題が200件を超えると表示に5秒以上かかる。
  - id: ISS-003
    title: 操作マニュアルの更新漏れ
    status: open
    priority: low
    assignee: MEM-001
    summary: 8月リリースの新機能がマニュアルに載っていない。
```

### A-2 `members/00-members.yaml`（幕2で貼り付け）

```yaml
members:
  - id: MEM-001
    name: 佐藤 拓
    role: developer
  - id: MEM-002
    name: 李 静
    role: qa
```

### A-3 `_schemas/issue.yaml`（幕2の仕上げの目標形）

```yaml
id: issue
name: 課題
match: "issues/*.{yaml,yml}"
root:
  type: object
  fields:
    - key: issues
      label: 課題一覧
      type: array
      item:
        type: object
        fields:
          - { key: id, label: 課題ID, type: id, required: true }
          - { key: title, label: 件名, type: string, required: true, role: title }
          - { key: status, label: 状態, type: enum, values: [open, in-progress, done], default: open, required: true }
          - { key: priority, label: 優先度, type: enum, values: [low, medium, high], default: medium, required: true }
          - key: assignee
            label: 担当
            type: ref
            idPattern: "MEM-*"
            scope: { schemaIds: [member] }
          - { key: due, label: 期限, type: date }
          - { key: summary, label: 概要, type: text }
```

### A-4 `_schemas/member.yaml`（幕2の仕上げの目標形）

```yaml
id: member
name: 担当者
match: "members/*.{yaml,yml}"
root:
  type: object
  fields:
    - key: members
      label: 担当者一覧
      type: array
      item:
        type: object
        fields:
          - { key: id, label: 担当者ID, type: id, required: true }
          - { key: name, label: 氏名, type: string, required: true, role: title }
          - { key: role, label: 役割, type: enum, values: [developer, qa, po] }
```

### A-5 `_schemas/issue-detail.yaml`（幕4で設計UIから作る目標形）

```yaml
id: issue-detail
name: 課題詳細
match: "issues/details/*.{yaml,yml}"
root:
  type: object
  fields:
    - { key: id, label: 課題ID, type: id, required: true }
    - { key: approach, label: 対応方針, type: text, required: true }
    - key: steps
      label: 作業手順
      type: array
      item: { type: string }
    - { key: risk, label: リスク・懸念, type: text }
```

### A-6 `_views/issue-catalog.yaml`（幕5で貼り付け）

```yaml
id: issue-catalog
name: 課題カタログ
rows:
  idPattern: "ISS-*"
order:
  fileGlob: issues/00-issues.yaml
columns:
  - label: 件名
    fileGlob: issues/00-issues.yaml
    path: title
    editable: true
  - label: 状態
    fileGlob: issues/00-issues.yaml
    path: status
    editable: true
  - label: 優先度
    fileGlob: issues/00-issues.yaml
    path: priority
    editable: true
  - label: 担当
    fileGlob: issues/00-issues.yaml
    path: assignee
    editable: true
  - label: 対応方針
    fileGlob: "issues/details/*.yaml"
    path: approach
detail:
  fileGlob: "issues/details/*.yaml"
  filePattern: "issues/details/{id}.yaml"
newRow:
  file: issues/00-issues.yaml
  path: issues
```

### A-7 `_agents/reviewer.yaml`（幕6で作成）

```yaml
id: reviewer
name: レビュー担当
persona: |
  慎重で、指摘には必ず対象IDと根拠を添える。
  推測で断定せず、文書に書かれている内容だけを評価する。
  指摘は具体的な改善案とセットで書く。
rubric:
  - 具体性
  - 実行可能性
  - 優先度と期限の整合
```

### A-8 `_activities/review-issues.yaml`（幕6で作成）

```yaml
id: review-issues
name: 課題レビュー
description: 課題と対応方針をレビューし、指摘を追記する。
agent: reviewer
target:
  match: "issues/**/*.{yaml,yml}"
instruction: |
  最初に ask_human で「今回のレビューで特に重視する観点」を1回だけ質問し、
  回答を踏まえて課題と対応方針をレビューしてください。
  - 課題: 件名・概要から状況が具体的に分かるか。優先度と期限は整合しているか
  - 対応方針: 方針が課題の原因に対応しているか。手順は実行可能か
  指摘は各文書のルートに review_comments 配列として追記してください。
  各指摘は { target: 対象ID, comment: 指摘内容, suggestion: 改善案 } の形式にします。
allowedFiles:
  - "issues/**"
allowedPaths:
  - "review_comments*"
limits:
  maxTurns: 8
gates:
  validation: error-free
```
