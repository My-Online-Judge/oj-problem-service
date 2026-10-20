package vn.thanhtuanle.problem.packaging;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.thanhtuanle.support.PostgresTest;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What the package tests share, so that they share one Spring context, and with it one more connection pool on the
 * shared Postgres container instead of two: test-case files in memory, the real bundle publisher over a mocked bundle
 * store, and MockMvc. Imports commit, so every slug starts with {@value #PREFIX} and is deleted after each test:
 * PostgresTest shares its database with ProblemSchemaTest, which expects zero problems.
 */
@AutoConfigureMockMvc
@Import(PackagingPostgresTest.Sources.class)
abstract class PackagingPostgresTest extends PostgresTest {

    static final String PREFIX = "pkg-";

    @TestConfiguration
    static class Sources {
        @Bean
        @Primary
        ProbedSources probedSources() {
            return new ProbedSources();
        }
    }

    /** In-memory test-case files that run {@link #beforeRead} on every read. */
    static class ProbedSources extends InMemoryTestCaseSourceStore {
        volatile Runnable beforeRead = () -> { };

        @Override
        public Optional<byte[]> get(String path) {
            beforeRead.run();
            return super.get(path);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProbedSources sources;
    @Autowired ProblemPackageService packages;
    @MockitoBean TestCaseBundleStore bundleStore;

    @AfterEach
    void deletePackageProblems() {
        String ours = "SELECT id FROM t_problems WHERE problem_slug LIKE '" + PREFIX + "%'";
        jdbc.update("DELETE FROM t_test_cases WHERE problem_id IN (" + ours + ")");
        jdbc.update("DELETE FROM t_problem_tags WHERE problem_id IN (" + ours + ")");
        jdbc.update("DELETE FROM t_problems WHERE problem_slug LIKE '" + PREFIX + "%'");
        sources.files.clear();
        sources.beforeRead = () -> { };
    }

    static String newSlug(String kind) {
        return PREFIX + kind + "-" + UUID.randomUUID();
    }

    /** A zip of these entries, in this order. */
    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    int problemCount(String slug) {
        return jdbc.queryForObject("SELECT count(*) FROM t_problems WHERE problem_slug = ?", Integer.class, slug);
    }
}
