package verification;

import example.todos.TodoApplication;
import example.todos.web.form.*;
import example.todos.web.operation.*;
import example.todos.service.error.NotFoundException;
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
    @Autowired EditTodoCommentWebOperation editComment;
    @Autowired DeleteTodoCommentWebOperation deleteComment;
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

    @Test void 並びとクリア指定を別の型へ写し省略と空とfalseを区別する() {
        var form = new EditTodoForm();
        var omitted = edit.toInput("TODO-001", form);
        assertNull(omitted.getTags()); assertNull(omitted.getClear());
        var tags = new ArrayList<String>(); tags.add("TAG-001");
        form.setTags(tags); form.setDetails("  空白を残す  ");
        var clear = new EditTodoForm.EditTodoClear(); clear.setDueOn(false); clear.setTags(true);
        form.setClear(clear);
        var input = edit.toInput("TODO-001", form);
        assertEquals("TODO-001", input.getId());
        assertEquals("  空白を残す  ", input.getDetails());
        assertEquals(false, input.getClear().getDueOn()); assertEquals(true, input.getClear().getTags());
        assertNull(input.getClear().getCategory());
        // 写した後に Form を変えても、Service の入力は変わらない
        tags.clear(); clear.setTags(false);
        assertEquals(List.of("TAG-001"), input.getTags()); assertEquals(true, input.getClear().getTags());
        var empty = edit.toInput("TODO-001", form);
        assertTrue(empty.getTags().isEmpty());
    }

    @Test void コメントの編集は番号と変えた値を写し空の本文は省略になる() {
        var form = new EditTodoCommentForm(); form.setRow(2L); form.setBody("  空白を残す  ");
        var input = editComment.toInput("TODO-001", form);
        assertEquals("TODO-001", input.getId()); assertEquals(2L, input.getRow());
        assertNull(input.getDate()); assertEquals("  空白を残す  ", input.getBody());
        form.setBody("");
        assertNull(editComment.toInput("TODO-001", form).getBody());
        var remove = new DeleteTodoCommentForm(); remove.setRow(3L);
        assertEquals(3L, deleteComment.toInput("TODO-001", remove).getRow());
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

    @Test void コメントの番号の欠落と入力なしと対象未指定を更新前に拒否する() {
        var id = register.execute(form());
        var add = new AddTodoCommentForm(); add.setDate(today); add.setBody("最初");
        comment.execute(id, add);
        var form = new EditTodoCommentForm(); form.setBody("番号がない");
        var failure = assertThrows(ConstraintViolationException.class, () -> editComment.execute(id, form));
        assertTrue(failure.getConstraintViolations().stream().anyMatch(v -> v.getPropertyPath().toString().equals("row")));
        assertThrows(ConstraintViolationException.class, () -> deleteComment.execute(id, new DeleteTodoCommentForm()));
        assertThrows(IllegalArgumentException.class, () -> editComment.execute(id, null));
        form.setRow(1L);
        assertThrows(IllegalArgumentException.class, () -> editComment.execute("", form));
        assertThrows(IllegalArgumentException.class, () -> start.execute(""));
        assertThrows(IllegalArgumentException.class, () -> register.execute(null));
        assertEquals("最初", jdbc.queryForObject("SELECT body FROM todo_comments WHERE todo_id = ?", String.class, id));
        assertEquals("OPEN", status(id));
    }

    List<String> bodies(String id) { return jdbc.queryForList("SELECT body FROM todo_comments WHERE todo_id = ? ORDER BY seq", String.class, id); }
    List<Long> seqs(String id) { return jdbc.queryForList("SELECT seq FROM todo_comments WHERE todo_id = ? ORDER BY seq", Long.class, id); }

    @Test void コメントは番号の一件だけを直し消し無い番号は何も変えずに断る() {
        var id = register.execute(form());
        var other = register.execute(form());
        for (var body : List.of("一つ目", "二つ目", "三つ目")) {
            var add = new AddTodoCommentForm(); add.setDate(today.minusDays(1)); add.setBody(body);
            comment.execute(id, add); comment.execute(other, add);
        }
        // 編集: 並び順（seq）の 1 行だけ。入力しなかった記録日は元のまま
        var change = new EditTodoCommentForm(); change.setRow(2L); change.setBody("直した");
        assertEquals(id, editComment.execute(id, change));
        assertEquals(List.of("一つ目", "直した", "三つ目"), bodies(id));
        assertEquals(today.minusDays(1), jdbc.queryForObject("SELECT date FROM todo_comments WHERE todo_id = ? AND seq = 2", LocalDate.class, id));
        assertEquals(List.of("一つ目", "二つ目", "三つ目"), bodies(other), "ほかの TODO の同じ番号は変えない");
        // 削除: その 1 行だけ。残った行の番号は振り直さない
        var remove = new DeleteTodoCommentForm(); remove.setRow(1L);
        assertEquals(id, deleteComment.execute(id, remove));
        assertEquals(List.of(2L, 3L), seqs(id));
        assertEquals(3, bodies(other).size());
        // 消した番号・無い番号は、何も変えずに断る
        assertThrows(NotFoundException.class, () -> deleteComment.execute(id, remove));
        var missing = new EditTodoCommentForm(); missing.setRow(9L); missing.setBody("無い行");
        assertThrows(NotFoundException.class, () -> editComment.execute(id, missing));
        assertEquals(List.of("直した", "三つ目"), bodies(id));
        // 追加は、消した後も最大の番号の次
        var add = new AddTodoCommentForm(); add.setDate(today); add.setBody("四つ目");
        comment.execute(id, add);
        assertEquals(List.of(2L, 3L, 4L), seqs(id));
    }

    @Test void 編集のクリアは入力した値より優先しコメントは変えない() {
        var id = register.execute(form());
        var add = new AddTodoCommentForm(); add.setDate(today); add.setBody("残る");
        comment.execute(id, add);
        var form = new EditTodoForm(); form.setDetails("詳細");
        edit.execute(id, form);
        assertEquals("詳細", jdbc.queryForObject("SELECT details FROM todo WHERE id = ?", String.class, id));
        var clearing = new EditTodoForm(); clearing.setDetails("残さない");
        var clear = new EditTodoForm.EditTodoClear(); clear.setDetails(true); clearing.setClear(clear);
        edit.execute(id, clearing);
        assertNull(jdbc.queryForObject("SELECT details FROM todo WHERE id = ?", String.class, id));
        assertEquals(List.of("残る"), bodies(id));
    }
}
