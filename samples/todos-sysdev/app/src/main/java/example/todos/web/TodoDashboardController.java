package example.todos.web;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** 確定した画面遷移設計の URL とハンドラ。入力保持と表示は共通処理へ渡す。 */
@Controller
public class TodoDashboardController {
    private final WebRuntime runtime;
    public TodoDashboardController(WebRuntime runtime) {this.runtime=runtime;}
    @RequestMapping(path="/",method=RequestMethod.GET)
    public String show(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
}
