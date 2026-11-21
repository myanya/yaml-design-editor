package example.todos.web;

import example.todos.web.form.RegisterTodoForm;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** 画面部品と入力検査の確認用。Service と DB は呼ばない。 */
@Controller
public class DesignPreviewController {
    @GetMapping("/design-preview")
    public String show(Model model) {
        model.addAttribute("registerTodoForm", new RegisterTodoForm());
        model.addAttribute("accepted", false);
        return "design-preview";
    }

    @PostMapping("/design-preview")
    public String check(@Valid @ModelAttribute("registerTodoForm") RegisterTodoForm form, BindingResult binding, Model model) {
        model.addAttribute("accepted", !binding.hasErrors());
        return "design-preview";
    }
}
