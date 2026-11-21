package example.todos.web.operation;

import example.todos.web.form.*;
import example.todos.service.TodoTaskService;
import example.todos.service.input.TodoTaskFinishInput;
import example.todos.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class TodoTaskFinishWebOperation {
    private final TodoTaskService service;
    private final Validator validator;

    public TodoTaskFinishWebOperation(TodoTaskService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    public String execute(String id) {
        var input = toInput(id);
        return service.todoTaskFinish(input.getId());
    }
    public TodoTaskFinishInput toInput(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("対象の ID がありません");
        var input = new TodoTaskFinishInput();
        input.setId(id);
        return input;
    }
}
