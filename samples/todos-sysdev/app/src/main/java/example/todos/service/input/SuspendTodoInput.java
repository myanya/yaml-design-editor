package example.todos.service.input;

/** 確定した操作入力と Service の引数から生成。 */
public class SuspendTodoInput {
    private java.lang.String id;
    public java.lang.String getId() { return id; }
    public void setId(java.lang.String value) { this.id = value; }
    private java.time.LocalDate date;
    public java.time.LocalDate getDate() { return date; }
    public void setDate(java.time.LocalDate value) { this.date = value; }
    private java.lang.String reason;
    public java.lang.String getReason() { return reason; }
    public void setReason(java.lang.String value) { this.reason = value; }
    private java.time.LocalDate resumeOn;
    public java.time.LocalDate getResumeOn() { return resumeOn; }
    public void setResumeOn(java.time.LocalDate value) { this.resumeOn = value; }
}
