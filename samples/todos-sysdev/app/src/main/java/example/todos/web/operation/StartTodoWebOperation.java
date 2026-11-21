package example.todos.web.operation;

import example.todos.web.form.*;
import example.todos.service.TodoService;
import example.todos.service.input.StartTodoInput;
import example.todos.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class StartTodoWebOperation {
    private final TodoService service;
    private final Validator validator;

    public StartTodoWebOperation(TodoService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    public String execute(String id) {
        var input = toInput(id);
        return service.startTodo(input.getId());
    }
    public StartTodoInput toInput(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("対象の ID がありません");
        var input = new StartTodoInput();
        input.setId(id);
        return input;
    }
}
