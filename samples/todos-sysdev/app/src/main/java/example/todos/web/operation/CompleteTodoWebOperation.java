package example.todos.web.operation;

import example.todos.web.form.*;
import example.todos.service.TodoService;
import example.todos.service.input.CompleteTodoInput;
import example.todos.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class CompleteTodoWebOperation {
    private final TodoService service;
    private final Validator validator;

    public CompleteTodoWebOperation(TodoService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    public String execute(String id, CompleteTodoForm form) {
        var input = toInput(id, form);
        return service.completeTodo(input.getId(), input.getDate(), input.getResult());
    }
    public java.util.List<BusinessViolation> check(String id, CompleteTodoForm form) {
        var input = toInput(id, form);
        return service.checkCompleteTodo(input.getId(), input.getDate(), input.getResult());
    }
    public CompleteTodoInput toInput(String id, CompleteTodoForm form) {
        if (form == null) throw new IllegalArgumentException("入力フォームがありません");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("対象の ID がありません");
        var input = new CompleteTodoInput();
        input.setId(id);
        input.setDate(form.getDate());
        input.setResult(form.getResult());
        return input;
    }
}
