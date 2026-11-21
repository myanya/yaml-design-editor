package example.todos.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 確定した画面入力とメッセージから生成。空文字のみ null にし、空白は保持する。 */
public class AddTodoCommentForm {
@jakarta.validation.constraints.NotNull(message = "記録日を入力してください")
@org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)

    private LocalDate date;
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate value) { this.date = value; }
@jakarta.validation.constraints.NotNull(message = "コメント本文を入力してください")
@jakarta.validation.constraints.Size(message = "コメント本文は1文字以上で入力してください", min = 1)

    private String body;
    public String getBody() { return body; }
    public void setBody(String value) { this.body = (value != null && value.isEmpty()) ? null : value; }
}
