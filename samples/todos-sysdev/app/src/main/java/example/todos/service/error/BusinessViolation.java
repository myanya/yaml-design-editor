package example.todos.service.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 確認画面と実行時で共有する、業務の違反。 */
public record BusinessViolation(java.lang.String key, java.lang.String field, Map<java.lang.String, java.lang.Object> values) {
    public BusinessViolation {
        Objects.requireNonNull(key, "メッセージのキーが必要です");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values)));
    }
}
