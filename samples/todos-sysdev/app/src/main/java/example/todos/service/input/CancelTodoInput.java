package example.todos.service.input;

/** 確定した操作入力と Service の引数から生成。 */
public class CancelTodoInput {
    private java.lang.String id;
    public java.lang.String getId() { return id; }
    public void setId(java.lang.String value) { this.id = value; }
    private java.lang.String reason;
    public java.lang.String getReason() { return reason; }
    public void setReason(java.lang.String value) { this.reason = value; }
    private java.lang.Long version;
    public java.lang.Long getVersion() { return version; }
    public void setVersion(java.lang.Long value) { this.version = value; }
}
