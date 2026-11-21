package example.todos.web;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** 確定した画面遷移設計の URL とハンドラ。入力保持と表示は共通処理へ渡す。 */
@Controller
public class CategoryController {
    private final WebRuntime runtime;
    public CategoryController(WebRuntime runtime) {this.runtime=runtime;}
    @RequestMapping(path="/category/{id}",method=RequestMethod.GET)
    public String show(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/category/add-category",method=RequestMethod.GET)
    public String addCategoryInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/category/add-category/confirm",method=RequestMethod.POST)
    public String addCategoryConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/category/add-category/back",method=RequestMethod.POST)
    public String addCategoryBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/category/add-category",method=RequestMethod.POST)
    public String addCategory(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
}
