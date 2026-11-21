package example.todos.web.operation;

import example.todos.web.form.*;
import example.todos.service.TodoService;
import example.todos.service.input.RegisterTodoInput;
import example.todos.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class RegisterTodoWebOperation {
    private final TodoService service;
    private final Validator validator;

    public RegisterTodoWebOperation(TodoService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    public String execute(RegisterTodoForm form) {
        var input = toInput(form);
        return service.registerTodo(input.getTitle(), input.getSummary(), input.getCategory(), input.getTags(), input.getFollows(), input.getDue(), input.getDetails());
    }
    public java.util.List<BusinessViolation> check(RegisterTodoForm form) {
        var input = toInput(form);
        return service.checkRegisterTodo(input.getTitle(), input.getSummary(), input.getCategory(), input.getTags(), input.getFollows(), input.getDue(), input.getDetails());
    }
    public RegisterTodoInput toInput(RegisterTodoForm form) {
        if (form == null) throw new IllegalArgumentException("入力フォームがありません");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        var input = new RegisterTodoInput();
        input.setTitle(form.getTitle());
        input.setSummary(form.getSummary());
        input.setCategory(form.getCategory());
        input.setTags(form.getTags() == null ? null : new java.util.ArrayList<>(form.getTags()));
        input.setFollows(form.getFollows());
        input.setDue(form.getDue());
        input.setDetails(form.getDetails());
        return input;
    }
}
