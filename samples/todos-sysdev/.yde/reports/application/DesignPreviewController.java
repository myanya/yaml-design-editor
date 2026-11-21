package {#each config.settings.package}{.}{#if more}.{/if}{/each}.web;

import {#each config.settings.package}{.}{#if more}.{/if}{/each}.web.form.{previewForm.className};
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** 画面部品と入力検査の確認用。Service と DB は呼ばない。 */
@Controller
public class DesignPreviewController {{
    @GetMapping("/design-preview")
    public String show(Model model) {{
        model.addAttribute({previewForm.beanName:java-string}, new {previewForm.className}());
        model.addAttribute("accepted", false);
        return "design-preview";
    }

    @PostMapping("/design-preview")
    public String check(@Valid @ModelAttribute({previewForm.beanName:java-string}) {previewForm.className} form, BindingResult binding, Model model) {{
        model.addAttribute("accepted", !binding.hasErrors());
        return "design-preview";
    }
}
