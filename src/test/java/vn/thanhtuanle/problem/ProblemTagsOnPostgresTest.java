package vn.thanhtuanle.problem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.problem.dto.CreateProblemDto;
import vn.thanhtuanle.problem.dto.TagCount;
import vn.thanhtuanle.problem.dto.UpdateProblemDto;
import vn.thanhtuanle.support.PostgresTest;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tags end to end on Postgres: write path, vocabulary (counts seeded rows too), the list filter and the reserved slug. */
class ProblemTagsOnPostgresTest extends PostgresTest {

    @TestConfiguration
    static class Sources {
        @Bean
        @Primary
        InMemoryTestCaseSourceStore inMemorySources() {
            return new InMemoryTestCaseSourceStore();
        }
    }

    private static final String P = "tagpg-";

    @Autowired ProblemService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TestCaseBundlePublisher publisher;
    @MockitoBean TestCaseBundleStore bundleStore;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM t_test_cases WHERE problem_id IN (SELECT id FROM t_problems WHERE problem_slug LIKE '" + P + "%' OR problem_slug = 'TAGS')");
        jdbc.update("DELETE FROM t_problem_tags WHERE problem_id IN (SELECT id FROM t_problems WHERE problem_slug LIKE '" + P + "%')");
        jdbc.update("DELETE FROM t_problems WHERE problem_slug LIKE '" + P + "%'");
    }

    private static CreateProblemDto dto(String slug, List<String> tags) {
        return CreateProblemDto.builder().title("t").subject("s").description("d").timeLimit(1000).memoryLimit(64)
                .hardnessLevel(1).problemSlug(slug).inputDescription("i").outputDescription("o")
                .sampleInput("1 2").sampleOutput("3").status(ProblemStatus.ACTIVE).tags(tags).build();
    }

    private static UpdateProblemDto update(List<String> tags) {
        return UpdateProblemDto.builder().title("t2").subject("s").description("d").timeLimit(1000).memoryLimit(64)
                .hardnessLevel(1).inputDescription("i").outputDescription("o").sampleInput("1 2").sampleOutput("3")
                .tags(tags).build();
    }

    private static MockMultipartFile zip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("1.in"));
            zos.write("1\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("1.out"));
            zos.write("1\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return new MockMultipartFile("file", "cases.zip", "application/zip", bytes.toByteArray());
    }

    private List<String> tagsInDb(String slug) {
        return jdbc.queryForList("""
                SELECT t.tag FROM t_problem_tags t JOIN t_problems p ON p.id = t.problem_id
                WHERE p.problem_slug = ? ORDER BY t.tag""", String.class, slug);
    }

    private void insertProblem(String slug, int status, String description, String... tags) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_problems (id, hardness_level, memory_limit, status, time_limit, created_at, updated_at, title, problem_slug, description)
                VALUES (?, 1, 64, ?, 1000, now(), now(), ?, ?, ?)""", id, status, "title " + slug, slug, description);
        for (String t : tags) {
            jdbc.update("INSERT INTO t_problem_tags (problem_id, tag) VALUES (?, ?)", id, t);
        }
    }

    @Test
    void createStoresTags() throws Exception {
        String slug = P + UUID.randomUUID();
        service.createProblem(dto(slug, List.of(" DP ", "graph", "dp")), zip());
        assertThat(tagsInDb(slug)).containsExactly("dp", "graph");
    }

    @Test
    void updateReplacesTags() throws Exception {
        String slug = P + UUID.randomUUID();
        service.createProblem(dto(slug, List.of("dp", "graph")), zip());
        var updated = service.updateProblem(slug, update(List.of("Math", "dp")));
        assertThat(tagsInDb(slug)).containsExactly("dp", "math");
        assertThat(updated.getTags()).containsExactlyInAnyOrder("dp", "math");
    }

    @Test
    void updateWithNullKeepsTags() throws Exception {
        String slug = P + UUID.randomUUID();
        service.createProblem(dto(slug, List.of("dp", "graph")), zip());
        service.updateProblem(slug, update(null));
        assertThat(tagsInDb(slug)).containsExactly("dp", "graph");
        service.updateProblem(slug, update(List.of()));
        assertThat(tagsInDb(slug)).isEmpty();
    }

    @Test
    void vocabularyCountsActiveOnly() {
        // tag names unique to this test; counts include rows written by SQL, like the seeded problems
        insertProblem(P + "a", 1, "d", "zz-voc", "zz-voc-b");
        insertProblem(P + "b", 1, "d", "zz-voc");
        insertProblem(P + "c", 0, "d", "zz-voc", "zz-inactive");
        insertProblem(P + "d", 2, "d", "zz-voc", "zz-deleted");

        List<TagCount> vocab = service.getTagVocabulary();

        assertThat(vocab).contains(new TagCount("zz-voc", 2), new TagCount("zz-voc-b", 1));
        assertThat(vocab).extracting(TagCount::tag).doesNotContain("zz-inactive", "zz-deleted");
        assertThat(vocab).isSortedAccordingTo((x, y) -> x.count() != y.count()
                ? Long.compare(y.count(), x.count()) : x.tag().compareTo(y.tag()));
    }

    @Test
    void tagFilterPagesAndCountsAgree() {
        for (int i = 0; i < 25; i++) {
            if (i < 12) {
                insertProblem(P + "f" + i, 1, "needle", "zz-dp");
            } else {
                insertProblem(P + "f" + i, 1, "needle");
            }
        }
        var first = service.getProblems(0, 10, "needle", null, null, "ZZ-DP", true);
        var second = service.getProblems(1, 10, "needle", null, null, "zz-dp", true);

        assertThat(first.getTotalElements()).isEqualTo(12);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getData()).hasSize(10);
        assertThat(second.getData()).hasSize(2);
        assertThat(first.getData()).allSatisfy(p -> assertThat(p.getTags()).containsExactly("zz-dp"));
        // search that only matches the description counts the same rows in page and count
        assertThat(service.getProblems(0, 10, "needle", null, null, null, true).getTotalElements()).isEqualTo(25);
    }

    @Test
    void detailCarriesTags() throws Exception {
        String slug = P + UUID.randomUUID();
        service.createProblem(dto(slug, List.of("dp", "graph")), zip());
        assertThat(service.getProblemBySlug(slug, true).getTags()).containsExactlyInAnyOrder("dp", "graph");
    }

    @Test
    void detailThenUpdateWithItsOwnTagsKeepsThem() throws Exception {
        String slug = P + UUID.randomUUID();
        service.createProblem(dto(slug, List.of("dp", "graph")), zip());
        // what the admin edit form does: prefill from the detail, send the same tags back
        List<String> prefilled = service.getProblemBySlug(slug, true).getTags();
        service.updateProblem(slug, update(prefilled));
        assertThat(tagsInDb(slug)).containsExactly("dp", "graph");
    }

    @Test
    void reservedSlugRejected() {
        assertThatThrownBy(() -> service.createProblem(dto("TAGS", List.of()), zip()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Problem slug \"tags\" is reserved");
    }
}
