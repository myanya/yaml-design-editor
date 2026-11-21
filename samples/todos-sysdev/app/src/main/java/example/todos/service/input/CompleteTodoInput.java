package example.todos.service.input;

/** 確定した操作入力と Service の引数から生成。 */
public class CompleteTodoInput {
    private java.lang.String id;
    public java.lang.String getId() { return id; }
    public void setId(java.lang.String value) { this.id = value; }
    private java.time.LocalDate date;
    public java.time.LocalDate getDate() { return date; }
    public void setDate(java.time.LocalDate value) { this.date = value; }
    private java.lang.String result;
    public java.lang.String getResult() { return result; }
    public void setResult(java.lang.String value) { this.result = value; }
}
