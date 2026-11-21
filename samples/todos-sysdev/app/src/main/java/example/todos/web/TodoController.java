package example.todos.web;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** 確定した画面遷移設計の URL とハンドラ。入力保持と表示は共通処理へ渡す。 */
@Controller
public class TodoController {
    private final WebRuntime runtime;
    public TodoController(WebRuntime runtime) {this.runtime=runtime;}
    @RequestMapping(path="/todo/{id}",method=RequestMethod.GET)
    public String show(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/register-todo",method=RequestMethod.GET)
    public String registerTodoInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/register-todo/confirm",method=RequestMethod.POST)
    public String registerTodoConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/register-todo/back",method=RequestMethod.POST)
    public String registerTodoBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/register-todo",method=RequestMethod.POST)
    public String registerTodo(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/add-todo-comment",method=RequestMethod.GET)
    public String addTodoCommentInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/add-todo-comment/confirm",method=RequestMethod.POST)
    public String addTodoCommentConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/add-todo-comment/back",method=RequestMethod.POST)
    public String addTodoCommentBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/add-todo-comment",method=RequestMethod.POST)
    public String addTodoComment(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/add-todo-task",method=RequestMethod.GET)
    public String addTodoTaskInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/add-todo-task/confirm",method=RequestMethod.POST)
    public String addTodoTaskConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/add-todo-task/back",method=RequestMethod.POST)
    public String addTodoTaskBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/add-todo-task",method=RequestMethod.POST)
    public String addTodoTask(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/edit-todo",method=RequestMethod.GET)
    public String editTodoInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/edit-todo/confirm",method=RequestMethod.POST)
    public String editTodoConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/edit-todo/back",method=RequestMethod.POST)
    public String editTodoBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/edit-todo",method=RequestMethod.POST)
    public String editTodo(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/start-todo/confirm",method=RequestMethod.GET)
    public String startTodoConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/start-todo",method=RequestMethod.POST)
    public String startTodo(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/suspend-todo",method=RequestMethod.GET)
    public String suspendTodoInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/suspend-todo/confirm",method=RequestMethod.POST)
    public String suspendTodoConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/suspend-todo/back",method=RequestMethod.POST)
    public String suspendTodoBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/suspend-todo",method=RequestMethod.POST)
    public String suspendTodo(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/resume-todo",method=RequestMethod.GET)
    public String resumeTodoInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/resume-todo/confirm",method=RequestMethod.POST)
    public String resumeTodoConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/resume-todo/back",method=RequestMethod.POST)
    public String resumeTodoBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/resume-todo",method=RequestMethod.POST)
    public String resumeTodo(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/complete-todo",method=RequestMethod.GET)
    public String completeTodoInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/complete-todo/confirm",method=RequestMethod.POST)
    public String completeTodoConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/complete-todo/back",method=RequestMethod.POST)
    public String completeTodoBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/complete-todo",method=RequestMethod.POST)
    public String completeTodo(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/cancel-todo",method=RequestMethod.GET)
    public String cancelTodoInput(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/cancel-todo/confirm",method=RequestMethod.POST)
    public String cancelTodoConfirm(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/cancel-todo/back",method=RequestMethod.POST)
    public String cancelTodoBack(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
    @RequestMapping(path="/todo/{id}/cancel-todo",method=RequestMethod.POST)
    public String cancelTodo(HttpServletRequest request,HttpServletResponse response,Model model) {
        return runtime.handle(request,response,model);
    }
}
