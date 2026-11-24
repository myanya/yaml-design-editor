package verification;

import static org.assertj.core.api.Assertions.assertThat;

import example.todos.TodoApplication;
import example.todos.data.mapper.*;
import example.todos.data.model.*;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 標準の todos 設計を使う結合検証。生成アプリのコピーにだけ追加して実行する。 */
@SpringBootTest(classes = TodoApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:mapper-verification;DB_CLOSE_DELAY=-1", "server.port=0"
})
@Transactional
class MapperIntegrationTest {
    @Autowired CategoryMapper categories;
    @Autowired TagMapper tags;
    @Autowired TodoMapper todos;
    @Autowired TodoTaskMapper tasks;
    @Autowired JdbcTemplate jdbc;
    final LocalDate today = LocalDate.of(2026, 11, 21);
    final Set<String> called = new HashSet<>();

    Object call(Object mapper, Class<?> type, String name, Map<String, Object> values) throws Exception {
        Method method = java.util.Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        called.add(type.getSimpleName() + "." + name);
        if (method.getParameterCount() == 0) return method.invoke(mapper);
        Object input = method.getParameterTypes()[0].getConstructor().newInstance();
        var bean = new BeanWrapperImpl(input);
        values.forEach(bean::setPropertyValue);
        return method.invoke(mapper, input);
    }
    Object todo(String name, Map<String, Object> values) throws Exception { return call(todos, TodoMapper.class, name, values); }
    Object task(String name, Map<String, Object> values) throws Exception { return call(tasks, TodoTaskMapper.class, name, values); }
    Object property(Object row, String key) { return new BeanWrapperImpl(row).getPropertyValue(key); }
    List<?> list(Object result) { return (List<?>) result; }
    Map<String, Object> id(String id) { return Map.of("id", id); }
    Map<String, Object> input(String id) { return Map.of("id", id, "contextToday", today); }

    void insertTodo(String id, String title, String category, LocalDate due) throws Exception {
        var values = new java.util.HashMap<String, Object>(Map.of("id", id, "title", title, "summary", "概要", "createdOn", today.minusDays(3), "updatedOn", today.minusDays(3)));
        if (category != null) values.put("category", category);
        if (due != null) values.put("dueOn", due);
        assertThat(todo("insert", values)).isEqualTo(1);
    }

