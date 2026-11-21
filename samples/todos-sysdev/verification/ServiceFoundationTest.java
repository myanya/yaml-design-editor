package verification;

import example.todos.service.error.BusinessViolation;
import example.todos.service.error.NotFoundException;
import example.todos.service.error.RefusedException;
import example.todos.service.state.TodoStatus;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServiceFoundationTest {
    @Test
    void stateTransitionsFollowTheFinalDesign() {
        var state = TodoStatus.next("register-todo", null);
        assertEquals(TodoStatus.OPEN, state);
        assertEquals("未着手", state.getLabel());
        assertTrue(state.isTodoActive());
        assertFalse(state.allows("complete-todo"));
        assertThrows(IllegalArgumentException.class, () -> TodoStatus.next("complete-todo", TodoStatus.OPEN));
        state = TodoStatus.next("start-todo", state);
        state = TodoStatus.next("suspend-todo", state);
        state = TodoStatus.next("resume-todo", state);
        state = TodoStatus.next("complete-todo", state);
        assertEquals(TodoStatus.CLOSED, state);
        assertTrue(state.isTerminal());
        assertFalse(state.isTodoActive());
        for (var active : new TodoStatus[]{TodoStatus.OPEN, TodoStatus.IN_PROGRESS, TodoStatus.SUSPENDED}) {
            assertEquals(TodoStatus.CANCELED, TodoStatus.next("cancel-todo", active));
        }
        assertTrue(TodoStatus.CANCELED.isTerminal());
        assertFalse(state.allows("unknown"));
        assertThrows(IllegalArgumentException.class, () -> TodoStatus.next("register-todo", TodoStatus.OPEN));
    }

    @Test
    void refusalsKeepFieldAndMessageValuesIncludingNull() {
        var values = new LinkedHashMap<String, Object>();
        values.put("left", LocalDate.of(2026, 1, 1));
        values.put("right", null);
        var violation = new BusinessViolation("check.date", "due", values);
        values.clear();
        assertEquals(2, violation.values().size());
        assertTrue(violation.values().containsKey("right"));
        assertThrows(UnsupportedOperationException.class, () -> violation.values().clear());
        var refused = new RefusedException(violation);
        assertEquals("check.date", refused.getMessage());
        assertSame(violation, refused.getViolation());
        var missing = new NotFoundException("todo", "TODO-999");
        assertEquals("error.notFound.todo", missing.getMessage());
        assertEquals("todo", missing.getEntity());
        assertEquals("TODO-999", missing.getId());
    }
}
