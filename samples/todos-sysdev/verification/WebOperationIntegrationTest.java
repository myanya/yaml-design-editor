package verification;

import example.todos.TodoApplication;
import example.todos.web.form.*;
import example.todos.web.operation.*;
import example.todos.service.error.RefusedException;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 画面入力から実際の Service・DB まで、型変換と再検査の境界を確認する。 */
@SpringBootTest(classes = {TodoApplication.class, WebOperationIntegrationTest.Time.class}, properties = {
    "spring.datasource.url=jdbc:h2:mem:web-operation-verification;DB_CLOSE_DELAY=-1", "server.port=0"
})
class WebOperationIntegrationTest {
    @TestConfiguration static class Time {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired RegisterTodoWebOperation register;
    @Autowired EditTodoWebOperation edit;
    @Autowired AddCategoryWebOperation category;
    @Autowired AddTagWebOperation tag;
    @Autowired AddTodoCommentWebOperation comment;
    @Autowired AddTodoTaskWebOperation task;
    @Autowired StartTodoWebOperation start;
    @Autowired SuspendTodoWebOperation suspend;
    @Autowired ResumeTodoWebOperation resume;
    @Autowired CompleteTodoWebOperation complete;
    @Autowired CancelTodoWebOperation cancel;
    @Autowired TodoTaskFinishWebOperation finish;
    @Autowired JdbcTemplate jdbc;
    final LocalDate today = LocalDate.of(2026, 11, 21);

