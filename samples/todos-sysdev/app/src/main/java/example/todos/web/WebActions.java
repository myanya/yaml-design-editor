package example.todos.web;

import java.util.*;
import java.util.function.*;
import org.springframework.stereotype.Component;
import example.todos.web.form.*;
import example.todos.web.operation.*;
import example.todos.service.error.BusinessViolation;

/** 画面と Service の対応検査を通った呼出しだけを登録する。 */
@Component
public class WebActions {
    record Action(Supplier<Object> form, BiFunction<String,Object,String> execute,
                  BiFunction<String,Object,List<BusinessViolation>> check) {}
    final Map<String,Action> actions=new LinkedHashMap<>();
    public WebActions(AddCategoryWebOperation op0, AddTagWebOperation op1, AddTodoCommentWebOperation op2, AddTodoTaskWebOperation op3, CancelTodoWebOperation op4, CompleteTodoWebOperation op5, DeleteTodoCommentWebOperation op6, DeleteTodoTaskWebOperation op7, EditTodoWebOperation op8, EditTodoCommentWebOperation op9, RegisterTodoWebOperation op10, ResumeTodoWebOperation op11, StartTodoWebOperation op12, SuspendTodoWebOperation op13, TodoTaskFinishWebOperation op14) {
        actions.put("add-category",new Action(
            () -> new AddCategoryForm(),
            (id,form) -> op0.execute((AddCategoryForm)form),
            (id,form) -> List.of()));
        actions.put("add-tag",new Action(
            () -> new AddTagForm(),
            (id,form) -> op1.execute((AddTagForm)form),
            (id,form) -> List.of()));
        actions.put("add-todo-comment",new Action(
            () -> new AddTodoCommentForm(),
            (id,form) -> op2.execute(id, (AddTodoCommentForm)form),
            (id,form) -> List.of()));
        actions.put("add-todo-task",new Action(
            () -> new AddTodoTaskForm(),
            (id,form) -> op3.execute(id, (AddTodoTaskForm)form),
            (id,form) -> List.of()));
        actions.put("cancel-todo",new Action(
            () -> new CancelTodoForm(),
            (id,form) -> op4.execute(id, (CancelTodoForm)form),
            (id,form) -> List.of()));
        actions.put("complete-todo",new Action(
            () -> new CompleteTodoForm(),
            (id,form) -> op5.execute(id, (CompleteTodoForm)form),
            (id,form) -> op5.check(id, (CompleteTodoForm)form)));
        actions.put("delete-todo-comment",new Action(
            () -> new DeleteTodoCommentForm(),
            (id,form) -> op6.execute(id, (DeleteTodoCommentForm)form),
            (id,form) -> List.of()));
        actions.put("delete-todo-task",new Action(
            () -> new DeleteTodoTaskForm(),
            (id,form) -> op7.execute(id, (DeleteTodoTaskForm)form),
            (id,form) -> List.of()));
        actions.put("edit-todo",new Action(
            () -> new EditTodoForm(),
            (id,form) -> op8.execute(id, (EditTodoForm)form),
            (id,form) -> op8.check(id, (EditTodoForm)form)));
        actions.put("edit-todo-comment",new Action(
            () -> new EditTodoCommentForm(),
            (id,form) -> op9.execute(id, (EditTodoCommentForm)form),
            (id,form) -> List.of()));
        actions.put("register-todo",new Action(
            () -> new RegisterTodoForm(),
            (id,form) -> op10.execute((RegisterTodoForm)form),
            (id,form) -> op10.check((RegisterTodoForm)form)));
        actions.put("resume-todo",new Action(
            () -> new ResumeTodoForm(),
            (id,form) -> op11.execute(id, (ResumeTodoForm)form),
            (id,form) -> List.of()));
        actions.put("start-todo",new Action(
            () -> new StartTodoForm(),
            (id,form) -> op12.execute(id, (StartTodoForm)form),
            (id,form) -> List.of()));
        actions.put("suspend-todo",new Action(
            () -> new SuspendTodoForm(),
            (id,form) -> op13.execute(id, (SuspendTodoForm)form),
            (id,form) -> op13.check(id, (SuspendTodoForm)form)));
        actions.put("todo-task.finish",new Action(
            () -> new TodoTaskFinishForm(),
            (id,form) -> op14.execute(id, (TodoTaskFinishForm)form),
            (id,form) -> List.of()));
    }
}
