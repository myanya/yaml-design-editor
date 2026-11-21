package example.todos.service.input;

/** 確定した操作入力と Service の引数から生成。 */
public class RegisterTodoInput {
    private java.lang.String title;
    public java.lang.String getTitle() { return title; }
    public void setTitle(java.lang.String value) { this.title = value; }
    private java.lang.String summary;
    public java.lang.String getSummary() { return summary; }
    public void setSummary(java.lang.String value) { this.summary = value; }
    private java.lang.String category;
    public java.lang.String getCategory() { return category; }
    public void setCategory(java.lang.String value) { this.category = value; }
    private java.util.List<java.lang.String> tags;
    public java.util.List<java.lang.String> getTags() { return tags; }
    public void setTags(java.util.List<java.lang.String> value) { this.tags = value; }
    private java.lang.String follows;
    public java.lang.String getFollows() { return follows; }
    public void setFollows(java.lang.String value) { this.follows = value; }
    private java.time.LocalDate due;
    public java.time.LocalDate getDue() { return due; }
    public void setDue(java.time.LocalDate value) { this.due = value; }
    private java.lang.String details;
    public java.lang.String getDetails() { return details; }
    public void setDetails(java.lang.String value) { this.details = value; }
}
