package example.todos.service;

import example.todos.data.model.*;
import example.todos.data.mapper.*;
import example.todos.service.input.*;
import example.todos.service.runtime.*;
import example.todos.service.error.BusinessViolation;
import org.springframework.stereotype.Service;

/** 確定した処理設計・入力・Mapper の対応から生成。 */
@Service
public class TodoTaskService {
    private final OperationRunner runner;
    private final java.time.Clock clock;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final TodoTaskMapper todoTaskMapper;
    private final TodoMapper todoMapper;
    public TodoTaskService(OperationRunner runner, java.time.Clock clock, CategoryMapper categoryMapper, TagMapper tagMapper, TodoTaskMapper todoTaskMapper, TodoMapper todoMapper) {
        this.runner = runner;
        this.clock = clock;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.todoTaskMapper = todoTaskMapper;
        this.todoMapper = todoMapper;
    }
    public String todoTaskFinish(java.lang.String id) {
        var input = new TodoTaskFinishInput();
        input.setId(id);
        return runner.execute(() -> runtodoTaskFinish(input, false)).id();
    }
    private OperationOutcome runtodoTaskFinish(TodoTaskFinishInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoTaskMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoTaskMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo-task", input.getId());
        var rows3 = new java.util.ArrayList<TodoTaskRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row3 = new TodoTaskRow();
            row3.setDone(false);
            var before3 = original;
            row3.setId(before3.getId());
            row3.setTodoId(before3.getTodoId());
            row3.setSeq(before3.getSeq());
            row3.setTitle(before3.getTitle());
            row3.setDone(before3.getDone());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row3.setDone(true);
            } else {}
            rows3.add(row3);
        }
        var rows4 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row4 = new TodoRow();
            row4.setStatus("OPEN");
            row4.setVersion(0L);
            var before4 = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> p.setId(original.getTodoId()), todoMapper::findById);
            if (before4 == null) throw new example.todos.service.error.NotFoundException("todo", original.getTodoId());
            row4.setId(before4.getId());
            row4.setTitle(before4.getTitle());
            row4.setSummary(before4.getSummary());
            row4.setCategory(before4.getCategory());
            row4.setFollows(before4.getFollows());
            row4.setStatus(before4.getStatus());
            row4.setCreatedOn(before4.getCreatedOn());
            row4.setUpdatedOn(before4.getUpdatedOn());
            row4.setDueOn(before4.getDueOn());
            row4.setCompletedOn(before4.getCompletedOn());
            row4.setDetails(before4.getDetails());
            row4.setResult(before4.getResult());
            row4.setVersion(before4.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row4.setUpdatedOn(today);
            } else {}
            rows4.add(row4);
        }
        for (var checked : rows4) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, original.getTodoId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows3.isEmpty()) {
            ServiceChecks.call(new TodoTaskMapper.TodoTaskFinishInput(), p -> { p.setId(input.getId()); }, todoTaskMapper::todoTaskFinish);
        }
        if (!rows4.isEmpty()) {
            ServiceChecks.call(new TodoMapper.TodoTaskFinishInput(), p -> { p.setTodoId(original.getTodoId()); p.setContextToday(today); }, todoMapper::todoTaskFinish);
        }
        if (!ServiceChecks.zero(ServiceChecks.call(new TodoTaskMapper.CountClosedHasNoOpenTaskInput(), p -> { p.setTodoId(original.getTodoId()); }, todoTaskMapper::countClosedHasNoOpenTask))) {
            violations.add(ServiceChecks.violation("check.closed-has-no-open-task", null, original.getTodoId(), null, null, null));
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
}
