package {#each config.package}{.}{#if more}.{/if}{/each};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.ibatis.session.SqlSessionFactory;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@SpringBootTest(properties = {{
    "spring.datasource.url=jdbc:h2:mem:bootstrap;DB_CLOSE_DELAY=-1",
    "server.port=0"
}})
class {config.applicationClass}Test {{
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSessionFactory sessions;
    @Autowired DataSource source;

    @Test
    void contextAndDatabaseStart() {{
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
{#each models}        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM {table}", Long.class)).isZero();
{/each}
{#each mappers}        assertThat(sessions.getConfiguration().hasMapper({#each config.package}{.}.{/each}data.mapper.{name}.class)).isTrue();
{/each}        assertThat(sessions.getConfiguration().getMappedStatementNames()).isNotEmpty();
    }}

    @Test
    void initializationRunsOnlyForEmptyDatabase() throws Exception {{
        {config.applicationClass}.initializeDatabase(source);
        var partial = new DriverManagerDataSource("jdbc:h2:mem:partial-schema;DB_CLOSE_DELAY=-1", "sa", "");
        var incomplete = new JdbcTemplate(partial);
        incomplete.execute("CREATE TABLE retained (id INTEGER PRIMARY KEY)");
        incomplete.update("INSERT INTO retained VALUES (42)");
        assertThatThrownBy(() -> {config.applicationClass}.initializeDatabase(partial)).isInstanceOf(IllegalStateException.class);
        assertThat(incomplete.queryForObject("SELECT id FROM retained", Integer.class)).isEqualTo(42);
    }}
}}
