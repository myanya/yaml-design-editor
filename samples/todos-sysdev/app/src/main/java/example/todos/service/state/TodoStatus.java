package example.todos.service.state;

/** 確定した状態設計から生成。 */
public enum TodoStatus {
    OPEN("未着手"),
    IN_PROGRESS("対応中"),
    SUSPENDED("中断"),
    CLOSED("完了"),
    CANCELED("中止")
    ;

    private final java.lang.String label;
    TodoStatus(java.lang.String label) { this.label = label; }
    public java.lang.String getLabel() { return label; }

    public boolean isTodoActive() {
        return this == OPEN || this == IN_PROGRESS || this == SUSPENDED;
    }

    public boolean isTerminal() {
        return false || this == CLOSED || this == CANCELED;
    }

    public boolean allows(java.lang.String action) {
        if ("complete-todo".equals(action) && (this == IN_PROGRESS)) return true;
        if ("cancel-todo".equals(action) && (this == OPEN || this == IN_PROGRESS || this == SUSPENDED)) return true;
        if ("start-todo".equals(action) && (this == OPEN)) return true;
        if ("suspend-todo".equals(action) && (this == IN_PROGRESS)) return true;
        if ("resume-todo".equals(action) && (this == SUSPENDED)) return true;
        return false;
    }

    public static TodoStatus next(java.lang.String action, TodoStatus current) {
        if (current == null && "register-todo".equals(action)) return OPEN;
        if ("complete-todo".equals(action) && (current == IN_PROGRESS)) return CLOSED;
        if ("cancel-todo".equals(action) && (current == OPEN || current == IN_PROGRESS || current == SUSPENDED)) return CANCELED;
        if ("start-todo".equals(action) && (current == OPEN)) return IN_PROGRESS;
        if ("suspend-todo".equals(action) && (current == IN_PROGRESS)) return SUSPENDED;
        if ("resume-todo".equals(action) && (current == SUSPENDED)) return IN_PROGRESS;
        throw new java.lang.IllegalArgumentException("現在の状態ではこの操作を実行できません: " + action);
    }
}