    @Test
    void allDesignedMethodsReadWriteAndPreserveMeaning() throws Exception {
        call(categories, CategoryMapper.class, "insert", Map.of("id", "CAT-001", "name", "O'Brien & <業務>"));
        call(categories, CategoryMapper.class, "insert", Map.of("id", "CAT-002", "name", "未使用"));
        call(tags, TagMapper.class, "insert", Map.of("id", "TAG-001", "name", "重要"));
        call(tags, TagMapper.class, "insert", Map.of("id", "TAG-002", "name", "連絡"));
        insertTodo("TODO-999", "親", "CAT-001", today.minusDays(1));
        insertTodo("TODO-1000", "引用 ' < > & \\ の保持", "CAT-001", today.plusDays(1));
        insertTodo("TODO-003", "カテゴリなし", null, null);
        assertThat(todo("maxId", Map.of())).isEqualTo("TODO-1000");
        assertThat(call(categories, CategoryMapper.class, "maxId", Map.of())).isEqualTo("CAT-002");
        assertThat(call(tags, TagMapper.class, "maxId", Map.of())).isEqualTo("TAG-002");
        assertThat(property(todo("findById", id("TODO-999")), "status")).isEqualTo("OPEN");
        assertThat(property(todo("findById", id("TODO-1000")), "title")).isEqualTo("引用 ' < > & \\ の保持");

        todo("insertTodoTags", Map.of("todoId", "TODO-999", "seq", 2L, "tagId", "TAG-002"));
        todo("insertTodoTags", Map.of("todoId", "TODO-999", "seq", 1L, "tagId", "TAG-001"));
        todo("insertTodoTags", Map.of("todoId", "TODO-1000", "seq", 1L, "tagId", "TAG-001"));
        todo("insertTodoComments", Map.of("todoId", "TODO-999", "seq", 2L, "date", today, "body", "後"));
        todo("insertTodoComments", Map.of("todoId", "TODO-999", "seq", 1L, "date", today, "body", "前 <&> '"));
        todo("insertTodoHistory", Map.of("todoId", "TODO-999", "seq", 1L, "date", today, "action", "register-todo", "to", "OPEN"));
        assertThat(todo("maxSeqTodoComments", id("TODO-999"))).isEqualTo(2L);
        assertThat(todo("maxSeqTodoHistory", id("TODO-999"))).isEqualTo(1L);
        assertThat(todo("maxSeqTodoComments", id("TODO-003"))).isNull();
        task("insert", Map.of("id", "TODO-999-TASK-999", "todoId", "TODO-999", "seq", 2L, "title", "後"));
        task("insert", Map.of("id", "TODO-999-TASK-1000", "todoId", "TODO-999", "seq", 1L, "title", "前", "done", false));
        assertThat(task("maxId", id("TODO-999"))).isEqualTo("TODO-999-TASK-1000");
        assertThat(task("maxSeq", id("TODO-999"))).isEqualTo(2L);
        assertThat(property(task("findById", id("TODO-999-TASK-999")), "done")).isEqualTo(false);

        Object detail = todo("findDetail", id("TODO-999"));
        assertThat(property(detail, "tags")).isEqualTo(List.of("TAG-001", "TAG-002"));
        assertThat(property(detail, "tagsDisplay")).isEqualTo(List.of("重要", "連絡"));
        assertThat(property(detail, "categoryDisplay")).isEqualTo("O'Brien & <業務>");
        assertThat(property(list(property(detail, "comments")).getFirst(), "body")).isEqualTo("前 <&> '");
        assertThat(property(list(property(detail, "tasks")).getFirst(), "seq")).isEqualTo(1L);
        assertThat(list(property(detail, "history"))).hasSize(1);

        var filters = Map.<String, Object>of("tags", List.of("TAG-001", "TAG-002"), "category", "CAT-001");
        assertThat(list(todo("selectTodoDashboardAll", filters))).hasSize(2);
        assertThat(todo("countTodoDashboardAll", filters)).isEqualTo(2L);
        assertThat(list(todo("selectTodoDashboardAll", Map.of("tags", List.of(), "category", "")))).hasSize(3);
        assertThat(todo("countTodoDashboardAll", Map.of())).isEqualTo(3L);
        var overdue = Map.<String, Object>of("tags", List.of("TAG-001", "TAG-002"), "category", "CAT-001", "today", today);
        assertThat(list(todo("selectTodoDashboardOverdue", overdue))).hasSize(1);
        assertThat(todo("countTodoDashboardOverdue", overdue)).isEqualTo(1L);
        assertThat(list(todo("selectAll", Map.of()))).hasSize(3);
        assertThat(task("countCompleteTodoNoOpenTask", id("TODO-999"))).isEqualTo(2L);
        assertThat(task("countClosedHasNoOpenTask", Map.of("todoId", "TODO-999"))).isEqualTo(0L);

        for (var pair : List.of(new Object[]{categories, CategoryMapper.class, "Categories", "CAT-001"}, new Object[]{tags, TagMapper.class, "Tags", "TAG-001"})) {
            Object mapper = pair[0]; Class<?> type = (Class<?>) pair[1]; String axis = (String) pair[2];
            assertThat(call(mapper, type, "findDetail", id((String) pair[3]))).isNotNull();
            assertThat(list(call(mapper, type, "selectAll", Map.of()))).hasSize(2);
            var aggregate = list(call(mapper, type, "selectTodoDashboard" + axis, Map.of()));
            assertThat(aggregate).hasSize(2);
            Object used = aggregate.stream().filter(r -> property(r, "id").equals(pair[3])).findFirst().orElseThrow();
            assertThat(property(used, "count0")).isEqualTo(2L);
            assertThat(property(used, "count1")).isEqualTo(0L);
            assertThat(call(mapper, type, "countTodoDashboard" + axis, Map.of())).isEqualTo(2L);
            var master = list(call(mapper, type, "selectTodoDashboard" + axis + "Master", Map.of()));
            Object row = master.stream().filter(r -> property(r, "id").equals(pair[3])).findFirst().orElseThrow();
            assertThat(property(row, "column2")).isEqualTo(2L);
            assertThat(call(mapper, type, "countTodoDashboard" + axis + "Master", Map.of())).isEqualTo(2L);
        }

        for (String method : List.of("addTodoComment", "addTodoTask", "startTodo", "suspendTodo", "resumeTodo")) assertThat(todo(method, input("TODO-999"))).isEqualTo(1);
        assertThat(property(todo("findById", id("TODO-999")), "status")).isEqualTo("IN_PROGRESS");
        todo("editTodo", Map.of("id", "TODO-999", "title", "変更", "clearCategory", true, "category", "CAT-002", "clearDueOn", true, "contextToday", today));
        Object edited = todo("findById", id("TODO-999"));
        assertThat(property(edited, "title")).isEqualTo("変更");
        assertThat(property(edited, "category")).isNull();
        assertThat(property(edited, "dueOn")).isNull();
        assertThat(property(edited, "summary")).isEqualTo("概要");
        assertThat(property(edited, "updatedOn")).isEqualTo(today);
        task("todoTaskFinish", id("TODO-999-TASK-999"));
        todo("todoTaskFinish", Map.of("todoId", "TODO-999", "contextToday", today));
        assertThat(property(task("findById", id("TODO-999-TASK-999")), "done")).isEqualTo(true);
        var all = list(todo("selectTodoDashboardAll", Map.of()));
        assertThat(property(all.stream().filter(r -> property(r, "id").equals("TODO-999")).findFirst().orElseThrow(), "column3")).isEqualTo("1/2");
        assertThat(property(all.stream().filter(r -> property(r, "id").equals("TODO-003")).findFirst().orElseThrow(), "column3")).isEqualTo("");
        todo("completeTodo", Map.of("id", "TODO-999", "date", today, "result", "完了", "contextToday", today));
        assertThat(task("countClosedHasNoOpenTask", Map.of("todoId", "TODO-999"))).isEqualTo(1L);
        todo("cancelTodo", Map.of("id", "TODO-1000", "reason", "中止", "contextToday", today));
        assertThat(property(todo("findById", id("TODO-1000")), "status")).isEqualTo("CANCELED");
        assertThat(todo("deleteTodoTags", id("TODO-999"))).isEqualTo(2);
        // コメントの 1 行の更新と削除: 親のキーと並び順（seq）で 1 行だけ。入れなかった列は元のまま。無い行は 0 件
        assertThat(todo("editTodoCommentCommentsRow", Map.of("id", "TODO-999", "row", 1L, "body", "直した ' <&>"))).isEqualTo(1);
        var comments = list(property(todo("findDetail", id("TODO-999")), "comments"));
        assertThat(comments).hasSize(2);
        assertThat(property(comments.get(0), "body")).isEqualTo("直した ' <&>");
        assertThat(property(comments.get(0), "date")).isEqualTo(today);
        assertThat(property(comments.get(1), "body")).isEqualTo("後");
        assertThat(todo("editTodoCommentCommentsRow", Map.of("id", "TODO-999", "row", 9L, "body", "無い行"))).isEqualTo(0);
        assertThat(todo("editTodoCommentCommentsRow", Map.of("id", "TODO-1000", "row", 1L, "body", "別の TODO"))).isEqualTo(0);
        assertThat(todo("editTodoComment", input("TODO-999"))).isEqualTo(1);
        assertThat(todo("deleteTodoCommentCommentsRow", Map.of("id", "TODO-999", "row", 1L))).isEqualTo(1);
        assertThat(todo("deleteTodoCommentCommentsRow", Map.of("id", "TODO-999", "row", 1L))).isEqualTo(0);
        assertThat(todo("deleteTodoComment", input("TODO-999"))).isEqualTo(1);
        assertThat(list(property(todo("findDetail", id("TODO-999")), "comments"))).hasSize(1);
        assertThat(todo("maxSeqTodoComments", id("TODO-999"))).isEqualTo(2L);
        assertThat(list(property(todo("findDetail", id("TODO-999")), "tags"))).isEmpty();
        assertThat(list(property(todo("findDetail", id("TODO-1000")), "tags"))).hasSize(1);
        var expected = new HashSet<String>();
        for (var type : List.of(CategoryMapper.class, TagMapper.class, TodoMapper.class, TodoTaskMapper.class))
            for (var method : type.getDeclaredMethods()) expected.add(type.getSimpleName() + "." + method.getName());
        assertThat(called).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(called).hasSize(51);
    }
}
