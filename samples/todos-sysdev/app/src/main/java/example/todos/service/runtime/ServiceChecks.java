package example.todos.service.runtime;

import example.todos.service.error.BusinessViolation;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** 生成した検査で使う値の演算。業務の条件は生成した Service に置く。 */
public final class ServiceChecks {
    private ServiceChecks() { }

    public static <I, R> R call(I input, Consumer<I> initialize, Function<I, R> invoke) {
        initialize.accept(input);
        return invoke.apply(input);
    }

    public static Boolean and(Boolean... values) {
        boolean unknown = false;
        for (var value : values) {
            if (Boolean.FALSE.equals(value)) return false;
            unknown |= value == null;
        }
        return unknown ? null : Boolean.TRUE;
    }

    public static Boolean or(Boolean... values) {
        boolean unknown = false;
        for (var value : values) {
            if (Boolean.TRUE.equals(value)) return true;
            unknown |= value == null;
        }
        return unknown ? null : Boolean.FALSE;
    }

    public static Boolean not(Boolean value) { return value == null ? null : !value; }

    public static Boolean equal(Object left, Object right) {
        if (left == null || right == null) return null;
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) return a.compareTo(b) == 0;
        return Objects.equals(left, right);
    }

    public static Boolean oneOf(Object value, Collection<?> choices) {
        if (value == null) return null;
        return choices.stream().anyMatch(choice -> Boolean.TRUE.equals(equal(value, choice)));
    }

    public static <T extends Comparable<? super T>> Boolean atLeast(T left, T right) {
        return left == null || right == null ? null : left.compareTo(right) >= 0;
    }

    public static boolean empty(Object value) {
        if (value == null) return true;
        if (value instanceof String text) return text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
        if (value instanceof Collection<?> items) return items.isEmpty();
        if (value instanceof Map<?, ?> items) return items.isEmpty();
        return false;
    }

    public static boolean zero(Long count) { return count != null && count == 0; }
    public static <T> T orDefault(T value, T fallback) { return value == null ? fallback : value; }
    public static boolean positive(Long count) { return count != null && count > 0; }
    public static String label(String value, Map<String, String> labels) { return value == null ? null : labels.getOrDefault(value, value); }

    public static BusinessViolation violation(String key, String field, Object id, Object state, Object left, Object right) {
        var values = new LinkedHashMap<String, Object>();
        values.put("id", id);
        values.put("state", state);
        values.put("left", left);
        values.put("right", right);
        return new BusinessViolation(key, field, values);
    }
}
