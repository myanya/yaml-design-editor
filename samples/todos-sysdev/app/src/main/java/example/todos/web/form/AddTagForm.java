package example.todos.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 確定した画面入力とメッセージから生成。空文字のみ null にし、空白は保持する。 */
public class AddTagForm {
@jakarta.validation.constraints.NotNull(message = "タグ名を入力してください")
@jakarta.validation.constraints.Size(message = "タグ名は100文字以内で入力してください", min = 1, max = 100)
@jakarta.validation.constraints.Pattern(message = "タグ名の書き方が正しくありません", regexp = "^[^\\r\\n]*$")

    private String name;
    public String getName() { return name; }
    public void setName(String value) { this.name = (value != null && value.isEmpty()) ? null : value; }
}
