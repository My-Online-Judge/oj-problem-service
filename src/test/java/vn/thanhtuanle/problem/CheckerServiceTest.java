package vn.thanhtuanle.problem;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.problem.dto.CheckerResponse;
import vn.thanhtuanle.support.PostgresTest;
import vn.thanhtuanle.testcase.BundleFile;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A problem's checker on Postgres: upload, read and removal, with test-case files in memory and the real bundle
 * publisher over a mocked bundle store. Every change commits, so the problems ({@code chk-…}) are deleted after each
 * test: PostgresTest shares its database with ProblemSchemaTest, which expects zero problems.
 */
class CheckerServiceTest extends PostgresTest {

    @TestConfiguration
    static class Sources {
        @Bean
        @Primary
        ProbedSources probedSources() {
            return new ProbedSources();
        }
    }

    /** In-memory files that run {@link #beforeRead} on every read. */
    static class ProbedSources extends InMemoryTestCaseSourceStore {
        volatile Runnable beforeRead = () -> { };

        @Override
        public Optional<byte[]> get(String path) {
            beforeRead.run();
            return super.get(path);
        }
    }

    private static final String CHECKER = """
            #include <fstream>
            int main(int argc, char** argv) {
                std::ifstream in(argv[1]), out(argv[2]);
                long long a, b, sum;
                in >> a >> b;
                return (out >> sum) && sum == a + b ? 0 : 1;
            }
            """;

    @Autowired CheckerService checkers;
    @Autowired ProblemService problemService;
    @Autowired ProblemRepository problems;
    @Autowired TestCaseBundlePublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProbedSources sources;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager tm;
    @MockitoBean TestCaseBundleStore bundleStore;

    private String slug;

