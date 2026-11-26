package verification;

import example.todos.TodoApplication;
import example.todos.service.*;
import example.todos.service.error.RefusedException;
import example.todos.service.error.NotFoundException;
import example.todos.service.input.EditTodoInput;
import example.todos.service.runtime.ServiceChecks;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 生成した操作本体を実 DB で動かし、書込みと拒否の境界を確認する。 */
@SpringBootTest(classes = {TodoApplication.class, ServiceIntegrationTest.Time.class}, properties = {
    "spring.datasource.url=jdbc:h2:mem:service-verification;DB_CLOSE_DELAY=-1", "server.port=0"
})
class ServiceIntegrationTest {
    @TestConfiguration static class Time {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired TodoService todos;
    @Autowired TodoTaskService tasks;
    @Autowired CategoryService categories;
    @Autowired TagService tags;
    @Autowired JdbcTemplate jdbc;
    final LocalDate today = LocalDate.of(2026, 11, 21);

    @BeforeEach void clean() {
        for (var table : List.of("todo_tags", "todo_comments", "todo_history", "todo_task")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE todo SET follows = NULL");
        for (var table : List.of("todo", "category", "tag")) jdbc.update("DELETE FROM " + table);
    }
    String register() { return todos.registerTodo("仕事", "概要", null, null, null, null, null); }
    /** 読んだときの版（同時更新の検出に渡す）。無い ID は null（見つからない扱いは Service が決める）。 */
    Long v(String id) { var found = jdbc.queryForList("SELECT version FROM todo WHERE id = ?", Long.class, id); return found.isEmpty() ? null : found.get(0); }
    String status(String id) { return jdbc.queryForObject("SELECT status FROM todo WHERE id = ?", String.class, id); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    void refusal(String key, Runnable operation) {
        var error = assertThrows(RefusedException.class, operation::run);
        assertEquals(key, error.getViolation().key());
    }

    @Test void everyDesignedOperationPersistsAndStateChecksRefuseInvalidTransitions() {
        var category = categories.addCategory("業務");
        var tag = tags.addTag("重要");
        var id = todos.registerTodo("仕事", "概要", category, List.of(tag), null, today.plusDays(1), "詳細");
        assertEquals("TODO-001", id);
        assertEquals(1, count("todo_tags"));
        assertEquals("OPEN", status(id));
        todos.addTodoComment(id, today, "記録", v(id));
        var child = todos.addTodoTask(id, "準備", v(id));
        assertEquals(id + "-S01", child);
        todos.startTodo(id, v(id));
        refusal("start-todo.state", () -> todos.startTodo(id, v(id)));
        assertTrue(todos.checkCompleteTodo(id, today, "完了", v(id)).stream().anyMatch(v -> v.key().equals("complete-todo.no-open-task")));
        refusal("complete-todo.no-open-task", () -> todos.completeTodo(id, today, "完了", v(id)));
        tasks.todoTaskFinish(child);
        todos.suspendTodo(id, today, "待機", today.plusDays(1), v(id));
        assertEquals("SUSPENDED", status(id));
        todos.resumeTodo(id, today, "再開", v(id));
        todos.completeTodo(id, today, "完了", v(id));
        assertEquals("CLOSED", status(id));
        var inactive = assertThrows(RefusedException.class, () -> todos.addTodoTask(id, "追加", v(id)));
        assertEquals("add-todo-task.active", inactive.getViolation().key());
        assertEquals(id, inactive.getViolation().values().get("id"));
        var other = register();
        todos.cancelTodo(other, "中止", v(other));
        assertEquals("CANCELED", status(other));
        assertThrows(NotFoundException.class, () -> todos.startTodo("TODO-999", v("TODO-999")));
        assertEquals(7, count("todo_history"));
    }

    @Test void staleVersionIsRefusedAndEveryUpdateAdvancesTheVersion() {
        var id = register();
        assertEquals(0L, v(id), "登録した直後の版は 0");
        todos.startTodo(id, 0L);
        assertEquals(1L, v(id), "更新のたびに 1 進む");
        // 古い画面（版 0）からの更新は断り、何も書かない
        refusal("add-todo-comment.version-unchanged", () -> todos.addTodoComment(id, today, "古い画面から", 0L));
        assertEquals(0, count("todo_comments"));
        assertEquals(1L, v(id));
        // 確認（書かない検査）でも同じ断りを返す
        assertTrue(todos.checkSuspendTodo(id, today, "待機", today.plusDays(1), 0L).stream().anyMatch(v -> v.key().equals("suspend-todo.version-unchanged")));
        todos.addTodoComment(id, today, "最新の画面から", 1L);
        assertEquals(2L, v(id));
        assertEquals(1, count("todo_comments"));
    }

    @Test void previewSharesChecksWithoutWritingOrConsumingNumbers() {
        var invalid = todos.checkRegisterTodo("仕事", "概要", null, null, null, today.minusDays(1), null);
        assertTrue(invalid.stream().anyMatch(v -> v.key().equals("check.due-not-before-created") && "due".equals(v.field())));
        assertEquals(0, count("todo"));
        assertTrue(todos.checkRegisterTodo("仕事", "概要", null, null, null, today, null).isEmpty());
        assertEquals("TODO-001", register());
        todos.startTodo("TODO-001", v("TODO-001"));
        int before = count("todo_history");
        var resume = todos.checkSuspendTodo("TODO-001", today, "待機", today.minusDays(1), v("TODO-001"));
        assertTrue(resume.stream().anyMatch(v -> v.key().equals("check.resume-not-before-suspend")));
        refusal("check.resume-not-before-suspend", () -> todos.suspendTodo("TODO-001", today, "待機", today.minusDays(1), v("TODO-001")));
        assertEquals("IN_PROGRESS", status("TODO-001"));
        assertEquals(before, count("todo_history"));
    }

    @Test void editUsesClearPriorityAndReplacesCollections() {
        var id = register();
        var category = categories.addCategory("業務");
        var tag = tags.addTag("重要");
        todos.addTodoComment(id, today, "残る", v(id));
        assertTrue(todos.checkEditTodo(id, "修正", null, category, List.of(tag), null, today.plusDays(2), "詳細", null, null, v(id)).isEmpty());
        assertEquals(0, count("todo_tags"));
        assertEquals("仕事", jdbc.queryForObject("SELECT title FROM todo WHERE id = ?", String.class, id));
        todos.editTodo(id, "修正", null, category, List.of(tag), null, today.plusDays(2), "詳細", null, null, v(id));
        assertEquals(1, count("todo_tags"));
        var clear = new EditTodoInput.EditTodoClear();
        clear.setCategory(true);
        clear.setDueOn(true);
        clear.setTags(true);
        todos.editTodo(id, null, null, category, List.of(tag), null, today.minusDays(1), null, null, clear, v(id));
        assertNull(jdbc.queryForObject("SELECT category FROM todo WHERE id = ?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT due_on FROM todo WHERE id = ?", LocalDate.class, id));
        assertEquals(0, count("todo_tags"));
        assertEquals(1, count("todo_comments"), "TODO の編集はコメントを変えない");
    }

    @Test void commentRowIsEditedAndDeletedBySequenceAndMissingRowIsRefused() {
        var id = register();
        todos.addTodoComment(id, today, "一つ目", v(id));
        todos.addTodoComment(id, today, "二つ目", v(id));
        todos.editTodoComment(id, 2L, null, "直した", v(id));
        assertEquals("直した", jdbc.queryForObject("SELECT body FROM todo_comments WHERE todo_id = ? AND seq = 2", String.class, id));
        assertEquals("一つ目", jdbc.queryForObject("SELECT body FROM todo_comments WHERE todo_id = ? AND seq = 1", String.class, id));
        todos.deleteTodoComment(id, 1L, v(id));
        assertEquals(1, count("todo_comments"));
        // 無い行は、親の更新日も含めて何も書かずに断る
        jdbc.update("UPDATE todo SET updated_on = ? WHERE id = ?", today.minusDays(3), id);
        assertThrows(NotFoundException.class, () -> todos.deleteTodoComment(id, 1L, v(id)));
        assertThrows(NotFoundException.class, () -> todos.editTodoComment(id, 7L, null, "無い行", v(id)));
        assertEquals(today.minusDays(3), jdbc.queryForObject("SELECT updated_on FROM todo WHERE id = ?", LocalDate.class, id));
        assertEquals(1, count("todo_comments"));
    }

    @Test void postWriteFailureRollsBackChildAndParentTogether() {
        var id = register();
        var first = todos.addTodoTask(id, "一つ目", v(id));
        todos.addTodoTask(id, "二つ目", v(id));
        jdbc.update("UPDATE todo SET status = 'CLOSED', completed_on = ?, result = '検証', updated_on = ? WHERE id = ?", today, today.minusDays(1), id);
        var error = assertThrows(RefusedException.class, () -> tasks.todoTaskFinish(first));
        assertEquals("check.closed-has-no-open-task", error.getViolation().key());
        assertEquals(id, error.getViolation().values().get("id"));
        assertFalse(jdbc.queryForObject("SELECT done FROM todo_task WHERE id = ?", Boolean.class, first));
        assertEquals(today.minusDays(1), jdbc.queryForObject("SELECT updated_on FROM todo WHERE id = ?", LocalDate.class, id));
        jdbc.update("UPDATE todo SET status = 'OPEN', completed_on = NULL, created_on = ? WHERE id = ?", today.plusDays(1), id);
        refusal("check.updated-not-before-created", () -> tasks.todoTaskFinish(first));
        assertFalse(jdbc.queryForObject("SELECT done FROM todo_task WHERE id = ?", Boolean.class, first));
    }

    @Test void selfReferenceAndCyclesAreRefusedBeforeWriting() {
        refusal("check.follows-not-self", () -> todos.registerTodo("仕事", "概要", null, null, "TODO-001", null, null));
        var first = register();
        var second = todos.registerTodo("二つ目", "概要", null, null, first, null, null);
        jdbc.update("UPDATE todo SET follows = ? WHERE id = ?", second, first);
        refusal("check.follow-up-of.acyclic", () -> todos.registerTodo("三つ目", "概要", null, null, first, null, null));
        assertEquals(2, count("todo"));
    }

    @Test void concurrentServicesAllocateDistinctIdsAndHistoryRows() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 24; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return register();
            }));
            start.countDown();
            var unique = new HashSet<String>();
            for (var result : results) assertTrue(unique.add(result.get(30, TimeUnit.SECONDS)));
            assertEquals(24, count("todo"));
            assertEquals(24, count("todo_history"));
        }
    }

    @Test void unknownAndWhitespaceKeepTheMeaningOfTheDesignExpressions() {
        assertNull(ServiceChecks.not(ServiceChecks.equal(null, "CLOSED")));
        assertNull(ServiceChecks.atLeast(null, today));
        assertTrue(ServiceChecks.empty("\u00a0\u3000"));
        assertFalse(ServiceChecks.empty("文字"));
        assertEquals(Boolean.FALSE, ServiceChecks.and(null, false));
        assertEquals(Boolean.TRUE, ServiceChecks.or(null, true));
    }
}
