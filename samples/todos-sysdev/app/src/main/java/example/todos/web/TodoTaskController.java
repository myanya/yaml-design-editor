package example.todos.web;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** 確定した画面遷移設計の URL とハンドラ。入力保持と表示は共通処理へ渡す。 */
@Controller
public class TodoTaskController {
    private final WebRuntime runtime;
    public TodoTaskController(WebRuntime runtime) {this.runtime=runtime;}
    @RequestMapping(path="/todo-task/{id}/todo-task.finish/confirm",method=RequestMethod.GET)
    public String todoTaskFinishConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo-task/{id}/todo-task.finish",method=RequestMethod.POST)
    public String todoTaskFinish(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
}
