package example.todos.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 確定した画面入力とメッセージから生成。空文字のみ null にし、空白は保持する。 */
public class EditTodoForm {
@jakarta.validation.constraints.Size(message = "タイトルは100文字以内で入力してください", min = 1, max = 100)
@jakarta.validation.constraints.Pattern(message = "タイトルの書き方が正しくありません", regexp = "^[^\\r\\n]*$")

    private String title;
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = (value != null && value.isEmpty()) ? null : value; }
@jakarta.validation.constraints.Size(message = "概要は100文字以内で入力してください", min = 1, max = 100)
@jakarta.validation.constraints.Pattern(message = "概要の書き方が正しくありません", regexp = "^[^\\r\\n]*$")

    private String summary;
    public String getSummary() { return summary; }
    public void setSummary(String value) { this.summary = (value != null && value.isEmpty()) ? null : value; }

    private String category;
    public String getCategory() { return category; }
    public void setCategory(String value) { this.category = (value != null && value.isEmpty()) ? null : value; }
@org.hibernate.validator.constraints.UniqueElements(message = "タグに同じものを 2 回選べません")

    private List<String> tags;
    public List<String> getTags() { return tags; }
    public void setTags(List<String> value) { this.tags = value; }
@org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)

    private LocalDate createdOn;
    public LocalDate getCreatedOn() { return createdOn; }
    public void setCreatedOn(LocalDate value) { this.createdOn = value; }
@org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)

    private LocalDate dueOn;
    public LocalDate getDueOn() { return dueOn; }
    public void setDueOn(LocalDate value) { this.dueOn = value; }

    private String details;
    public String getDetails() { return details; }
    public void setDetails(String value) { this.details = (value != null && value.isEmpty()) ? null : value; }

    private String result;
    public String getResult() { return result; }
    public void setResult(String value) { this.result = (value != null && value.isEmpty()) ? null : value; }

    private EditTodoClear clear;
    public EditTodoClear getClear() { return clear; }
    public void setClear(EditTodoClear value) { this.clear = value; }
    public static class EditTodoClear {

        private Boolean category;
        public Boolean getCategory() { return category; }
        public void setCategory(Boolean value) { this.category = value; }

        private Boolean tags;
        public Boolean getTags() { return tags; }
        public void setTags(Boolean value) { this.tags = value; }

        private Boolean dueOn;
        public Boolean getDueOn() { return dueOn; }
        public void setDueOn(Boolean value) { this.dueOn = value; }

        private Boolean details;
        public Boolean getDetails() { return details; }
        public void setDetails(Boolean value) { this.details = value; }

        private Boolean result;
        public Boolean getResult() { return result; }
        public void setResult(Boolean value) { this.result = value; }
    }
}
