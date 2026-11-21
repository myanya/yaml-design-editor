package example.todos.web;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** 確定した画面遷移設計の URL とハンドラ。入力保持と表示は共通処理へ渡す。 */
@Controller
public class TagController {
    private final WebRuntime runtime;
    public TagController(WebRuntime runtime) {this.runtime=runtime;}
    @RequestMapping(path="/tag/{id}",method=RequestMethod.GET)
    public String show(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/tag/add-tag",method=RequestMethod.GET)
    public String addTagInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/tag/add-tag/confirm",method=RequestMethod.POST)
    public String addTagConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/tag/add-tag/back",method=RequestMethod.POST)
    public String addTagBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/tag/add-tag",method=RequestMethod.POST)
    public String addTag(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
}
