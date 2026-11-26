package example.todos.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 確定した画面入力とメッセージから生成。空文字のみ null にし、空白は保持する。 */
public class CancelTodoForm {
@jakarta.validation.constraints.NotNull(message = "中止理由を入力してください")
@jakarta.validation.constraints.Size(message = "中止理由は1文字以上で入力してください", min = 1)

    private String reason;
    public String getReason() { return reason; }
    public void setReason(String value) { this.reason = (value != null && value.isEmpty()) ? null : value; }
@jakarta.validation.constraints.NotNull(message = "版を受け取れませんでした。画面を開き直してから、もう一度操作してください")

    private Long version;
    public Long getVersion() { return version; }
    public void setVersion(Long value) { this.version = value; }
}
