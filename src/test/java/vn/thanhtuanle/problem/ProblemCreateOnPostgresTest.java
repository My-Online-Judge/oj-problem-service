package vn.thanhtuanle.problem;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.problem.dto.CreateProblemDto;
import vn.thanhtuanle.support.PostgresTest;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * createProblem end to end on Postgres: the facts claimSlug rests on — Postgres names the unique index, Hibernate hands
 * that name to the ConstraintViolationException, and the problem saved first still cascades its test cases — and the
 * race itself, which the mocked ProblemSlugRaceTest can only describe. A taken slug answers like the existsByProblemSlug
 * check does: ResourceAlreadyExistException (400 through oj-common's handler).
 */
class ProblemCreateOnPostgresTest extends PostgresTest {

    @TestConfiguration
    static class Sources {
        @Bean
        @Primary
        InMemoryTestCaseSourceStore inMemorySources() {
            return new InMemoryTestCaseSourceStore();
        }
    }

    @Autowired ProblemService service;
    @Autowired ProblemRepository repo;
    @Autowired JdbcTemplate jdbc;
    @Autowired InMemoryTestCaseSourceStore sources;
    @Autowired PlatformTransactionManager tm;
    @MockitoBean TestCaseBundlePublisher publisher;
    @MockitoBean TestCaseBundleStore bundleStore;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM t_test_cases WHERE problem_id IN (SELECT id FROM t_problems WHERE problem_slug LIKE 'create-pg-%')");
        jdbc.update("DELETE FROM t_problem_tags WHERE problem_id IN (SELECT id FROM t_problems WHERE problem_slug LIKE 'create-pg-%')");
        jdbc.update("DELETE FROM t_problems WHERE problem_slug LIKE 'create-pg-%'");
    }

    private static CreateProblemDto dto(String slug) {
        return CreateProblemDto.builder().title("t").subject("s").description("d").timeLimit(1000).memoryLimit(64)
                .hardnessLevel(1).problemSlug(slug).inputDescription("i").outputDescription("o")
                .sampleInput("1 2").sampleOutput("3").status(ProblemStatus.ACTIVE).build();
    }

    private static MockMultipartFile zip(String... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (int i = 0; i < entries.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entries[i]));
                zos.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", "cases.zip", "application/zip", bytes.toByteArray());
    }

    @Test
    void createPersistsTheProblemItsTestCasesAndPublishesAfterClaimingTheSlug() throws Exception {
        String slug = "create-pg-" + UUID.randomUUID();

        service.createProblem(dto(slug), zip("1.in", "1 2\n", "1.out", "3\n", "2.in", "5 5\n", "2.out", "10\n"));

        UUID id = jdbc.queryForObject("SELECT id FROM t_problems WHERE problem_slug = ?", UUID.class, slug);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_test_cases WHERE problem_id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_problems WHERE problem_slug = ? AND created_at IS NOT NULL AND updated_at IS NOT NULL",
                Integer.class, slug)).isEqualTo(1);
        assertThat(sources.get(slug + "/1.in")).isPresent();
        assertThat(sources.get(slug + "/2.out")).isPresent();
        verify(publisher).publish(argThat(p -> p.getId() != null && p.getTestCases().size() == 2
                && p.getTestCases().get(0).getInput().equals(slug + "/1.in")));
    }

    @Test
    void hibernateNamesTheSlugIndexOnADuplicate() {
        String slug = "create-pg-" + UUID.randomUUID();
        repo.saveAndFlush(problem(slug));

        Throwable thrown = catchThrowable(() -> repo.saveAndFlush(problem(slug)));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(thrown.getCause()).isInstanceOf(ConstraintViolationException.class);
        assertThat(((ConstraintViolationException) thrown.getCause()).getConstraintName()).isEqualTo("ux_problems_slug");
    }

    @Test
    void aNullSlugIsNotReportedAsATakenSlug() {
        Throwable thrown = catchThrowable(() -> repo.saveAndFlush(problem(null)));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(((ConstraintViolationException) thrown.getCause()).getConstraintName()).isNotEqualTo("ux_problems_slug");
    }

    /** The race itself: a competing create has inserted the slug but not committed when ours passes existsByProblemSlug. */
    @Test
    void theLoserOfAConcurrentCreateIsRefusedAndWritesNoFile() throws Exception {
        String slug = "create-pg-" + UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        Thread winner = new Thread(() -> new TransactionTemplate(tm).executeWithoutResult(tx -> {
            jdbc.update("""
                    INSERT INTO t_problems (id, hardness_level, memory_limit, status, time_limit, created_at, updated_at, title, problem_slug)
                    VALUES (?, 1, 64, 1, 1000, now(), now(), 'winner', ?)""", UUID.randomUUID(), slug);
            inserted.countDown();
            try {
                Thread.sleep(2000); // the loser must block on the unique index meanwhile
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        winner.start();
        assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.createProblem(dto(slug), zip("1.in", "1\n", "1.out", "1\n")))
                .isInstanceOf(ResourceAlreadyExistException.class)
                .hasMessageContaining(slug);
        winner.join();

        assertThat(System.nanoTime() - started).as("blocked until the winner committed").isGreaterThan(TimeUnit.SECONDS.toNanos(1));
        assertThat(sources.get(slug + "/1.in")).isEmpty();
        verifyNoInteractions(publisher);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_problems WHERE problem_slug = ?", Integer.class, slug)).isEqualTo(1);
    }

    private static Problem problem(String slug) {
        return Problem.builder().title("t").problemSlug(slug).status(ProblemStatus.ACTIVE.getValue())
                .timeLimit(1000).memoryLimit(64L).hardnessLevel(1).build();
    }
}
