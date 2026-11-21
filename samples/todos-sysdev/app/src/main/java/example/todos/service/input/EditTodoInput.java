package example.todos.service.input;

/** 確定した操作入力と Service の引数から生成。 */
public class EditTodoInput {
    private java.lang.String id;
    public java.lang.String getId() { return id; }
    public void setId(java.lang.String value) { this.id = value; }
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
    private java.time.LocalDate createdOn;
    public java.time.LocalDate getCreatedOn() { return createdOn; }
    public void setCreatedOn(java.time.LocalDate value) { this.createdOn = value; }
    private java.time.LocalDate dueOn;
    public java.time.LocalDate getDueOn() { return dueOn; }
    public void setDueOn(java.time.LocalDate value) { this.dueOn = value; }
    private java.lang.String details;
    public java.lang.String getDetails() { return details; }
    public void setDetails(java.lang.String value) { this.details = value; }
    private java.lang.String result;
    public java.lang.String getResult() { return result; }
    public void setResult(java.lang.String value) { this.result = value; }
    private java.util.List<EditTodoCommentsItem> comments;
    public java.util.List<EditTodoCommentsItem> getComments() { return comments; }
    public void setComments(java.util.List<EditTodoCommentsItem> value) { this.comments = value; }
    private EditTodoClear clear;
    public EditTodoClear getClear() { return clear; }
    public void setClear(EditTodoClear value) { this.clear = value; }
    public static class EditTodoCommentsItem {
        private java.time.LocalDate date;
        public java.time.LocalDate getDate() { return date; }
        public void setDate(java.time.LocalDate value) { this.date = value; }
        private java.lang.String body;
        public java.lang.String getBody() { return body; }
        public void setBody(java.lang.String value) { this.body = value; }
    }
    public static class EditTodoClear {
        private java.lang.Boolean category;
        public java.lang.Boolean getCategory() { return category; }
        public void setCategory(java.lang.Boolean value) { this.category = value; }
        private java.lang.Boolean tags;
        public java.lang.Boolean getTags() { return tags; }
        public void setTags(java.lang.Boolean value) { this.tags = value; }
        private java.lang.Boolean dueOn;
        public java.lang.Boolean getDueOn() { return dueOn; }
        public void setDueOn(java.lang.Boolean value) { this.dueOn = value; }
        private java.lang.Boolean details;
        public java.lang.Boolean getDetails() { return details; }
        public void setDetails(java.lang.Boolean value) { this.details = value; }
        private java.lang.Boolean result;
        public java.lang.Boolean getResult() { return result; }
        public void setResult(java.lang.Boolean value) { this.result = value; }
        private java.lang.Boolean comments;
        public java.lang.Boolean getComments() { return comments; }
        public void setComments(java.lang.Boolean value) { this.comments = value; }
    }
}
