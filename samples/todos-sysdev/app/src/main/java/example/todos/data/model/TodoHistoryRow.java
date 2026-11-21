package example.todos.data.model;

/** 確定したテーブル設計から生成した行。初期値は DB の DEFAULT として扱います。 */
public class TodoHistoryRow {
    private java.lang.String todoId;
    private java.lang.Long seq;
    private java.time.LocalDate date;
    private java.lang.String action;
    private java.lang.String from;
    private java.lang.String to;
    private java.lang.String note;
    private java.time.LocalDate resumeOn;
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

    public java.lang.String getAction() {
        return action;
    }

    public void setAction(java.lang.String action) {
        this.action = action;
    }

    public java.lang.String getFrom() {
        return from;
    }

    public void setFrom(java.lang.String from) {
        this.from = from;
    }

    public java.lang.String getTo() {
        return to;
    }

    public void setTo(java.lang.String to) {
        this.to = to;
    }

    public java.lang.String getNote() {
        return note;
    }

    public void setNote(java.lang.String note) {
        this.note = note;
    }

    public java.time.LocalDate getResumeOn() {
        return resumeOn;
    }

    public void setResumeOn(java.time.LocalDate resumeOn) {
        this.resumeOn = resumeOn;
    }

}
