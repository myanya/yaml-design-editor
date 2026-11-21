package example.todos.service.input;

/** 確定した操作入力と Service の引数から生成。 */
public class AddTodoCommentInput {
    private java.lang.String id;
    public java.lang.String getId() { return id; }
    public void setId(java.lang.String value) { this.id = value; }
    private java.time.LocalDate date;
    public java.time.LocalDate getDate() { return date; }
    public void setDate(java.time.LocalDate value) { this.date = value; }
    private java.lang.String body;
    public java.lang.String getBody() { return body; }
    public void setBody(java.lang.String value) { this.body = value; }
}
