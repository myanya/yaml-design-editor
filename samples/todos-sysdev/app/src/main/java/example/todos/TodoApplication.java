package example.todos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@SpringBootApplication
public class TodoApplication {
    public static void main(String[] args) {
        SpringApplication.run(TodoApplication.class, args);
    }

    @Bean
    ApplicationRunner databaseSetup(DataSource source) {
        return args -> initializeDatabase(source);
    }

    static void initializeDatabase(DataSource source) throws SQLException {
        Set<String> expected = Set.of("category".toUpperCase(Locale.ROOT), "tag".toUpperCase(Locale.ROOT), "todo".toUpperCase(Locale.ROOT), "todo_task".toUpperCase(Locale.ROOT), "todo_tags".toUpperCase(Locale.ROOT), "todo_comments".toUpperCase(Locale.ROOT), "todo_history".toUpperCase(Locale.ROOT));
        Set<String> actual = new HashSet<>();
        try (var connection = source.getConnection();
             var tables = connection.getMetaData().getTables(null, connection.getSchema(), null, new String[] {"TABLE", "BASE TABLE"})) {
            while (tables.next()) actual.add(tables.getString("TABLE_NAME").toUpperCase(Locale.ROOT));
        }
        if (actual.isEmpty()) {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        } else if (!actual.containsAll(expected)) {
            var missing = new HashSet<>(expected);
            missing.removeAll(actual);
            throw new IllegalStateException("必要なテーブルがありません: " + missing + "。既存 DB を自動変更しません。設計に合う DB を指定してください。");
        }
    }
}
