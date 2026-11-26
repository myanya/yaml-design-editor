package example.todos.web.operation;

import example.todos.web.form.*;
import example.todos.service.TodoService;
import example.todos.service.input.AddTodoCommentInput;
import example.todos.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class AddTodoCommentWebOperation {
    private final TodoService service;
    private final Validator validator;

    public AddTodoCommentWebOperation(TodoService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    public String execute(String id, AddTodoCommentForm form) {
        var input = toInput(id, form);
        return service.addTodoComment(input.getId(), input.getDate(), input.getBody(), input.getVersion());
    }
    public AddTodoCommentInput toInput(String id, AddTodoCommentForm form) {
        if (form == null) throw new IllegalArgumentException("入力フォームがありません");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("対象の ID がありません");
        var input = new AddTodoCommentInput();
        input.setId(id);
        input.setDate(form.getDate());
        input.setBody(form.getBody());
        input.setVersion(form.getVersion());
        return input;
    }
}
