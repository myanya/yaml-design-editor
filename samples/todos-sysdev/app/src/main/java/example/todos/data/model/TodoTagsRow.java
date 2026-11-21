package example.todos.data.model;

/** 確定したテーブル設計から生成した行。初期値は DB の DEFAULT として扱います。 */
public class TodoTagsRow {
    private java.lang.String todoId;
    private java.lang.Long seq;
    private java.lang.String tagId;
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

    public java.lang.String getTagId() {
        return tagId;
    }

    public void setTagId(java.lang.String tagId) {
        this.tagId = tagId;
    }

}
