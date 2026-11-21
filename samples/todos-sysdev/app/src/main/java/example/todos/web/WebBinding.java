package example.todos.web;

import org.springframework.validation.DefaultMessageCodesResolver;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/** 確定メッセージの validation. 接頭辞と Spring の入力検査を接続する。 */
@ControllerAdvice
public class WebBinding {
    @InitBinder
    public void configure(WebDataBinder binder) {
        var resolver = new DefaultMessageCodesResolver();
        resolver.setPrefix("validation.");
        binder.setMessageCodesResolver(resolver);
    }
}
