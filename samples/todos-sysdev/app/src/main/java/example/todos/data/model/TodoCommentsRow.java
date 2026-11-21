package example.todos.data.model;

/** 確定したテーブル設計から生成した行。初期値は DB の DEFAULT として扱います。 */
public class TodoCommentsRow {
    private java.lang.String todoId;
    private java.lang.Long seq;
    private java.time.LocalDate date;
    private java.lang.String body;
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

    public java.time.LocalDate getDate() {
        return date;
    }

    public void setDate(java.time.LocalDate date) {
        this.date = date;
    }

    public java.lang.String getBody() {
        return body;
    }

    public void setBody(java.lang.String body) {
        this.body = body;
    }

}
