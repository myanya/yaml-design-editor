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
    public String addTodoComment(java.lang.String id, java.time.LocalDate date, java.lang.String body) {
        var input = new AddTodoCommentInput();
        input.setId(id);
        input.setDate(date);
        input.setBody(body);
        return runner.execute(() -> runaddTodoComment(input, false)).id();
    }
    private OperationOutcome runaddTodoComment(AddTodoCommentInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows3 = new java.util.ArrayList<TodoCommentsRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row3 = new TodoCommentsRow();
            row3.setTodoId(input.getId());
            row3.setDate(input.getDate());
            row3.setBody(input.getBody());
            row3.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoCommentsInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoComments)));
            rows3.add(row3);
        }
        var rows4 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row4 = new TodoRow();
            row4.setStatus("OPEN");
            row4.setVersion(0L);
            var before4 = original;
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
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        for (var writing : rows3) {
            todoMapper.insertTodoComments(writing);
        }
        if (!rows4.isEmpty()) {
            ServiceChecks.call(new TodoMapper.AddTodoCommentInput(), p -> { p.setId(input.getId()); p.setContextToday(today); }, todoMapper::addTodoComment);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String addTodoTask(java.lang.String id, java.lang.String title) {
        var input = new AddTodoTaskInput();
        input.setId(id);
        input.setTitle(title);
        return runner.execute(() -> runaddTodoTask(input, false)).id();
    }
    private OperationOutcome runaddTodoTask(AddTodoTaskInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var numberedId = SequenceNumbers.nextId(ServiceChecks.call(new TodoTaskMapper.MaxIdInput(), p -> { p.setId(input.getId()); }, todoTaskMapper::maxId), "{id}-S".replace("{id}", original.getId()), 2);
        var rows5 = new java.util.ArrayList<TodoTaskRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row5 = new TodoTaskRow();
            row5.setDone(false);
            row5.setId(numberedId);
            row5.setTitle(input.getTitle());
            row5.setDone(ServiceChecks.orDefault(false, false));
            row5.setTodoId(input.getId());
            row5.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoTaskMapper.MaxSeqInput(), p -> p.setId(input.getId()), todoTaskMapper::maxSeq)));
            rows5.add(row5);
        }
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
            rows6.add(row6);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("OPEN", "IN_PROGRESS", "SUSPENDED"))))) {
            violations.add(ServiceChecks.violation("add-todo-task.active", null, input.getId(), null, null, null));
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(numberedId, violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        for (var writing : rows5) {
            todoTaskMapper.insert(writing);
        }
        if (!rows6.isEmpty()) {
            ServiceChecks.call(new TodoMapper.AddTodoTaskInput(), p -> { p.setId(input.getId()); p.setContextToday(today); }, todoMapper::addTodoTask);
        }
        if (!ServiceChecks.zero(ServiceChecks.call(new TodoTaskMapper.CountClosedHasNoOpenTaskInput(), p -> { p.setTodoId(input.getId()); }, todoTaskMapper::countClosedHasNoOpenTask))) {
            violations.add(ServiceChecks.violation("check.closed-has-no-open-task", null, input.getId(), null, null, null));
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(numberedId, violations);
    }
    public String cancelTodo(java.lang.String id, java.lang.String reason) {
        var input = new CancelTodoInput();
        input.setId(id);
        input.setReason(reason);
        return runner.execute(() -> runcancelTodo(input, false)).id();
    }
    private OperationOutcome runcancelTodo(CancelTodoInput input, boolean preview) {
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
                row5.setResult(input.getReason());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setStatus("CANCELED");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setUpdatedOn(today);
            } else {}
            rows5.add(row5);
        }
        var rows6 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoHistoryRow();
            row6.setTodoId(input.getId());
            row6.setDate(today);
            row6.setAction("cancel-todo");
            row6.setFrom(original.getStatus());
            row6.setTo("CANCELED");
            row6.setNote(input.getReason());
            row6.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows6.add(row6);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("OPEN", "IN_PROGRESS", "SUSPENDED")))) {
            violations.add(ServiceChecks.violation("cancel-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
        }
        for (var checked : rows5) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows5) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.not(ServiceChecks.equal(checked.getStatus(), "CLOSED")), Boolean.valueOf(checked.getCompletedOn() != null)))) {
            violations.add(ServiceChecks.violation("check.unfinished-has-no-completed-on", null, input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows5.isEmpty()) {
            ServiceChecks.call(new TodoMapper.CancelTodoInput(), p -> { p.setId(input.getId()); p.setReason(input.getReason()); p.setContextToday(today); }, todoMapper::cancelTodo);
        }
        for (var writing : rows6) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String completeTodo(java.lang.String id, java.time.LocalDate date, java.lang.String result) {
        var input = new CompleteTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setResult(result);
        return runner.execute(() -> runcompleteTodo(input, false)).id();
    }
    public java.util.List<BusinessViolation> checkCompleteTodo(java.lang.String id, java.time.LocalDate date, java.lang.String result) {
        var input = new CompleteTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setResult(result);
        return runner.preview(() -> runcompleteTodo(input, true)).violations();
    }
    private OperationOutcome runcompleteTodo(CompleteTodoInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows8 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row8 = new TodoRow();
            row8.setStatus("OPEN");
            row8.setVersion(0L);
            var before8 = original;
            row8.setId(before8.getId());
            row8.setTitle(before8.getTitle());
            row8.setSummary(before8.getSummary());
            row8.setCategory(before8.getCategory());
            row8.setFollows(before8.getFollows());
            row8.setStatus(before8.getStatus());
            row8.setCreatedOn(before8.getCreatedOn());
            row8.setUpdatedOn(before8.getUpdatedOn());
            row8.setDueOn(before8.getDueOn());
            row8.setCompletedOn(before8.getCompletedOn());
            row8.setDetails(before8.getDetails());
            row8.setResult(before8.getResult());
            row8.setVersion(before8.getVersion());
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row8.setCompletedOn(input.getDate());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row8.setResult(input.getResult());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row8.setStatus("CLOSED");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row8.setUpdatedOn(today);
            } else {}
            rows8.add(row8);
        }
        var rows9 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row9 = new TodoHistoryRow();
            row9.setTodoId(input.getId());
            row9.setDate(input.getDate());
            row9.setAction("complete-todo");
            row9.setFrom(original.getStatus());
            row9.setTo("CLOSED");
            row9.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows9.add(row9);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("IN_PROGRESS")))) {
            violations.add(ServiceChecks.violation("complete-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
        }
        if (!ServiceChecks.zero(ServiceChecks.call(new TodoTaskMapper.CountCompleteTodoNoOpenTaskInput(), p -> { p.setId(input.getId()); }, todoTaskMapper::countCompleteTodoNoOpenTask))) {
            violations.add(ServiceChecks.violation("complete-todo.no-open-task", null, input.getId(), null, null, null));
        }
        for (var checked : rows8) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows8) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getCompletedOn() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getCompletedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.completed-not-before-created", "date", input.getId(), null, checked.getCompletedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows8) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(checked.getStatus(), "CLOSED"), Boolean.valueOf(checked.getCompletedOn() == null)))) {
            violations.add(ServiceChecks.violation("check.closed-has-completed-on", "date", input.getId(), null, null, null));
            }
        }
        for (var checked : rows8) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(checked.getStatus(), "CLOSED"), ServiceChecks.empty(checked.getResult())))) {
            violations.add(ServiceChecks.violation("check.closed-has-result", "result", input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows8.isEmpty()) {
            ServiceChecks.call(new TodoMapper.CompleteTodoInput(), p -> { p.setId(input.getId()); p.setDate(input.getDate()); p.setResult(input.getResult()); p.setContextToday(today); }, todoMapper::completeTodo);
        }
        for (var writing : rows9) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!ServiceChecks.zero(ServiceChecks.call(new TodoTaskMapper.CountClosedHasNoOpenTaskInput(), p -> { p.setTodoId(input.getId()); }, todoTaskMapper::countClosedHasNoOpenTask))) {
            violations.add(ServiceChecks.violation("check.closed-has-no-open-task", null, input.getId(), null, null, null));
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String deleteTodoComment(java.lang.String id, java.lang.Long row) {
        var input = new DeleteTodoCommentInput();
        input.setId(id);
        input.setRow(row);
        return runner.execute(() -> rundeleteTodoComment(input, false)).id();
    }
    private OperationOutcome rundeleteTodoComment(DeleteTodoCommentInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows4 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row4 = new TodoRow();
            row4.setStatus("OPEN");
            row4.setVersion(0L);
            var before4 = original;
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
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            if (ServiceChecks.call(new TodoMapper.DeleteTodoCommentCommentsRowInput(), p -> { p.setId(input.getId()); p.setRow(input.getRow()); }, todoMapper::deleteTodoCommentCommentsRow) == 0) throw new example.todos.service.error.NotFoundException("todo.comments", input.getId());
        }
        if (!rows4.isEmpty()) {
            ServiceChecks.call(new TodoMapper.DeleteTodoCommentInput(), p -> { p.setId(input.getId()); p.setContextToday(today); }, todoMapper::deleteTodoComment);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String editTodo(java.lang.String id, java.lang.String title, java.lang.String summary, java.lang.String category, java.util.List<java.lang.String> tags, java.time.LocalDate createdOn, java.time.LocalDate dueOn, java.lang.String details, java.lang.String result, EditTodoInput.EditTodoClear clear) {
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
        return runner.execute(() -> runeditTodo(input, false)).id();
    }
    public java.util.List<BusinessViolation> checkEditTodo(java.lang.String id, java.lang.String title, java.lang.String summary, java.lang.String category, java.util.List<java.lang.String> tags, java.time.LocalDate createdOn, java.time.LocalDate dueOn, java.lang.String details, java.lang.String result, EditTodoInput.EditTodoClear clear) {
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
        return runner.preview(() -> runeditTodo(input, true)).violations();
    }
    private OperationOutcome runeditTodo(EditTodoInput input, boolean preview) {
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
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getTitle() != null)))) {
                row6.setTitle(input.getTitle());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getSummary() != null)))) {
                row6.setSummary(input.getSummary());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getCategory()), true)))) {
                row6.setCategory(null);
            } else             if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getCategory() != null)))) {
                row6.setCategory(input.getCategory());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getCreatedOn() != null)))) {
                row6.setCreatedOn(input.getCreatedOn());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getDueOn()), true)))) {
                row6.setDueOn(null);
            } else             if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getDueOn() != null)))) {
                row6.setDueOn(input.getDueOn());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getDetails()), true)))) {
                row6.setDetails(null);
            } else             if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getDetails() != null)))) {
                row6.setDetails(input.getDetails());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getResult()), true)))) {
                row6.setResult(null);
            } else             if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getResult() != null)))) {
                row6.setResult(input.getResult());
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setUpdatedOn(today);
            } else {}
            rows6.add(row6);
        }
        var rows8 = new java.util.ArrayList<TodoTagsRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getTags() != null)))) {
            if (input.getTags() != null) {
                long position = 1L;
                boolean firstItem = true;
                for (var item : input.getTags()) {
                    if (!firstItem) position = SequenceNumbers.nextPosition(position);
                    firstItem = false;
                    java.util.Objects.requireNonNull(item, "配列の要素が null です");
            var row8 = new TodoTagsRow();
            row8.setTodoId(input.getId());
            row8.setSeq(position);
            row8.setTagId(item);
                    rows8.add(row8);
                }
            }
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getDueOn() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getDueOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.due-not-before-created", "dueOn", input.getId(), null, checked.getDueOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", "createdOn", input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getCompletedOn() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getCompletedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.completed-not-before-created", "createdOn", input.getId(), null, checked.getCompletedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows6) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal(checked.getStatus(), "CLOSED"), ServiceChecks.empty(checked.getResult())))) {
            violations.add(ServiceChecks.violation("check.closed-has-result", "result", input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows6.isEmpty()) {
            ServiceChecks.call(new TodoMapper.EditTodoInput(), p -> { p.setId(input.getId()); p.setTitle(input.getTitle()); p.setSummary(input.getSummary()); p.setCategory(input.getCategory()); p.setCreatedOn(input.getCreatedOn()); p.setDueOn(input.getDueOn()); p.setDetails(input.getDetails()); p.setResult(input.getResult()); p.setContextToday(today); p.setClearCategory((input.getClear() == null ? null : input.getClear().getCategory())); p.setClearDueOn((input.getClear() == null ? null : input.getClear().getDueOn())); p.setClearDetails((input.getClear() == null ? null : input.getClear().getDetails())); p.setClearResult((input.getClear() == null ? null : input.getClear().getResult())); }, todoMapper::editTodo);
        }
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(input.getTags() != null)))) {
            ServiceChecks.call(new TodoMapper.DeleteTodoTagsInput(), p -> { p.setId(input.getId()); }, todoMapper::deleteTodoTags);
        }
        for (var writing : rows8) {
            todoMapper.insertTodoTags(writing);
        }
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.equal((input.getClear() == null ? null : input.getClear().getTags()), true)))) {
            ServiceChecks.call(new TodoMapper.DeleteTodoTagsInput(), p -> { p.setId(input.getId()); }, todoMapper::deleteTodoTags);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String editTodoComment(java.lang.String id, java.lang.Long row, java.time.LocalDate date, java.lang.String body) {
        var input = new EditTodoCommentInput();
        input.setId(id);
        input.setRow(row);
        input.setDate(date);
        input.setBody(body);
        return runner.execute(() -> runeditTodoComment(input, false)).id();
    }
    private OperationOutcome runeditTodoComment(EditTodoCommentInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var original = ServiceChecks.call(new TodoMapper.FindByIdInput(), p -> { p.setId(input.getId()); }, todoMapper::findById);
        if (original == null) throw new example.todos.service.error.NotFoundException("todo", input.getId());
        var rows4 = new java.util.ArrayList<TodoRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row4 = new TodoRow();
            row4.setStatus("OPEN");
            row4.setVersion(0L);
            var before4 = original;
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
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            if (ServiceChecks.call(new TodoMapper.EditTodoCommentCommentsRowInput(), p -> { p.setId(input.getId()); p.setRow(input.getRow()); p.setDate(input.getDate()); p.setBody(input.getBody()); }, todoMapper::editTodoCommentCommentsRow) == 0) throw new example.todos.service.error.NotFoundException("todo.comments", input.getId());
        }
        if (!rows4.isEmpty()) {
            ServiceChecks.call(new TodoMapper.EditTodoCommentInput(), p -> { p.setId(input.getId()); p.setContextToday(today); }, todoMapper::editTodoComment);
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
    public String resumeTodo(java.lang.String id, java.time.LocalDate date, java.lang.String note) {
        var input = new ResumeTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setNote(note);
        return runner.execute(() -> runresumeTodo(input, false)).id();
    }
    private OperationOutcome runresumeTodo(ResumeTodoInput input, boolean preview) {
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
                row5.setStatus("IN_PROGRESS");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setUpdatedOn(today);
            } else {}
            rows5.add(row5);
        }
        var rows6 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoHistoryRow();
            row6.setTodoId(input.getId());
            row6.setDate(input.getDate());
            row6.setAction("resume-todo");
            row6.setFrom(original.getStatus());
            row6.setTo("IN_PROGRESS");
            row6.setNote(input.getNote());
            row6.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows6.add(row6);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("SUSPENDED")))) {
            violations.add(ServiceChecks.violation("resume-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
        }
        for (var checked : rows5) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows5) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.not(ServiceChecks.equal(checked.getStatus(), "CLOSED")), Boolean.valueOf(checked.getCompletedOn() != null)))) {
            violations.add(ServiceChecks.violation("check.unfinished-has-no-completed-on", null, input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows5.isEmpty()) {
            ServiceChecks.call(new TodoMapper.ResumeTodoInput(), p -> { p.setId(input.getId()); p.setContextToday(today); }, todoMapper::resumeTodo);
        }
        for (var writing : rows6) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String startTodo(java.lang.String id) {
        var input = new StartTodoInput();
        input.setId(id);
        return runner.execute(() -> runstartTodo(input, false)).id();
    }
    private OperationOutcome runstartTodo(StartTodoInput input, boolean preview) {
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
                row5.setStatus("IN_PROGRESS");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row5.setUpdatedOn(today);
            } else {}
            rows5.add(row5);
        }
        var rows6 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row6 = new TodoHistoryRow();
            row6.setTodoId(input.getId());
            row6.setDate(today);
            row6.setAction("start-todo");
            row6.setFrom(original.getStatus());
            row6.setTo("IN_PROGRESS");
            row6.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows6.add(row6);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("OPEN")))) {
            violations.add(ServiceChecks.violation("start-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
        }
        for (var checked : rows5) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getUpdatedOn(), checked.getCreatedOn()))) {
            violations.add(ServiceChecks.violation("check.updated-not-before-created", null, input.getId(), null, checked.getUpdatedOn(), checked.getCreatedOn()));
                }
            }
        }
        for (var checked : rows5) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, ServiceChecks.not(ServiceChecks.equal(checked.getStatus(), "CLOSED")), Boolean.valueOf(checked.getCompletedOn() != null)))) {
            violations.add(ServiceChecks.violation("check.unfinished-has-no-completed-on", null, input.getId(), null, null, null));
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows5.isEmpty()) {
            ServiceChecks.call(new TodoMapper.StartTodoInput(), p -> { p.setId(input.getId()); p.setContextToday(today); }, todoMapper::startTodo);
        }
        for (var writing : rows6) {
            todoMapper.insertTodoHistory(writing);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(input.getId(), violations);
    }
    public String suspendTodo(java.lang.String id, java.time.LocalDate date, java.lang.String reason, java.time.LocalDate resumeOn) {
        var input = new SuspendTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setReason(reason);
        input.setResumeOn(resumeOn);
        return runner.execute(() -> runsuspendTodo(input, false)).id();
    }
    public java.util.List<BusinessViolation> checkSuspendTodo(java.lang.String id, java.time.LocalDate date, java.lang.String reason, java.time.LocalDate resumeOn) {
        var input = new SuspendTodoInput();
        input.setId(id);
        input.setDate(date);
        input.setReason(reason);
        input.setResumeOn(resumeOn);
        return runner.preview(() -> runsuspendTodo(input, true)).violations();
    }
    private OperationOutcome runsuspendTodo(SuspendTodoInput input, boolean preview) {
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
                row6.setStatus("SUSPENDED");
            } else {}
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
                row6.setUpdatedOn(today);
            } else {}
            rows6.add(row6);
        }
        var rows7 = new java.util.ArrayList<TodoHistoryRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row7 = new TodoHistoryRow();
            row7.setTodoId(input.getId());
            row7.setDate(input.getDate());
            row7.setAction("suspend-todo");
            row7.setFrom(original.getStatus());
            row7.setTo("SUSPENDED");
            row7.setNote(input.getReason());
            row7.setResumeOn(input.getResumeOn());
            row7.setSeq(SequenceNumbers.nextPosition(ServiceChecks.call(new TodoMapper.MaxSeqTodoHistoryInput(), p -> p.setId(input.getId()), todoMapper::maxSeqTodoHistory)));
            rows7.add(row7);
        }
        if (!Boolean.TRUE.equals(ServiceChecks.oneOf(original.getStatus(), java.util.Arrays.asList("IN_PROGRESS")))) {
            violations.add(ServiceChecks.violation("suspend-todo.state", null, input.getId(), ServiceChecks.label(original.getStatus(), java.util.Map.ofEntries(java.util.Map.entry("OPEN", "未着手"), java.util.Map.entry("IN_PROGRESS", "対応中"), java.util.Map.entry("SUSPENDED", "中断"), java.util.Map.entry("CLOSED", "完了"), java.util.Map.entry("CANCELED", "中止"))), null, null));
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
        for (var checked : rows7) {
            if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE, Boolean.valueOf(checked.getResumeOn() != null)))) {
                if (Boolean.FALSE.equals(ServiceChecks.atLeast(checked.getResumeOn(), checked.getDate()))) {
            violations.add(ServiceChecks.violation("check.resume-not-before-suspend", "resumeOn", input.getId(), null, checked.getResumeOn(), checked.getDate()));
                }
            }
        }
        if (preview) return new OperationOutcome(input.getId(), violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        if (!rows6.isEmpty()) {
            ServiceChecks.call(new TodoMapper.SuspendTodoInput(), p -> { p.setId(input.getId()); p.setContextToday(today); }, todoMapper::suspendTodo);
        }
        for (var writing : rows7) {
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
