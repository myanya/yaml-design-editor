package example.todos.web.operation;

import example.todos.web.form.*;
import example.todos.service.TagService;
import example.todos.service.input.AddTagInput;
import example.todos.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class AddTagWebOperation {
    private final TagService service;
    private final Validator validator;

    public AddTagWebOperation(TagService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    public String execute(AddTagForm form) {
        var input = toInput(form);
        return service.addTag(input.getName());
    }
    public AddTagInput toInput(AddTagForm form) {
        if (form == null) throw new IllegalArgumentException("入力フォームがありません");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        var input = new AddTagInput();
        input.setName(form.getName());
        return input;
    }
}
