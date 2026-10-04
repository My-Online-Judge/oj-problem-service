package vn.thanhtuanle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.thanhtuanle.support.PostgresTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * problem-db is built by Flyway alone: V1 (the live schema) + V2 (statistics). The context starting at all
 * proves Hibernate's ddl-auto=validate accepts them for every entity.
 */
class ProblemSchemaTest extends PostgresTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayBuildsTheWholeSchemaAndSeedsNothing() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsExactly("1", "2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_problems", Integer.class)).isZero();
    }
}
