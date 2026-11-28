package example.todos.data.model;

/** 確定したテーブル設計から生成した行。初期値は DB の DEFAULT として扱います。 */
public class TodoTaskRow {
    private java.lang.String id;
    private java.lang.String todoId;
    private java.lang.Long seq;
    private java.lang.String title;
    private java.lang.Boolean done;
    private java.lang.Boolean deleted;
    public java.lang.String getId() {
        return id;
    }

    public void setId(java.lang.String id) {
        this.id = id;
    }

    public java.lang.String getTodoId() {
        return todoId;
    }

    public void setTodoId(java.lang.String todoId) {
        this.todoId = todoId;
    }

    public java.lang.Long getSeq() {
        return seq;
    }

    public void setSeq(java.lang.Long seq) {
        this.seq = seq;
    }

    public java.lang.String getTitle() {
        return title;
    }

    public void setTitle(java.lang.String title) {
        this.title = title;
    }

    public java.lang.Boolean getDone() {
        return done;
    }

    public void setDone(java.lang.Boolean done) {
        this.done = done;
    }

    public java.lang.Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(java.lang.Boolean deleted) {
        this.deleted = deleted;
    }

}
