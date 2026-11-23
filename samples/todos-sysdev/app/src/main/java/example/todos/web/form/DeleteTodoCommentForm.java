package example.todos.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 確定した画面入力とメッセージから生成。空文字のみ null にし、空白は保持する。 */
public class DeleteTodoCommentForm {
@jakarta.validation.constraints.NotNull(message = "コメントの番号を入力してください")

    private Long row;
    public Long getRow() { return row; }
    public void setRow(Long value) { this.row = value; }
}
