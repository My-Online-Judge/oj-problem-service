package vn.thanhtuanle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.support.PostgresTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V3: the slug is required and unique — deleted problems included — and the problem foreign keys are indexed. */
@Transactional // each test rolls back: PostgresTest shares one database with ProblemSchemaTest, which expects no rows
class ProblemIntegrityMigrationTest extends PostgresTest {

    @Autowired JdbcTemplate jdbc;

    private void insertProblem(String slug, ProblemStatus status) {
        jdbc.update("""
                INSERT INTO t_problems (id, hardness_level, memory_limit, status, time_limit, created_at, updated_at, title, problem_slug)
                VALUES (?, 1, 64, ?, 1000, now(), now(), 'integrity', ?)""", UUID.randomUUID(), status.getValue(), slug);
    }

    @Test
    void aSlugCanBeTakenOnlyOnce() {
        String slug = "integrity-" + UUID.randomUUID();
        insertProblem(slug, ProblemStatus.ACTIVE);
        assertThatThrownBy(() -> insertProblem(slug, ProblemStatus.ACTIVE))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("ux_problems_slug");
    }

    @Test
    void aDeletedProblemKeepsItsSlug() {
        String slug = "integrity-" + UUID.randomUUID();
        insertProblem(slug, ProblemStatus.DELETED);
        assertThatThrownBy(() -> insertProblem(slug, ProblemStatus.ACTIVE)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aProblemWithoutASlugIsRejected() {
        assertThatThrownBy(() -> insertProblem(null, ProblemStatus.ACTIVE)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theForeignKeysOfTagsAndTestCasesAreIndexed() {
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class))
                .contains("ux_problems_slug", "ix_problem_tags_problem_id", "ix_test_cases_problem_id");
    }
}
