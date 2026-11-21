package example.todos.web.operation;

import example.todos.web.form.*;
import example.todos.service.TodoService;
import example.todos.service.input.EditTodoInput;
import example.todos.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class EditTodoWebOperation {
    private final TodoService service;
    private final Validator validator;

    public EditTodoWebOperation(TodoService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    public String execute(String id, EditTodoForm form) {
        var input = toInput(id, form);
        return service.editTodo(input.getId(), input.getTitle(), input.getSummary(), input.getCategory(), input.getTags(), input.getCreatedOn(), input.getDueOn(), input.getDetails(), input.getResult(), input.getComments(), input.getClear());
    }
    public java.util.List<BusinessViolation> check(String id, EditTodoForm form) {
        var input = toInput(id, form);
        return service.checkEditTodo(input.getId(), input.getTitle(), input.getSummary(), input.getCategory(), input.getTags(), input.getCreatedOn(), input.getDueOn(), input.getDetails(), input.getResult(), input.getComments(), input.getClear());
    }
    public EditTodoInput toInput(String id, EditTodoForm form) {
        if (form == null) throw new IllegalArgumentException("入力フォームがありません");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("対象の ID がありません");
        var input = new EditTodoInput();
        input.setId(id);
        input.setTitle(form.getTitle());
        input.setSummary(form.getSummary());
        input.setCategory(form.getCategory());
        input.setTags(form.getTags() == null ? null : new java.util.ArrayList<>(form.getTags()));
        input.setCreatedOn(form.getCreatedOn());
        input.setDueOn(form.getDueOn());
        input.setDetails(form.getDetails());
        input.setResult(form.getResult());
        if (form.getComments() != null) {
            var valuesComments = new java.util.ArrayList<EditTodoInput.EditTodoCommentsItem>();
            for (var rowComments : form.getComments()) {
                if (rowComments == null) throw new IllegalArgumentException("入力の明細に空の行があります");
            var valueComments = new EditTodoInput.EditTodoCommentsItem();
        valueComments.setDate(rowComments.getDate());
        valueComments.setBody(rowComments.getBody());
                valuesComments.add(valueComments);
            }
            input.setComments(valuesComments);
        }
        if (form.getClear() != null) {
            var valueClear = new EditTodoInput.EditTodoClear();
        valueClear.setCategory(form.getClear().getCategory());
        valueClear.setTags(form.getClear().getTags());
        valueClear.setDueOn(form.getClear().getDueOn());
        valueClear.setDetails(form.getClear().getDetails());
        valueClear.setResult(form.getClear().getResult());
        valueClear.setComments(form.getClear().getComments());
            input.setClear(valueClear);
        }
        return input;
    }
}
