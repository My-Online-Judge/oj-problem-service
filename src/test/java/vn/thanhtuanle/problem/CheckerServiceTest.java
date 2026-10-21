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
import vn.thanhtuanle.problem.dto.UpdateProblemDto;
import vn.thanhtuanle.support.PostgresTest;
import vn.thanhtuanle.testcase.BundleFile;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundleException;
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
import static org.mockito.Mockito.when;

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

    /** Where a checker of this version is stored: {@code <slug>/spj-<sha256 hex>.cpp}. */
    private String key(String version) {
        return slug + "/spj-" + version + ".cpp";
    }

    /** Every stored key of the problem that holds a checker source, versioned or legacy. */
    private List<String> checkerKeys() {
        return sources.files.keySet().stream().filter(k -> k.startsWith(slug + "/spj")).sorted().toList();
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
        assertThat(sources.files.get(key(version))).isEqualTo(source);
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
        assertThat(checkerKeys()).isEmpty();
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
        assertThat(checkerKeys()).isEmpty();
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
    void removeClearsAndRepublishes() throws Exception {
        checkers.upload(slug, file(utf8(CHECKER)));
        clearInvocations(bundleStore);

        checkers.remove(slug);

        assertThat(row()).containsEntry("spj", false).containsEntry("spj_version", null);
        assertThat(info(lastPublished())).contains("\"spj\" : false").doesNotContain("spj_version");
        assertThat(sources.files).as("a leftover source is inert while spj is false, so it is kept")
                .containsKey(key(sha256(utf8(CHECKER))));
    }

    @Test
    void anUploadStoresTheSourceUnderItsVersion() throws Exception {
        byte[] source = utf8(CHECKER);

        checkers.upload(slug, file(source));

        assertThat(checkerKeys()).containsExactly(key(sha256(source)));
        assertThat(sources.files.get(key(sha256(source)))).isEqualTo(source);
        assertThat(sources.files).as("no legacy key is written").doesNotContainKey(slug + "/spj.cpp");
    }

    /**
     * A replacement whose publish fails rolls the row back to v1, and v1's source is still under v1's key: readers get
     * the source their row names, never v2's source under v1's version.
     */
    @Test
    void aFailedReplacementKeepsThePreviousSourceReadable() throws Exception {
        byte[] v1 = utf8(CHECKER);
        byte[] v2 = utf8(CHECKER + "// v2\n");
        checkers.upload(slug, file(v1));
        when(bundleStore.publish(eq(slug), anyList())).thenThrow(new TestCaseBundleException("MinIO is down"));

        assertThatThrownBy(() -> checkers.upload(slug, file(v2))).isInstanceOf(TestCaseBundleException.class);

        assertThat(row()).containsEntry("spj", true).containsEntry("spj_version", sha256(v1));
        assertThat(checkers.get(slug)).isEqualTo(new CheckerResponse(true, sha256(v1), CHECKER));
        assertThat(sources.files.get(key(sha256(v1)))).isEqualTo(v1);
    }

    /** A remove keeps every key, so an upload right after it can never lose its source to a late delete. */
    @Test
    void removeThenUploadKeepsTheNewSource() throws Exception {
        byte[] v1 = utf8(CHECKER);
        byte[] v2 = utf8(CHECKER + "// v2\n");
        checkers.upload(slug, file(v1));

        checkers.remove(slug);
        checkers.upload(slug, file(v2));

        assertThat(checkers.get(slug)).isEqualTo(new CheckerResponse(true, sha256(v2), CHECKER + "// v2\n"));
        assertThat(checkerKeys()).as("v1 stays as garbage, by design")
                .containsExactlyInAnyOrder(key(sha256(v1)), key(sha256(v2)));
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

    /**
     * A problem edit takes the same row lock, so one that runs during a checker or test-case change cannot write back
     * the stale spj, spj_version and test_case_high_water it read before that change committed.
     */
    @Test
    void anEditDuringACheckerChangeDoesNotWriteStaleCheckerState() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(tm).executeWithoutResult(tx -> {
                Problem held = problems.findBySlugForUpdate(slug).orElseThrow();
                held.setSpj(true);
                held.setSpjVersion("v-from-upload");
                held.setTestCaseHighWater(7);
                problems.save(held);
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> edit = pool.submit(() -> problemService.updateProblem(slug,
                    UpdateProblemDto.builder().title("renamed").subject("s").timeLimit(1000).memoryLimit(64)
                            .hardnessLevel(1).build()));
            assertThatThrownBy(() -> edit.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            edit.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForMap(
                    "SELECT title, spj, spj_version, test_case_high_water FROM t_problems WHERE problem_slug = ?", slug))
                    .containsEntry("title", "renamed").containsEntry("spj", true)
                    .containsEntry("spj_version", "v-from-upload").containsEntry("test_case_high_water", 7);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
