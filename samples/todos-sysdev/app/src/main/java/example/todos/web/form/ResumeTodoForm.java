package example.todos.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 確定した画面入力とメッセージから生成。空文字のみ null にし、空白は保持する。 */
public class ResumeTodoForm {
@jakarta.validation.constraints.NotNull(message = "再開日を入力してください")
@org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)

    private LocalDate date;
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate value) { this.date = value; }

    private String note;
    public String getNote() { return note; }
    public void setNote(String value) { this.note = (value != null && value.isEmpty()) ? null : value; }
@jakarta.validation.constraints.NotNull(message = "版を受け取れませんでした。画面を開き直してから、もう一度操作してください")

    private Long version;
    public Long getVersion() { return version; }
    public void setVersion(Long value) { this.version = value; }
}
