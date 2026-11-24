package verification;

import example.todos.TodoApplication;
import example.todos.web.form.*;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 生成した Form と Thymeleaf を実際の HTTP で確認する。業務更新を伴わないプレビュー。 */
@SpringBootTest(classes = TodoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:web-design-verification;DB_CLOSE_DELAY=-1"
})
class WebDesignIntegrationTest {
    @LocalServerPort int port;
    @Autowired Validator validator;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newHttpClient();

    String get(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }
    String post(String title, String summary, String due) throws Exception {
        String data = "title=" + encode(title) + "&summary=" + encode(summary) + "&due=" + encode(due);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/design-preview"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(data)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @Test void confirmedThemeAndTemplatesAreServed() throws Exception {
        String html = get("/design-preview");
        assertTrue(html.contains("NEON OPS") && html.contains("デザイン確認"));
        assertTrue(html.contains("/css/theme.css") && html.contains("<form"));
        assertFalse(html.contains("th:field") || html.contains("{previewForm"));
        String css = get("/css/theme.css");
        assertTrue(css.contains("--primary: #63F5EC") && css.contains("prefers-reduced-motion"));
        assertTrue(css.contains(":focus-visible") && css.contains("max-width: 760px"));
        assertFalse(css.contains("@apply") || css.contains("{record."));
    }

    @Test void httpValidationUsesConfirmedJapaneseMessagesAndNeverWrites() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM todo", Long.class);
        String invalid = post("長".repeat(101), "", "");
        assertTrue(invalid.contains("タイトルは100文字以内で入力してください"));
        assertTrue(invalid.contains("概要を入力してください"));
        assertTrue(invalid.contains("aria-invalid=\"true\""));
        assertTrue(post("", "概要", "").contains("タイトルを入力してください"));
        assertTrue(post("タイトル", "概要", "invalid").contains("期限は年月日で入力してください"));
        assertTrue(post("タイトル", "概要", "2026-11-21").contains("入力の形式を確認しました"));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM todo", Long.class));
    }

    @Test void inputIsEscapedAndWhitespaceIsPreserved() throws Exception {
        String html = post("<script>alert(1)</script>", "概要", "");
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        var form = new RegisterTodoForm();
        form.setTitle("  タイトル  "); form.setSummary("概要");
        assertEquals("  タイトル  ", form.getTitle());
        assertTrue(validator.validate(form).isEmpty());
        form.setTitle("");
        assertNull(form.getTitle());
        assertTrue(validator.validate(form).stream().anyMatch(v -> v.getPropertyPath().toString().equals("title")));
    }

    @Test void lengthLineBreakAndDuplicateConstraintsAreActive() {
        var form = new RegisterTodoForm();
        form.setTitle("字".repeat(100)); form.setSummary("概要");
        assertTrue(validator.validate(form).isEmpty());
        form.setTitle("字".repeat(101));
        assertTrue(validator.validate(form).stream().anyMatch(v -> v.getPropertyPath().toString().equals("title")));
        form.setTitle("一行目\n二行目");
        assertTrue(validator.validate(form).stream().anyMatch(v -> v.getMessage().contains("書き方")));
        form.setTitle("タイトル"); form.setTags(List.of("TAG-001", "TAG-001"));
        assertTrue(validator.validate(form).stream().anyMatch(v -> v.getPropertyPath().toString().equals("tags")));
    }

    @Test void commentRowFormsRequireTheRowNumberAndAllFormsHaveMetadata() {
        var form = new EditTodoCommentForm();
        assertTrue(validator.validate(form).stream().anyMatch(v -> v.getPropertyPath().toString().equals("row")));
        form.setRow(1L); form.setDate(LocalDate.of(2026, 11, 21)); form.setBody("確認する");
        assertTrue(validator.validate(form).isEmpty());
        assertTrue(validator.validate(new DeleteTodoCommentForm()).stream().anyMatch(v -> v.getPropertyPath().toString().equals("row")));
        for (var type : List.of(AddCategoryForm.class, AddTagForm.class, AddTodoCommentForm.class, AddTodoTaskForm.class,
                CancelTodoForm.class, CompleteTodoForm.class, DeleteTodoCommentForm.class, EditTodoCommentForm.class,
                EditTodoForm.class, RegisterTodoForm.class, ResumeTodoForm.class, SuspendTodoForm.class)) {
            assertNotNull(validator.getConstraintsForClass(type));
        }
    }
}
