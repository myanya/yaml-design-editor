package example.todos.web.operation;

import example.todos.web.form.*;
import example.todos.service.TodoService;
import example.todos.service.input.CancelTodoInput;
import example.todos.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class CancelTodoWebOperation {
    private final TodoService service;
    private final Validator validator;

    public CancelTodoWebOperation(TodoService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    public String execute(String id, CancelTodoForm form) {
        var input = toInput(id, form);
        return service.cancelTodo(input.getId(), input.getReason(), input.getVersion());
    }
    public CancelTodoInput toInput(String id, CancelTodoForm form) {
        if (form == null) throw new IllegalArgumentException("入力フォームがありません");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("対象の ID がありません");
        var input = new CancelTodoInput();
        input.setId(id);
        input.setReason(form.getReason());
        input.setVersion(form.getVersion());
        return input;
    }
}
