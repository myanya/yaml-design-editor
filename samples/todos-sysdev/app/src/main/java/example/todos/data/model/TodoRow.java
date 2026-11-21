package example.todos.data.model;

/** 確定したテーブル設計から生成した行。初期値は DB の DEFAULT として扱います。 */
public class TodoRow {
    private java.lang.String id;
    private java.lang.String title;
    private java.lang.String summary;
    private java.lang.String category;
    private java.lang.String follows;
    private java.lang.String status;
    private java.time.LocalDate createdOn;
    private java.time.LocalDate updatedOn;
    private java.time.LocalDate dueOn;
    private java.time.LocalDate completedOn;
    private java.lang.String details;
    private java.lang.String result;
    public java.lang.String getId() {
        return id;
    }

    public void setId(java.lang.String id) {
        this.id = id;
    }

    public java.lang.String getTitle() {
        return title;
    }

    public void setTitle(java.lang.String title) {
        this.title = title;
    }

    public java.lang.String getSummary() {
        return summary;
    }

    public void setSummary(java.lang.String summary) {
        this.summary = summary;
    }

    public java.lang.String getCategory() {
        return category;
    }

    public void setCategory(java.lang.String category) {
        this.category = category;
    }

    public java.lang.String getFollows() {
        return follows;
    }

    public void setFollows(java.lang.String follows) {
        this.follows = follows;
    }

    public java.lang.String getStatus() {
        return status;
    }

    public void setStatus(java.lang.String status) {
        this.status = status;
    }

    public java.time.LocalDate getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(java.time.LocalDate createdOn) {
        this.createdOn = createdOn;
    }

    public java.time.LocalDate getUpdatedOn() {
        return updatedOn;
    }

    public void setUpdatedOn(java.time.LocalDate updatedOn) {
        this.updatedOn = updatedOn;
    }

    public java.time.LocalDate getDueOn() {
        return dueOn;
    }

    public void setDueOn(java.time.LocalDate dueOn) {
        this.dueOn = dueOn;
    }

    public java.time.LocalDate getCompletedOn() {
        return completedOn;
    }

    public void setCompletedOn(java.time.LocalDate completedOn) {
        this.completedOn = completedOn;
    }

    public java.lang.String getDetails() {
        return details;
    }

    public void setDetails(java.lang.String details) {
        this.details = details;
    }

    public java.lang.String getResult() {
        return result;
    }

    public void setResult(java.lang.String result) {
        this.result = result;
    }

}
