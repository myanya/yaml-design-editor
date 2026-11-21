package example.todos.web.operation;

import example.todos.web.form.*;
import example.todos.service.CategoryService;
import example.todos.service.input.AddCategoryInput;
import example.todos.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class AddCategoryWebOperation {
    private final CategoryService service;
    private final Validator validator;

    public AddCategoryWebOperation(CategoryService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    public String execute(AddCategoryForm form) {
        var input = toInput(form);
        return service.addCategory(input.getName());
    }
    public AddCategoryInput toInput(AddCategoryForm form) {
        if (form == null) throw new IllegalArgumentException("入力フォームがありません");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        var input = new AddCategoryInput();
        input.setName(form.getName());
        return input;
    }
}
