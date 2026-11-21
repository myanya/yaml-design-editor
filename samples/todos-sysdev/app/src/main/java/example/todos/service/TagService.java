package example.todos.service;

import example.todos.data.model.*;
import example.todos.data.mapper.*;
import example.todos.service.input.*;
import example.todos.service.runtime.*;
import example.todos.service.error.BusinessViolation;
import org.springframework.stereotype.Service;

/** 確定した処理設計・入力・Mapper の対応から生成。 */
@Service
public class TagService {
    private final OperationRunner runner;
    private final java.time.Clock clock;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final TodoTaskMapper todoTaskMapper;
    private final TodoMapper todoMapper;
    public TagService(OperationRunner runner, java.time.Clock clock, CategoryMapper categoryMapper, TagMapper tagMapper, TodoTaskMapper todoTaskMapper, TodoMapper todoMapper) {
        this.runner = runner;
        this.clock = clock;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.todoTaskMapper = todoTaskMapper;
        this.todoMapper = todoMapper;
    }
    public String addTag(java.lang.String name) {
        var input = new AddTagInput();
        input.setName(name);
        return runner.execute(() -> runaddTag(input, false)).id();
    }
    private OperationOutcome runaddTag(AddTagInput input, boolean preview) {
        var today = java.time.LocalDate.now(clock);
        var violations = new java.util.ArrayList<BusinessViolation>();
        var numberedId = SequenceNumbers.nextId(tagMapper.maxId(), "TAG-", 3);
        var rows2 = new java.util.ArrayList<TagRow>();
        if (Boolean.TRUE.equals(ServiceChecks.and(Boolean.TRUE))) {
            var row2 = new TagRow();
            row2.setId(numberedId);
            row2.setName(input.getName());
            rows2.add(row2);
        }
        if (preview) return new OperationOutcome(numberedId, violations);
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        for (var writing : rows2) {
            tagMapper.insert(writing);
        }
        if (!violations.isEmpty()) throw new example.todos.service.error.RefusedException(violations.getFirst());
        return new OperationOutcome(numberedId, violations);
    }

    public TagMapper.FindDetailResult findDetail(java.lang.String id) {
        var input = new TagMapper.FindDetailInput();
        input.setId(id);
        return tagMapper.findDetail(input);
    }
}
