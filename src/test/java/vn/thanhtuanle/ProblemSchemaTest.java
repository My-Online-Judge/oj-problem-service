package vn.thanhtuanle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.thanhtuanle.support.PostgresTest;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * problem-db is built by Flyway alone: V1 (the live schema) + V2 (statistics) + V3 (integrity) + V4 (statement text)
 * + V5 (the verdict a submission is counted under) + V6 (the test-case number high-water mark). The context starting at all
 * proves Hibernate's ddl-auto=validate accepts them for every entity.
 */
class ProblemSchemaTest extends PostgresTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void flywayBuildsTheWholeSchemaAndSeedsNothing() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_problems", Integer.class)).isZero();
    }

    /** V6 reads the numbers out of the stored paths (<code>slug/N.in</code>), ignoring names that are not numbers. */
    @Test
    void v6BackfillsTheHighWaterMark() throws Exception {
        String db = "backfill_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        }
        String url = PostgresTest.POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
        Flyway at5 = Flyway.configure().dataSource(url, PostgresTest.POSTGRES.getUsername(), PostgresTest.POSTGRES.getPassword())
                .target("5").load();
        at5.migrate();

        JdbcTemplate other = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, PostgresTest.POSTGRES.getUsername(), PostgresTest.POSTGRES.getPassword()));
        UUID problem = UUID.randomUUID();
        other.update("""
                INSERT INTO t_problems (id, hardness_level, memory_limit, status, time_limit, created_at, updated_at, title, problem_slug)
                VALUES (?, 1, 64, 1, 1000, now(), now(), 'p', 'p')""", problem);
        for (String in : new String[] {"p/1.in", "p/7.in", "p/x.in"}) {
            other.update("""
                    INSERT INTO t_test_cases (id, problem_id, created_at, updated_at, input, output)
                    VALUES (?, ?, now(), now(), ?, ?)""", UUID.randomUUID(), problem, in, in.replace(".in", ".out"));
        }
        UUID empty = UUID.randomUUID();
        other.update("""
                INSERT INTO t_problems (id, hardness_level, memory_limit, status, time_limit, created_at, updated_at, title, problem_slug)
                VALUES (?, 1, 64, 1, 1000, now(), now(), 'q', 'q')""", empty);

        Flyway.configure().dataSource(url, PostgresTest.POSTGRES.getUsername(), PostgresTest.POSTGRES.getPassword()).load().migrate();

        assertThat(other.queryForObject("SELECT test_case_high_water FROM t_problems WHERE id = ?", Integer.class, problem))
                .isEqualTo(7);
        assertThat(other.queryForObject("SELECT test_case_high_water FROM t_problems WHERE id = ?", Integer.class, empty))
                .isZero();
    }
}