    @BeforeEach void clean() {
        for (var table : List.of("todo_tags", "todo_comments", "todo_history", "todo_task")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE todo SET follows = NULL");
        for (var table : List.of("todo", "category", "tag")) jdbc.update("DELETE FROM " + table);
    }
    RegisterTodoForm form() {
        var form = new RegisterTodoForm();
        form.setTitle("仕事"); form.setSummary("概要");
        return form;
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    String status(String id) { return jdbc.queryForObject("SELECT status FROM todo WHERE id = ?", String.class, id); }

    @Test void 全操作を対応するフォームから実行できる() {
        var categoryForm = new AddCategoryForm(); categoryForm.setName("業務");
        var categoryId = category.execute(categoryForm);
        var tagForm = new AddTagForm(); tagForm.setName("重要");
        var tagId = tag.execute(tagForm);
        var registration = form(); registration.setCategory(categoryId); registration.setTags(List.of(tagId));
        var id = register.execute(registration);
        var editForm = new EditTodoForm(); editForm.setTitle("確認する仕事"); editForm.setDueOn(today.plusDays(2));
        assertTrue(edit.check(id, editForm).isEmpty());
        assertEquals(id, edit.execute(id, editForm));
        var commentForm = new AddTodoCommentForm(); commentForm.setDate(today); commentForm.setBody("確認しました");
        assertEquals(id, comment.execute(id, commentForm));
        var taskForm = new AddTodoTaskForm(); taskForm.setTitle("動作確認");
        var taskId = task.execute(id, taskForm);
        assertEquals(taskId, finish.execute(taskId));
        assertEquals(id, start.execute(id));
        var suspendForm = new SuspendTodoForm(); suspendForm.setDate(today); suspendForm.setReason("確認待ち"); suspendForm.setResumeOn(today.plusDays(1));
        assertTrue(suspend.check(id, suspendForm).isEmpty());
        assertEquals(id, suspend.execute(id, suspendForm));
        var resumeForm = new ResumeTodoForm(); resumeForm.setDate(today); resumeForm.setNote("確認済み");
        assertEquals(id, resume.execute(id, resumeForm));
        var completeForm = new CompleteTodoForm(); completeForm.setDate(today); completeForm.setResult("作業完了");
        assertTrue(complete.check(id, completeForm).isEmpty());
        assertEquals(id, complete.execute(id, completeForm));
        assertEquals("CLOSED", status(id));
        var other = register.execute(form());
        var cancelForm = new CancelTodoForm(); cancelForm.setReason("不要になりました");
        assertEquals(other, cancel.execute(other, cancelForm));
        assertEquals("CANCELED", status(other));
        assertEquals(1, count("todo_comments"));
        assertEquals(1, count("todo_tags"));
    }

    @Test void 明細とクリア指定を別の型へ写し省略と空とfalseを区別する() {
        var form = new EditTodoForm();
        var omitted = edit.toInput("TODO-001", form);
        assertNull(omitted.getTags()); assertNull(omitted.getComments()); assertNull(omitted.getClear());
        var row = new EditTodoForm.EditTodoCommentsRow(); row.setDate(today); row.setBody("  空白を残す  ");
        var rows = new ArrayList<EditTodoForm.EditTodoCommentsRow>(); rows.add(row);
        var tags = new ArrayList<String>(); tags.add("TAG-001");
        form.setComments(rows); form.setTags(tags);
        var clear = new EditTodoForm.EditTodoClear(); clear.setDueOn(false); clear.setComments(true);
        form.setClear(clear);
        var input = edit.toInput("TODO-001", form);
        assertEquals("TODO-001", input.getId());
        assertEquals(today, input.getComments().getFirst().getDate());
        assertEquals("  空白を残す  ", input.getComments().getFirst().getBody());
        assertEquals(false, input.getClear().getDueOn()); assertEquals(true, input.getClear().getComments());
        assertNull(input.getClear().getCategory());
        row.setBody("変更"); rows.clear(); tags.clear(); clear.setComments(false);
        assertEquals("  空白を残す  ", input.getComments().getFirst().getBody());
        assertEquals(List.of("TAG-001"), input.getTags()); assertEquals(true, input.getClear().getComments());
        var empty = edit.toInput("TODO-001", form);
        assertTrue(empty.getComments().isEmpty()); assertTrue(empty.getTags().isEmpty());
    }

    @Test void 確認でDBを書かず実行時にも入力と業務の規則を確かめる() {
        var form = form();
        assertTrue(register.check(form).isEmpty()); assertEquals(0, count("todo"));
        form.setDue(today.minusDays(1));
        assertTrue(register.check(form).stream().anyMatch(v -> v.key().equals("check.due-not-before-created")));
        assertThrows(RefusedException.class, () -> register.execute(form));
        assertEquals(0, count("todo"));
        form.setDue(null); assertTrue(register.check(form).isEmpty());
        form.setTitle("あ".repeat(101));
        assertThrows(ConstraintViolationException.class, () -> register.execute(form));
        assertEquals(0, count("todo"));
    }

    @Test void 明細の入力違反と空の行と対象未指定を更新前に拒否する() {
        var id = register.execute(form());
        var form = new EditTodoForm();
        var row = new EditTodoForm.EditTodoCommentsRow(); row.setBody("日付がない");
        form.setComments(List.of(row));
        var failure = assertThrows(ConstraintViolationException.class, () -> edit.execute(id, form));
        assertTrue(failure.getConstraintViolations().stream().anyMatch(v -> v.getPropertyPath().toString().equals("comments[0].date")));
        form.setComments(Arrays.asList((EditTodoForm.EditTodoCommentsRow) null));
        assertThrows(IllegalArgumentException.class, () -> edit.execute(id, form));
        assertThrows(IllegalArgumentException.class, () -> start.execute(""));
        assertThrows(IllegalArgumentException.class, () -> register.execute(null));
        assertEquals(0, count("todo_comments"));
        assertEquals("OPEN", status(id));
    }

    @Test void 既存明細の置換とクリアの優先順位がDBにも届く() {
        var id = register.execute(form());
        var row = new EditTodoForm.EditTodoCommentsRow(); row.setDate(today); row.setBody("明細");
        var form = new EditTodoForm(); form.setComments(List.of(row)); form.setDetails("詳細");
        edit.execute(id, form);
        assertEquals(1, count("todo_comments"));
        var clearing = new EditTodoForm(); clearing.setComments(List.of(row)); clearing.setDetails("残さない");
        var clear = new EditTodoForm.EditTodoClear(); clear.setComments(true); clear.setDetails(true); clearing.setClear(clear);
        edit.execute(id, clearing);
        assertEquals(0, count("todo_comments"));
        assertNull(jdbc.queryForObject("SELECT details FROM todo WHERE id = ?", String.class, id));
    }
}
