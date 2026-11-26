package example.todos.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 確定した画面入力とメッセージから生成。空文字のみ null にし、空白は保持する。 */
public class CompleteTodoForm {
@jakarta.validation.constraints.NotNull(message = "完了日を入力してください")
@org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)

    private LocalDate date;
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate value) { this.date = value; }
@jakarta.validation.constraints.NotNull(message = "完了結果を入力してください")
@jakarta.validation.constraints.Size(message = "完了結果は1文字以上で入力してください", min = 1)

    private String result;
    public String getResult() { return result; }
    public void setResult(String value) { this.result = (value != null && value.isEmpty()) ? null : value; }
@jakarta.validation.constraints.NotNull(message = "版を受け取れませんでした。画面を開き直してから、もう一度操作してください")

    private Long version;
    public Long getVersion() { return version; }
    public void setVersion(Long value) { this.version = value; }
}
