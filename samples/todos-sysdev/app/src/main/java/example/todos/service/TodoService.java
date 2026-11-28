package example.todos.service;

import example.todos.data.model.*;
import example.todos.data.mapper.*;
import example.todos.service.input.*;
import example.todos.service.runtime.*;
import example.todos.service.error.BusinessViolation;
import org.springframework.stereotype.Service;

/** 確定した処理設計・入力・Mapper の対応から生成。 */
@Service
public class TodoService {
    private final OperationRunner runner;
    private final java.time.Clock clock;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final TodoTaskMapper todoTaskMapper;
    private final TodoMapper todoMapper;
    public TodoService(OperationRunner runner, java.time.Clock clock, CategoryMapper categoryMapper, TagMapper tagMapper, TodoTaskMapper todoTaskMapper, TodoMapper todoMapper) {
        this.runner = runner;
        this.clock = clock;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.todoTaskMapper = todoTaskMapper;
        this.todoMapper = todoMapper;
    }
    public String addTodoComment(java.lang.String id, java.time.LocalDate date, java.lang.String body, java.lang.Long version) {
        var input = new AddTodoCommentInput();
        input.setId(id);
        input.setDate(date);
        input.setBody(body);
        input.setVersion(version);
        return runner.execute(() -> runaddTodoComment(input, false)).id();
    }
    private OperationOutcome runaddTodoComment(AddTodoCommentInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows4 = new java.util.ArrayList<TodoCommentsRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row4 = new TodoCommentsRow();
            row4.setTodoId(input.getId());
            row4.setDate(input.getDate());
            row4.setBody(input.getBody());
            row4.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoCommentsInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoComments)));
            rows4.add(row4);
        }
        var rows5 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row5 = new TodoRow();
            row5.setStatus("OPEN");
            row5.setVersion(0L);
            var before5 = original;
            row5.setId(before5.getId());
            row5.setTitle(before5.getTitle());
            row5.setSummary(before5.getSummary());
            row5.setCategory(before5.getCategory());
            row5.setFollows(before5.getFollows());
            row5.setStatus(before5.getStatus());
            row5.setCreatedOn(before5.getCreatedOn());
            row5.setUpdatedOn(before5.getUpdatedOn());
            row5.setDueOn(before5.getDueOn());
            row5.setCompletedOn(before5.getCompletedOn());
            row5.setDetails(before5.getDetails());
            row5.setResult(before5.getResult());
            row5.setVersion(before5.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setVersion((original.getVersion() + 1L));
            } else {}
            rows5.add(row5);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("add-todo-comment.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows5) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        for (var writing : rows4) {
            todoMapper.insertTodoComments(writing);
        }
        if (!rows5.isEmpty()) {
            ServiceChecks.call(new TodoMapper.AddTodoCommentInput(), p -> { p.setId(input.getId()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::addTodoComment);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String addTodoTask(java.lang.String id, java.lang.String title, java.lang.Long version) {
        var input = new AddTodoTaskInput();
        input.setId(id);
        input.setTitle(title);
        input.setVersion(version);
        return runner.execute(() -> runaddTodoTask(input, false)).id();
    }
    private OperationOutcome runaddTodoTask(AddTodoTaskInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var numberedId = SequenceNumbers.nextId(ServiceChecks.call(new TodoTaskMapper.MaxIdInput(), p -> { p.setId(input.getId()); }, todoTaskMapper::maxId), "{id}-S".replace("{id}", original.getId()), 2);
        var rows6 = new java.util.ArrayList<TodoTaskRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoTaskRow();
            row6.setDone(false);
            row6.setDeleted(false);
            row6.setId(numberedId);
            row6.setTitle(input.getTitle());
            row6.setDone(ServiceChecks.orDefault(false, false));
            row6.setTodoId(input.getId());
            row6.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoTaskMapper.MaxSeqInput(), p -> p.setId(input.getId()), todoTaskMapper::maxSeq)));
            rows6.add(row6);
        }
        var rows7 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row7 = new TodoRow();
            row7.setStatus("OPEN");
            row7.setVersion(0L);
            var before7 = original;
            row7.setId(before7.getId());
            row7.setTitle(before7.getTitle());
            row7.setSummary(before7.getSummary());
            row7.setCategory(before7.getCategory());
            row7.setFollows(before7.getFollows());
            row7.setStatus(before7.getStatus());
            row7.setCreatedOn(before7.getCreatedOn());
            row7.setUpdatedOn(before7.getUpdatedOn());
            row7.setDueOn(before7.getDueOn());
            row7.setCompletedOn(before7.getCompletedOn());
            row7.setDetails(before7.getDetails());
            row7.setResult(before7.getResult());
            row7.setVersion(before7.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row7.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row7.setVersion((original.getVersion() + 1L));
            } else {}
            rows7.add(row7);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("OPEN", "IN_PROGRESS", "SUSPENDED"))))) {
            violations.add(ServiceChecks.violation("add-todo-task.active", null, input.getId(), null, null, null));
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("add-todo-task.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows7) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(numberedId, violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        for (var writing : rows6) {
            todoTaskMapper.insert(writing);
        }
        if (!rows7.isEmpty()) {
            ServiceChecks.call(new TodoMapper.AddTodoTaskInput(), p -> { p.setId(input.getId()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::addTodoTask);
        }
        if (!ServiceChecks.zero(ServiceChecks.call(new TodoTaskMapper.CountClosedHasNoOpenTaskInput(), p -> { p.setTodoId(input.getId()); }, todoTaskMapper::countClosedHasNoOpenTask))) {
            violations.add(ServiceChecks.violation("check.closed-has-no-open-task", null, input.getId(), null, null, null));
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(numberedId, violations);
    }
    public String cancelTodo(java.lang.String id, java.lang.String reason, java.lang.Long version) {
        var input = new CancelTodoInput();
        input.setId(id);
        input.setReason(reason);
        input.setVersion(version);
        return runner.execute(() -> runcancelTodo(input, false)).id();
    }
    private OperationOutcome runcancelTodo(CancelTodoInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows6 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoRow();
            row6.setStatus("OPEN");
            row6.setVersion(0L);
            var before6 = original;
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
                row6.setResult(input.getReason());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setStatus("CANCELED");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setVersion((original.getVersion() + 1L));
            } else {}
            rows6.add(row6);
        }
        var rows7 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row7 = new TodoHistoryRow();
            row7.setTodoId(input.getId());
            row7.setDate(today);
            row7.setAction("cancel-todo");
            row7.setFrom(original.getStatus());
            row7.setTo("CANCELED");
            row7.setNote(input.getReason());
            row7.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows7.add(row7);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("OPEN", "IN_PROGRESS", "SUSPENDED")))) {
            violations.add(ServiceChecks.violation("cancel-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("cancel-todo.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.not(ServiceChecks.equal(checked.getStatus(), "CLOSED")), Boolean.valueOf(checked.getCompletedOn() != null)))) {
            violations.add(ServiceChecks.violation("check.unfinished-has-no-completed-on", null, input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows6.isEmpty()) {
            ServiceChecks.call(new TodoMapper.CancelTodoInput(), p -> { p.setId(input.getId()); p.setReason(input.getReason()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::cancelTodo);
        }
        for (var writing : rows7) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String completeTodo(java.lang.String id, java.time.LocalDate date, java.lang.String result, java.lang.Long version) {
        var input = new CompleteTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setResult(result);
        input.setVersion(version);
        return runner.execute(() -> runcompleteTodo(input, false)).id();
    }
    public java.util.List<BusinessViolation> checkCompleteTodo(java.lang.String id, java.time.LocalDate date, java.lang.String result, java.lang.Long version) {
        var input = new CompleteTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setResult(result);
        input.setVersion(version);
        return runner.preview(() -> runcompleteTodo(input, true)).violations();
    }
    private OperationOutcome runcompleteTodo(CompleteTodoInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows9 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row9 = new TodoRow();
            row9.setStatus("OPEN");
            row9.setVersion(0L);
            var before9 = original;
            row9.setId(before9.getId());
            row9.setTitle(before9.getTitle());
            row9.setSummary(before9.getSummary());
            row9.setCategory(before9.getCategory());
            row9.setFollows(before9.getFollows());
            row9.setStatus(before9.getStatus());
            row9.setCreatedOn(before9.getCreatedOn());
            row9.setUpdatedOn(before9.getUpdatedOn());
            row9.setDueOn(before9.getDueOn());
            row9.setCompletedOn(before9.getCompletedOn());
            row9.setDetails(before9.getDetails());
            row9.setResult(before9.getResult());
            row9.setVersion(before9.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row9.setCompletedOn(input.getDate());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row9.setResult(input.getResult());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row9.setStatus("CLOSED");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row9.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row9.setVersion((original.getVersion() + 1L));
            } else {}
            rows9.add(row9);
        }
        var rows10 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row10 = new TodoHistoryRow();
            row10.setTodoId(input.getId());
            row10.setDate(input.getDate());
            row10.setAction("complete-todo");
            row10.setFrom(original.getStatus());
            row10.setTo("CLOSED");
            row10.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows10.add(row10);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("IN_PROGRESS")))) {
            violations.add(ServiceChecks.violation("complete-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
        }
        if (!ServiceChecks.zero(ServiceChecks.call(new TodoTaskMapper.CountCompleteTodoNoOpenTaskInput(), p -> { p.setId(input.getId()); }, todoTaskMapper::countCompleteTodoNoOpenTask))) {
            violations.add(ServiceChecks.violation("complete-todo.no-open-task", null, input.getId(), null, null, null));
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("complete-todo.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows9) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows9) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getCompletedOn() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getCompletedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.completed-not-before-created", "date", input.getId(), null, checked.getCompletedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows9) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(checked.getStatus(), "CLOSED"), Boolean.valueOf(checked.getCompletedOn() == null)))) {
            violations.add(ServiceChecks.violation("check.closed-has-completed-on", "date", input.getId(), null, null, null));
            }
        }
        for (var checked : rows9) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(checked.getStatus(), "CLOSED"), ServiceChecks.empty(checked.getResult())))) {
            violations.add(ServiceChecks.violation("check.closed-has-result", "result", input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows9.isEmpty()) {
            ServiceChecks.call(new TodoMapper.CompleteTodoInput(), p -> { p.setId(input.getId()); p.setDate(input.getDate()); p.setResult(input.getResult()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::completeTodo);
        }
        for (var writing : rows10) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!ServiceChecks.zero(ServiceChecks.call(new TodoTaskMapper.CountClosedHasNoOpenTaskInput(), p -> { p.setTodoId(input.getId()); }, todoTaskMapper::countClosedHasNoOpenTask))) {
            violations.add(ServiceChecks.violation("check.closed-has-no-open-task", null, input.getId(), null, null, null));
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String deleteTodoComment(java.lang.String id, java.lang.Long row, java.lang.Long version) {
        var input = new DeleteTodoCommentInput();
        input.setId(id);
        input.setRow(row);
        input.setVersion(version);
        return runner.execute(() -> rundeleteTodoComment(input, false)).id();
    }
    private OperationOutcome rundeleteTodoComment(DeleteTodoCommentInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows5 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row5 = new TodoRow();
            row5.setStatus("OPEN");
            row5.setVersion(0L);
            var before5 = original;
            row5.setId(before5.getId());
            row5.setTitle(before5.getTitle());
            row5.setSummary(before5.getSummary());
            row5.setCategory(before5.getCategory());
            row5.setFollows(before5.getFollows());
            row5.setStatus(before5.getStatus());
            row5.setCreatedOn(before5.getCreatedOn());
            row5.setUpdatedOn(before5.getUpdatedOn());
            row5.setDueOn(before5.getDueOn());
            row5.setCompletedOn(before5.getCompletedOn());
            row5.setDetails(before5.getDetails());
            row5.setResult(before5.getResult());
            row5.setVersion(before5.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setVersion((original.getVersion() + 1L));
            } else {}
            rows5.add(row5);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("delete-todo-comment.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows5) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            if (ServiceChecks.call(new TodoMapper.DeleteTodoCommentCommentsRowInput(), p -> { p.setId(input.getId()); p.setRow(input.getRow()); }, todoMapper::deleteTodoCommentCommentsRow) == 0) throw new example.todos.service.error.NotFoundException("todo.comments", input.getId());
        }
        if (!rows5.isEmpty()) {
            ServiceChecks.call(new TodoMapper.DeleteTodoCommentInput(), p -> { p.setId(input.getId()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::deleteTodoComment);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String deleteTodoTask(java.lang.String id, java.lang.Long row, java.lang.Long version) {
        var input = new DeleteTodoTaskInput();
        input.setId(id);
        input.setRow(row);
        input.setVersion(version);
        return runner.execute(() -> rundeleteTodoTask(input, false)).id();
    }
    private OperationOutcome rundeleteTodoTask(DeleteTodoTaskInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows6 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoRow();
            row6.setStatus("OPEN");
            row6.setVersion(0L);
            var before6 = original;
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
                row6.setVersion((original.getVersion() + 1L));
            } else {}
            rows6.add(row6);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("OPEN", "IN_PROGRESS", "SUSPENDED"))))) {
            violations.add(ServiceChecks.violation("delete-todo-task.active", null, input.getId(), null, null, null));
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("delete-todo-task.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            if (ServiceChecks.call(new TodoTaskMapper.DeleteTodoTaskTasksRowInput(), p -> { p.setId(input.getId()); p.setRow(input.getRow()); }, todoTaskMapper::deleteTodoTaskTasksRow) == 0) throw new example.todos.service.error.NotFoundException("todo.tasks", input.getId());
        }
        if (!rows6.isEmpty()) {
            ServiceChecks.call(new TodoMapper.DeleteTodoTaskInput(), p -> { p.setId(input.getId()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::deleteTodoTask);
        }
        if (!ServiceChecks.zero(ServiceChecks.call(new TodoTaskMapper.CountClosedHasNoOpenTaskInput(), p -> { p.setTodoId(input.getId()); }, todoTaskMapper::countClosedHasNoOpenTask))) {
            violations.add(ServiceChecks.violation("check.closed-has-no-open-task", null, input.getId(), null, null, null));
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String editTodo(java.lang.String id, java.lang.String title, java.lang.String summary, java.lang.String category, java.util.List<java.lang.String> tags, java.time.LocalDate createdOn, java.time.LocalDate dueOn, java.lang.String details, java.lang.String result, EditTodoInput.EditTodoClear clear, java.lang.Long version) {
        var input = new EditTodoInput();
        input.setId(id);
        input.setTitle(title);
        input.setSummary(summary);
        input.setCategory(category);
        input.setTags(tags);
        input.setCreatedOn(createdOn);
        input.setDueOn(dueOn);
        input.setDetails(details);
        input.setResult(result);
        input.setClear(clear);
        input.setVersion(version);
        return runner.execute(() -> runeditTodo(input, false)).id();
    }
    public java.util.List<BusinessViolation> checkEditTodo(java.lang.String id, java.lang.String title, java.lang.String summary, java.lang.String category, java.util.List<java.lang.String> tags, java.time.LocalDate createdOn, java.time.LocalDate dueOn, java.lang.String details, java.lang.String result, EditTodoInput.EditTodoClear clear, java.lang.Long version) {
        var input = new EditTodoInput();
        input.setId(id);
        input.setTitle(title);
        input.setSummary(summary);
        input.setCategory(category);
        input.setTags(tags);
        input.setCreatedOn(createdOn);
        input.setDueOn(dueOn);
        input.setDetails(details);
        input.setResult(result);
        input.setClear(clear);
        input.setVersion(version);
        return runner.preview(() -> runeditTodo(input, true)).violations();
    }
    private OperationOutcome runeditTodo(EditTodoInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows7 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row7 = new TodoRow();
            row7.setStatus("OPEN");
            row7.setVersion(0L);
            var before7 = original;
            row7.setId(before7.getId());
            row7.setTitle(before7.getTitle());
            row7.setSummary(before7.getSummary());
            row7.setCategory(before7.getCategory());
            row7.setFollows(before7.getFollows());
            row7.setStatus(before7.getStatus());
            row7.setCreatedOn(before7.getCreatedOn());
            row7.setUpdatedOn(before7.getUpdatedOn());
            row7.setDueOn(before7.getDueOn());
            row7.setCompletedOn(before7.getCompletedOn());
            row7.setDetails(before7.getDetails());
            row7.setResult(before7.getResult());
            row7.setVersion(before7.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getTitle() != null)))) {
                row7.setTitle(input.getTitle());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getSummary() != null)))) {
                row7.setSummary(input.getSummary());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getCategory()), true)))) {
                row7.setCategory(null);
            } else             if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getCategory() != null)))) {
                row7.setCategory(input.getCategory());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getCreatedOn() != null)))) {
                row7.setCreatedOn(input.getCreatedOn());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getDueOn()), true)))) {
                row7.setDueOn(null);
            } else             if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getDueOn() != null)))) {
                row7.setDueOn(input.getDueOn());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getDetails()), true)))) {
                row7.setDetails(null);
            } else             if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getDetails() != null)))) {
                row7.setDetails(input.getDetails());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getResult()), true)))) {
                row7.setResult(null);
            } else             if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getResult() != null)))) {
                row7.setResult(input.getResult());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row7.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row7.setVersion((original.getVersion() + 1L));
            } else {}
            rows7.add(row7);
        }
        var rows9 = new java.util.ArrayList<TodoTagsRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getTags() != null)))) {
            if (input.getTags() != null) {
                long position = 1L;
                boolean firstItem = true;
                for (var item : input.getTags()) {
                    if (!firstItem) position = SequenceNumbers.nextPosition(position);
                    firstItem = false;
                    java.util.Objects.requireNonNull(item, "配列の要素が null です");
            var row9 = new TodoTagsRow();
            row9.setTodoId(input.getId());
            row9.setSeq(position);
            row9.setTagId(item);
                    rows9.add(row9);
                }
            }
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("edit-todo.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows7) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getDueOn() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getDueOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.due-not-before-created", "dueOn", input.getId(), null, checked.getDueOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows7) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", "createdOn", input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows7) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getCompletedOn() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getCompletedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.completed-not-before-created", "createdOn", input.getId(), null, checked.getCompletedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows7) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(checked.getStatus(), "CLOSED"), ServiceChecks.empty(checked.getResult())))) {
            violations.add(ServiceChecks.violation("check.closed-has-result", "result", input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows7.isEmpty()) {
            ServiceChecks.call(new TodoMapper.EditTodoInput(), p -> { p.setId(input.getId()); p.setTitle(input.getTitle()); p.setSummary(input.getSummary()); p.setCategory(input.getCategory()); p.setCreatedOn(input.getCreatedOn()); p.setDueOn(input.getDueOn()); p.setDetails(input.getDetails()); p.setResult(input.getResult()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); p.setClearCategory((input.getClear() == null ? null : input.getClear().getCategory())); p.setClearDueOn((input.getClear() == null ? null : input.getClear().getDueOn())); p.setClearDetails((input.getClear() == null ? null : input.getClear().getDetails())); p.setClearResult((input.getClear() == null ? null : input.getClear().getResult())); }, todoMapper::editTodo);
        }
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getTags() != null)))) {
            ServiceChecks.call(new TodoMapper.DeleteTodoTagsInput(), p -> { p.setId(input.getId()); }, todoMapper::deleteTodoTags);
        }
        for (var writing : rows9) {
            todoMapper.insertTodoTags(writing);
        }
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getTags()), true)))) {
            ServiceChecks.call(new TodoMapper.DeleteTodoTagsInput(), p -> { p.setId(input.getId()); }, todoMapper::deleteTodoTags);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String editTodoComment(java.lang.String id, java.lang.Long row, java.time.LocalDate date, java.lang.String body, java.lang.Long version) {
        var input = new EditTodoCommentInput();
        input.setId(id);
        input.setRow(row);
        input.setDate(date);
        input.setBody(body);
        input.setVersion(version);
        return runner.execute(() -> runeditTodoComment(input, false)).id();
    }
    private OperationOutcome runeditTodoComment(EditTodoCommentInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows5 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row5 = new TodoRow();
            row5.setStatus("OPEN");
            row5.setVersion(0L);
            var before5 = original;
            row5.setId(before5.getId());
            row5.setTitle(before5.getTitle());
            row5.setSummary(before5.getSummary());
            row5.setCategory(before5.getCategory());
            row5.setFollows(before5.getFollows());
            row5.setStatus(before5.getStatus());
            row5.setCreatedOn(before5.getCreatedOn());
            row5.setUpdatedOn(before5.getUpdatedOn());
            row5.setDueOn(before5.getDueOn());
            row5.setCompletedOn(before5.getCompletedOn());
            row5.setDetails(before5.getDetails());
            row5.setResult(before5.getResult());
            row5.setVersion(before5.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setVersion((original.getVersion() + 1L));
            } else {}
            rows5.add(row5);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("edit-todo-comment.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows5) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            if (ServiceChecks.call(new TodoMapper.EditTodoCommentCommentsRowInput(), p -> { p.setId(input.getId()); p.setRow(input.getRow()); p.setDate(input.getDate()); p.setBody(input.getBody()); }, todoMapper::editTodoCommentCommentsRow) == 0) throw new example.todos.service.error.NotFoundException("todo.comments", input.getId());
        }
        if (!rows5.isEmpty()) {
            ServiceChecks.call(new TodoMapper.EditTodoCommentInput(), p -> { p.setId(input.getId()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::editTodoComment);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String registerTodo(java.lang.String title, java.lang.String summary, java.lang.String category, java.util.List<java.lang.String> tags, java.lang.String follows, java.time.LocalDate due, java.lang.String details) {
        var input = new RegisterTodoInput();
        input.setTitle(title);
        input.setSummary(summary);
        input.setCategory(category);
        input.setTags(tags);
        input.setFollows(follows);
        input.setDue(due);
        input.setDetails(details);
        return runner.execute(() -> runregisterTodo(input, false)).id();
    }
    public java.util.List<BusinessViolation> checkRegisterTodo(java.lang.String title, java.lang.String summary, java.lang.String category, java.util.List<java.lang.String> tags, java.lang.String follows, java.time.LocalDate due, java.lang.String details) {
        var input = new RegisterTodoInput();
        input.setTitle(title);
        input.setSummary(summary);
        input.setCategory(category);
        input.setTags(tags);
        input.setFollows(follows);
        input.setDue(due);
        input.setDetails(details);
        return runner.preview(() -> runregisterTodo(input, true)).violations();
    }
    private OperationOutcome runregisterTodo(RegisterTodoInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var numberedId = SequenceNumbers.nextId(todoMapper.maxId(), "TODO-", 3);
        var rows6 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoRow();
            row6.setStatus("OPEN");
            row6.setVersion(0L);
            row6.setId(numberedId);
            row6.setDueOn(input.getDue());
            row6.setDetails(input.getDetails());
            row6.setTitle(input.getTitle());
            row6.setSummary(input.getSummary());
            row6.setCategory(input.getCategory());
            row6.setFollows(input.getFollows());
            row6.setStatus(ServiceChecks.orDefault("OPEN", "OPEN"));
            row6.setCreatedOn(today);
            row6.setUpdatedOn(today);
            rows6.add(row6);
        }
        var rows7 = new java.util.ArrayList<TodoTagsRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            if (input.getTags() != null) {
                long position = 1L;
                boolean firstItem = true;
                for (var item : input.getTags()) {
                    if (!firstItem) position = SequenceNumbers.nextPosition(position);
                    firstItem = false;
                    java.util.Objects.requireNonNull(item, "配列の要素が null です");
            var row7 = new TodoTagsRow();
            row7.setTodoId(numberedId);
            row7.setSeq(position);
            row7.setTagId(item);
                    rows7.add(row7);
                }
            }
        }
        var rows8 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row8 = new TodoHistoryRow();
            row8.setTodoId(numberedId);
            row8.setDate(today);
            row8.setAction("register-todo");
            row8.setTo("OPEN");
            row8.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(numberedId), todoMapper::maxSeqTodoHistory)));
            rows8.add(row8);
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getDueOn() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getDueOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.due-not-before-created", "due", numberedId, null, checked.getDueOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, numberedId, null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getFollows() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.not(ServiceChecks.equal(checked.getFollows(), checked.getId())))) {
            violations.add(ServiceChecks.violation("check.follows-not-self", "follows", numberedId, null, checked.getFollows(), checked.getId()));
                }
            }
        }
        for (var checked : rows6) {
            {
                var cursor = checked.getFollows();
                var visited = new java.util.HashSet<String>();
                while (cursor != null) {
                    if (java.util.Objects.equals(cursor, checked.getId()) || !visited.add(cursor)) {
            violations.add(ServiceChecks.violation("check.follow-up-of.acyclic", "follows", numberedId, null, null, null));
                        break;
                    }
                    var lookup = cursor;
                    var linked = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> p.setId(lookup), todoMapper::findById);
                    if (linked == null) break;
                    cursor = linked.getFollows();
                }
            }
        }
        if (preview) return new OperationOutcome(numberedId, violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        for (var writing : rows6) {
            todoMapper.insert(writing);
        }
        for (var writing : rows7) {
            todoMapper.insertTodoTags(writing);
        }
        for (var writing : rows8) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(numberedId, violations);
    }
    public String resumeTodo(java.lang.String id, java.time.LocalDate date, java.lang.String note, java.lang.Long version) {
        var input = new ResumeTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setNote(note);
        input.setVersion(version);
        return runner.execute(() -> runresumeTodo(input, false)).id();
    }
    private OperationOutcome runresumeTodo(ResumeTodoInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows6 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoRow();
            row6.setStatus("OPEN");
            row6.setVersion(0L);
            var before6 = original;
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
                row6.setStatus("IN_PROGRESS");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setVersion((original.getVersion() + 1L));
            } else {}
            rows6.add(row6);
        }
        var rows7 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row7 = new TodoHistoryRow();
            row7.setTodoId(input.getId());
            row7.setDate(input.getDate());
            row7.setAction("resume-todo");
            row7.setFrom(original.getStatus());
            row7.setTo("IN_PROGRESS");
            row7.setNote(input.getNote());
            row7.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows7.add(row7);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("SUSPENDED")))) {
            violations.add(ServiceChecks.violation("resume-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("resume-todo.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.not(ServiceChecks.equal(checked.getStatus(), "CLOSED")), Boolean.valueOf(checked.getCompletedOn() != null)))) {
            violations.add(ServiceChecks.violation("check.unfinished-has-no-completed-on", null, input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows6.isEmpty()) {
            ServiceChecks.call(new TodoMapper.ResumeTodoInput(), p -> { p.setId(input.getId()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::resumeTodo);
        }
        for (var writing : rows7) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String startTodo(java.lang.String id, java.lang.Long version) {
        var input = new StartTodoInput();
        input.setId(id);
        input.setVersion(version);
        return runner.execute(() -> runstartTodo(input, false)).id();
    }
    private OperationOutcome runstartTodo(StartTodoInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows6 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoRow();
            row6.setStatus("OPEN");
            row6.setVersion(0L);
            var before6 = original;
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
                row6.setStatus("IN_PROGRESS");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setVersion((original.getVersion() + 1L));
            } else {}
            rows6.add(row6);
        }
        var rows7 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row7 = new TodoHistoryRow();
            row7.setTodoId(input.getId());
            row7.setDate(today);
            row7.setAction("start-todo");
            row7.setFrom(original.getStatus());
            row7.setTo("IN_PROGRESS");
            row7.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows7.add(row7);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("OPEN")))) {
            violations.add(ServiceChecks.violation("start-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("start-todo.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.not(ServiceChecks.equal(checked.getStatus(), "CLOSED")), Boolean.valueOf(checked.getCompletedOn() != null)))) {
            violations.add(ServiceChecks.violation("check.unfinished-has-no-completed-on", null, input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows6.isEmpty()) {
            ServiceChecks.call(new TodoMapper.StartTodoInput(), p -> { p.setId(input.getId()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::startTodo);
        }
        for (var writing : rows7) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String suspendTodo(java.lang.String id, java.time.LocalDate date, java.lang.String reason, java.time.LocalDate resumeOn, java.lang.Long version) {
        var input = new SuspendTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setReason(reason);
        input.setResumeOn(resumeOn);
        input.setVersion(version);
        return runner.execute(() -> runsuspendTodo(input, false)).id();
    }
    public java.util.List<BusinessViolation> checkSuspendTodo(java.lang.String id, java.time.LocalDate date, java.lang.String reason, java.time.LocalDate resumeOn, java.lang.Long version) {
        var input = new SuspendTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setReason(reason);
        input.setResumeOn(resumeOn);
        input.setVersion(version);
        return runner.preview(() -> runsuspendTodo(input, true)).violations();
    }
    private OperationOutcome runsuspendTodo(SuspendTodoInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows7 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row7 = new TodoRow();
            row7.setStatus("OPEN");
            row7.setVersion(0L);
            var before7 = original;
            row7.setId(before7.getId());
            row7.setTitle(before7.getTitle());
            row7.setSummary(before7.getSummary());
            row7.setCategory(before7.getCategory());
            row7.setFollows(before7.getFollows());
            row7.setStatus(before7.getStatus());
            row7.setCreatedOn(before7.getCreatedOn());
            row7.setUpdatedOn(before7.getUpdatedOn());
            row7.setDueOn(before7.getDueOn());
            row7.setCompletedOn(before7.getCompletedOn());
            row7.setDetails(before7.getDetails());
            row7.setResult(before7.getResult());
            row7.setVersion(before7.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row7.setStatus("SUSPENDED");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row7.setUpdatedOn(today);
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row7.setVersion((original.getVersion() + 1L));
            } else {}
            rows7.add(row7);
        }
        var rows8 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row8 = new TodoHistoryRow();
            row8.setTodoId(input.getId());
            row8.setDate(input.getDate());
            row8.setAction("suspend-todo");
            row8.setFrom(original.getStatus());
            row8.setTo("SUSPENDED");
            row8.setNote(input.getReason());
            row8.setResumeOn(input.getResumeOn());
            row8.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows8.add(row8);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("IN_PROGRESS")))) {
            violations.add(ServiceChecks.violation("suspend-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(original.getVersion(), input.getVersion())))) {
            violations.add(ServiceChecks.violation("suspend-todo.version-unchanged", null, input.getId(), null, null, null));
        }
        for (var checked : rows7) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows7) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.not(ServiceChecks.equal(checked.getStatus(), "CLOSED")), Boolean.valueOf(checked.getCompletedOn() != null)))) {
            violations.add(ServiceChecks.violation("check.unfinished-has-no-completed-on", null, input.getId(), null, null, null));
            }
        }
        for (var checked : rows8) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getResumeOn() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getResumeOn(), checked.getDate()))) {
            violations.add(ServiceChecks.violation("check.resume-not-before-suspend", "resumeOn", input.getId(), null, checked.getResumeOn(), checked.getDate()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows7.isEmpty()) {
            ServiceChecks.call(new TodoMapper.SuspendTodoInput(), p -> { p.setId(input.getId()); p.setContextToday(today); p.setNextVersion((original.getVersion() + 1L)); }, todoMapper::suspendTodo);
        }
        for (var writing : rows8) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }

    public TodoMapper.FindDetailResult findDetail(java.lang.String id) {
        var input = new TodoMapper.FindDetailInput();
        input.setId(id);
        return todoMapper.findDetail(input);
    }
}