    @BeforeEach
    void aProblemWithOneTestCase() {
        slug = "chk-" + UUID.randomUUID();
        Problem problem = Problem.builder().title("t").problemSlug(slug).status(ProblemStatus.ACTIVE.getValue())
                .timeLimit(1000).memoryLimit(64L).hardnessLevel(1).testCases(new ArrayList<>()).build();
        problem.getTestCases().add(TestCase.builder().input(slug + "/1.in").output(slug + "/1.out").problem(problem)
                .build());
        sources.put(slug + "/1.in", utf8("1 2\n"));
        sources.put(slug + "/1.out", utf8("3\n"));
        problems.saveAndFlush(problem);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("""
                DELETE FROM t_test_cases WHERE problem_id IN (SELECT id FROM t_problems WHERE problem_slug LIKE 'chk-%')""");
        jdbc.update("DELETE FROM t_problems WHERE problem_slug LIKE 'chk-%'");
        sources.files.clear();
        sources.beforeRead = () -> { };
        Mockito.reset(bundleStore);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "spj.cpp", "text/x-c++src", content);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT spj, spj_version FROM t_problems WHERE problem_slug = ?", slug);
    }

    /** The files of the last bundle published for the problem. */
    private List<BundleFile> lastPublished() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BundleFile>> files = ArgumentCaptor.forClass(List.class);
        verify(bundleStore, atLeastOnce()).publish(eq(slug), files.capture());
        return files.getValue();
    }

    private static String info(List<BundleFile> files) {
        return files.stream().filter(f -> f.name().equals("info")).findFirst()
                .map(f -> new String(f.content(), StandardCharsets.UTF_8)).orElseThrow();
    }

    /** The hash of the problem's bundle as it stands, which is what its last publish uploaded. */
    private String bundleHash() {
        return new TransactionTemplate(tm).execute(tx ->
                publisher.bundleHash(problems.findBySlugForUpdate(slug).orElseThrow()));
    }

    @Test
    void uploadSetsTheVersionAndRepublishes() throws Exception {
        byte[] source = utf8(CHECKER);
        String version = sha256(source);

        CheckerResponse response = checkers.upload(slug, file(source));

        assertThat(version).hasSize(64);
        assertThat(response).isEqualTo(new CheckerResponse(true, version, null));
        assertThat(row()).containsEntry("spj", true).containsEntry("spj_version", version);
        assertThat(sources.files.get(slug + "/spj.cpp")).isEqualTo(source);
        assertThat(info(lastPublished())).contains("\"spj\" : true").contains("\"spj_version\" : \"" + version + "\"");
        assertThat(problemService.getProblemBySlug(slug, true).isSpj()).as("the problem's public spj flag").isTrue();
    }

    @Test
    void uploadOver64KiBIs400() throws Exception {
        byte[] tooBig = new byte[65_537];
        Arrays.fill(tooBig, (byte) ' ');

        assertThatThrownBy(() -> checkers.upload(slug, file(tooBig)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A checker is at most 64 KiB");
        assertThat(row()).containsEntry("spj", false).containsEntry("spj_version", null);
        assertThat(sources.files).doesNotContainKey(slug + "/spj.cpp");
        verify(bundleStore, never()).publish(anyString(), anyList());

        byte[] atTheLimit = Arrays.copyOf(tooBig, 65_536);
        assertThat(checkers.upload(slug, file(atTheLimit)).spj()).as("65 536 bytes is allowed").isTrue();
    }

    @Test
    void uploadNotUtf8Is400() {
        byte[] latin1 = "// café\nint main() { return 0; }\n".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> checkers.upload(slug, file(latin1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A checker must be UTF-8 text");
        assertThat(row()).containsEntry("spj", false);
        assertThat(sources.files).doesNotContainKey(slug + "/spj.cpp");
        verify(bundleStore, never()).publish(anyString(), anyList());
    }

    @Test
    void anEmptyUploadIs400() {
        assertThatThrownBy(() -> checkers.upload(slug, file(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Checker file cannot be empty");
        verify(bundleStore, never()).publish(anyString(), anyList());
    }

    @Test
    void removeClearsAndRepublishes() {
        checkers.upload(slug, file(utf8(CHECKER)));
        clearInvocations(bundleStore);

        checkers.remove(slug);

        assertThat(row()).containsEntry("spj", false).containsEntry("spj_version", null);
        assertThat(info(lastPublished())).contains("\"spj\" : false").doesNotContain("spj_version");
        assertThat(sources.files).as("the source is deleted after the commit").doesNotContainKey(slug + "/spj.cpp");
    }

    @Test
    void removeWithoutACheckerIs404() {
        assertThatThrownBy(() -> checkers.remove(slug)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> checkers.remove("chk-no-such-problem")).isInstanceOf(ResourceNotFoundException.class);
        verify(bundleStore, never()).publish(anyString(), anyList());
    }

    /** The row is read first and the file afterwards, so a slow MinIO never holds a database connection. */
    @Test
    void getReturnsTheSource() throws Exception {
        assertThat(checkers.get(slug)).isEqualTo(new CheckerResponse(false, null, null));
        checkers.upload(slug, file(utf8(CHECKER)));
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        List<Integer> activeWhileReading = new CopyOnWriteArrayList<>();
        sources.beforeRead = () -> activeWhileReading.add(pool.getActiveConnections());

        CheckerResponse response = checkers.get(slug);

        assertThat(response).isEqualTo(new CheckerResponse(true, sha256(utf8(CHECKER)), CHECKER));
        assertThat(activeWhileReading).hasSize(1).containsOnly(0);
        assertThatThrownBy(() -> checkers.get("chk-no-such-problem")).isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Bundles installed before SP13 stay 0700 on the workers, where qduoj's spj user cannot read them (Task 16), so
     * adding, changing and removing a checker must each give the problem a new bundle, which every worker extracts
     * anew.
     */
    @Test
    void addingChangingAndRemovingACheckerEachChangeTheBundleHash() {
        String plain = bundleHash();

        checkers.upload(slug, file(utf8(CHECKER)));
        String added = bundleHash();
        checkers.upload(slug, file(utf8(CHECKER + "// v2\n")));
        String changed = bundleHash();
        checkers.remove(slug);
        String removed = bundleHash();

        verify(bundleStore, times(3)).publish(eq(slug), anyList());
        assertThat(List.of(plain, added, changed)).doesNotHaveDuplicates();
        assertThat(removed).isNotEqualTo(changed).isEqualTo(plain);
    }

    /** Like a test-case change, an upload takes the problem row lock, so it waits for another holder. */
    @Test
    void uploadWaitsWhileAnotherTransactionHoldsTheProblemRow() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(tm).executeWithoutResult(tx -> {
                problems.findBySlugForUpdate(slug).orElseThrow();
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<CheckerResponse> upload = pool.submit(() -> checkers.upload(slug, file(utf8(CHECKER))));
            assertThatThrownBy(() -> upload.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(upload.get(10, TimeUnit.SECONDS).spj()).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
