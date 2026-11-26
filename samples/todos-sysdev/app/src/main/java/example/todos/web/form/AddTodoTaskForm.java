package example.todos.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 確定した画面入力とメッセージから生成。空文字のみ null にし、空白は保持する。 */
public class AddTodoTaskForm {
@jakarta.validation.constraints.NotNull(message = "作業を入力してください")
@jakarta.validation.constraints.Size(message = "作業は100文字以内で入力してください", min = 1, max = 100)
@jakarta.validation.constraints.Pattern(message = "作業の書き方が正しくありません", regexp = "^[^\\r\\n]*$")

    private String title;
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = (value != null && value.isEmpty()) ? null : value; }
@jakarta.validation.constraints.NotNull(message = "版を受け取れませんでした。画面を開き直してから、もう一度操作してください")

    private Long version;
    public Long getVersion() { return version; }
    public void setVersion(Long value) { this.version = value; }
}
