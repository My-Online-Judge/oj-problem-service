package vn.thanhtuanle.problem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.problem.dto.TestCaseResponse;
import vn.thanhtuanle.problem.dto.TestCaseSummary;
import vn.thanhtuanle.support.PostgresTest;
import vn.thanhtuanle.testcase.BundleFile;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/** Test-case list previews, read one and update, on Postgres; the add/update race needs the problem row lock. */
class TestCaseUpdateTest extends PostgresTest {

    @TestConfiguration
    static class Sources {
        @Bean
        @Primary
        InMemoryTestCaseSourceStore inMemorySources() {
            return new InMemoryTestCaseSourceStore();
        }
    }

    @Autowired TestCaseService service;
    @Autowired ProblemRepository repo;
    @Autowired JdbcTemplate jdbc;
    @Autowired InMemoryTestCaseSourceStore sources;
    @MockitoBean TestCaseBundleStore bundleStore;

    private String slug;
    private UUID firstId;

    @BeforeEach
    void setUp() {
        slug = "tc-upd-" + UUID.randomUUID();
        Problem problem = Problem.builder().title("t").problemSlug(slug).timeLimit(1000).memoryLimit(64L)
                .hardnessLevel(1).testCases(new ArrayList<>()).build();
        TestCase first = TestCase.builder().input(slug + "/1.in").output(slug + "/1.out").sample(true)
                .problem(problem).build();
        problem.getTestCases().add(first);
        sources.put(slug + "/1.in", "1 2\n".getBytes(StandardCharsets.UTF_8));
        sources.put(slug + "/1.out", "3\n".getBytes(StandardCharsets.UTF_8));
        firstId = repo.saveAndFlush(problem).getTestCases().get(0).getId();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM t_test_cases WHERE problem_id IN (SELECT id FROM t_problems WHERE problem_slug LIKE 'tc-upd-%')");
        jdbc.update("DELETE FROM t_problems WHERE problem_slug LIKE 'tc-upd-%'");
        Mockito.reset(bundleStore);
    }

    private static MockMultipartFile part(String name, String content) {
        return new MockMultipartFile(name, name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listReturnsSummariesWithoutContent() {
        String big = ("é".repeat(100) + "\n").repeat(10); // 1010 chars, 2010 bytes
        sources.put(slug + "/1.in", big.getBytes(StandardCharsets.UTF_8));

        List<TestCaseSummary> list = service.listTestCases(slug);

        assertThat(list).hasSize(1);
        TestCaseSummary s = list.get(0);
        assertThat(s.id()).isEqualTo(firstId);
        assertThat(s.name()).isEqualTo("1");
        assertThat(s.sample()).isTrue();
        assertThat(s.inputSize()).isEqualTo(big.getBytes(StandardCharsets.UTF_8).length);
        assertThat(s.outputSize()).isEqualTo(2);
        assertThat(s.inputPreview().length()).isLessThanOrEqualTo(256);
        assertThat(big).startsWith(s.inputPreview());
        assertThat(s.outputPreview()).isEqualTo("3\n");
    }

    @Test
    void getOneReturnsFullContent() {
        TestCaseResponse r = service.getTestCase(slug, firstId);

        assertThat(r.getInput()).isEqualTo("1 2\n");
        assertThat(r.getOutput()).isEqualTo("3\n");
        assertThat(r.getName()).isEqualTo("1");
        assertThat(r.isSample()).isTrue();
    }

    @Test
    void updateRewritesBothFilesAndKeepsSample() throws Exception {
        TestCaseSummary s = service.updateTestCase(slug, firstId, part("input", "10 20\n"), part("output", "30\n"));

        assertThat(new String(sources.files.get(slug + "/1.in"), StandardCharsets.UTF_8)).isEqualTo("10 20\n");
        assertThat(new String(sources.files.get(slug + "/1.out"), StandardCharsets.UTF_8)).isEqualTo("30\n");
        assertThat(s.sample()).isTrue();
        assertThat(s.name()).isEqualTo("1");
        assertThat(s.inputSize()).isEqualTo(6);
        verify(bundleStore).publish(eq(slug), any());
    }

    @Test
    void updateUnknownIdIs404() {
        assertThatThrownBy(() -> service.updateTestCase(slug, UUID.randomUUID(), part("input", "a"), part("output", "b")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getTestCase(slug, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void concurrentAddAndUpdate() throws Exception {
        for (int round = 0; round < 5; round++) {
            Mockito.reset(bundleStore);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            String updated = "updated " + round + "\n";
            Future<?> add = pool.submit(() -> {
                go.await();
                return service.addTestCase(slug, part("input", "x\n"), part("output", "y\n"));
            });
            Future<?> update = pool.submit(() -> {
                go.await();
                return service.updateTestCase(slug, firstId, part("input", updated), part("output", "o\n"));
            });
            go.countDown();
            add.get(30, TimeUnit.SECONDS);
            update.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            Problem problem = repo.findLiveBySlug(slug).orElseThrow();
            List<String> names = jdbc.queryForList(
                    "SELECT input FROM t_test_cases WHERE problem_id = ? ORDER BY input", String.class, problem.getId());
            assertThat(names).doesNotHaveDuplicates();
            int rows = names.size();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BundleFile>> files = ArgumentCaptor.forClass(List.class);
            verify(bundleStore, atLeastOnce()).publish(eq(slug), files.capture());
            List<BundleFile> last = files.getValue();
            // the last publish saw every committed row and the updated bytes of test 1
            assertThat(last.stream().filter(f -> f.name().endsWith(".in")).count()).isEqualTo(rows);
            BundleFile one = last.stream().filter(f -> f.name().equals("1.in")).findFirst().orElseThrow();
            assertThat(new String(one.content(), StandardCharsets.UTF_8)).isEqualTo(updated);
        }
    }
}
