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
    public String todoTaskFinish(java.lang.String id, java.lang.Long version) {
        var input = new TodoTaskFinishInput();
        input.setId(id);
        input.setVersion(version);
        return runner.execute(() -> runtodoTaskFinish(input, false)).id();
    }
    private OperationOutcome runtodoTaskFinish(TodoTaskFinishInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoTaskMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoTaskMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo-task", input.getId());
        var owner = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(original.getTodoId()); }, todoMapper::findById);
        if (owner == null) throw new example.todos.service.error.NotFoundException("todo", original.getTodoId());
        var rows5 = new java.util.ArrayList<TodoTaskRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row5 = new TodoTaskRow();
            row5.setDone(false);
            row5.setDeleted(false);
            var before5 = original;
            row5.setId(before5.getId());
            row5.setTodoId(before5.getTodoId());
            row5.setSeq(before5.getSeq());
            row5.setTitle(before5.getTitle());
            row5.setDone(before5.getDone());
            row5.setDeleted(before5.getDeleted());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setDone(true);
            } else {}
            rows5.add(row5);
        }
        var rows6 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoRow();
            row6.setStatus("OPEN");
            row6.setVersion(0L);
            var before6 = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> p.setId(original.getTodoId()), todoMapper::findById);
            if (before6 == null) throw new example.todos.service.error.NotFoundException("todo", original.getTodoId());
            row6.setId(before6.getId());
            row6.setTitle(before6.getTitle());
            row6.setSummary(before6.getSummary());
            row6.setCategory(before6.getCategory());
            row6.setFollows(before6.getFollows());
            row6.setStatus(before6.getStatus());
            row6.setCreatedOn(before6.getCreatedOn());
            row6.setUpdatedOn(before6.getUpdatedOn());
            row6.setDueOn(before6.getDueOn());
            row6.setCompletedOn(before6.getCompletedOn());
            row6.setDetails(before6.getDetails());
            row6.setResult(before6.getResult());
            row6.setVersion(before6.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setVersion((owner.getVersion() + 1L));
            } else {}
            rows6.add(row6);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(owner.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("todo-task.finish.version-unchanged", null, original.getTodoId(), null, null, null));
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, original.getTodoId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows5.isEmpty()) {
            ServiceChecks.call(new TodoTaskMapper.TodoTaskFinishInput(), p -> { p.setId(input.getId()); }, todoTaskMapper::todoTaskFinish);
        }
        if (!rows6.isEmpty()) {
            ServiceChecks.call(new TodoMapper.TodoTaskFinishInput(), p -> { p.setTodoId(original.getTodoId()); p.setContextToday(today); p.setOwnerNextVersion((owner.getVersion() + 1L)); }, todoMapper::todoTaskFinish);
        }
        if (!ServiceChecks.zero(ServiceChecks.call(new TodoTaskMapper.CountClosedHasNoOpenTaskInput(), p -> { p.setTodoId(original.getTodoId()); }, todoTaskMapper::countClosedHasNoOpenTask))) {
            violations.add(ServiceChecks.violation("check.closed-has-no-open-task", null, original.getTodoId(), null, null, null));
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
}
