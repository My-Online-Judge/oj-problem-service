package vn.thanhtuanle.problem;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
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
import vn.thanhtuanle.support.PostgresTest;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Statement columns are text (V4): a long statement is stored whole, and the DTO caps it before the database does. */
class ProblemLongStatementOnPostgresTest extends PostgresTest {

    @TestConfiguration
    static class Sources {
        @Bean
        @Primary
        InMemoryTestCaseSourceStore inMemorySources() {
            return new InMemoryTestCaseSourceStore();
        }
    }

    @Autowired ProblemService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired Validator validator;
    @MockitoBean TestCaseBundlePublisher publisher;
    @MockitoBean TestCaseBundleStore bundleStore;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM t_test_cases WHERE problem_id IN (SELECT id FROM t_problems WHERE problem_slug LIKE 'long-pg-%')");
        jdbc.update("DELETE FROM t_problem_tags WHERE problem_id IN (SELECT id FROM t_problems WHERE problem_slug LIKE 'long-pg-%')");
        jdbc.update("DELETE FROM t_problems WHERE problem_slug LIKE 'long-pg-%'");
    }

    private static CreateProblemDto.CreateProblemDtoBuilder base(String slug) {
        return CreateProblemDto.builder().title("t").subject("s").description("d").timeLimit(1000).memoryLimit(64)
                .hardnessLevel(1).problemSlug(slug).inputDescription("i").outputDescription("o")
                .sampleInput("1 2").sampleOutput("3").status(ProblemStatus.ACTIVE);
    }

    private static MockMultipartFile zip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (String[] e : new String[][] {{"1.in", "1 2\n"}, {"1.out", "3\n"}}) {
                zos.putNextEntry(new ZipEntry(e[0]));
                zos.write(e[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", "cases.zip", "application/zip", bytes.toByteArray());
    }

    @Test
    void createsProblemWithLongStatementFields() throws Exception {
        String slug = "long-pg-" + UUID.randomUUID();
        String text = "x".repeat(5000);

        service.createProblem(base(slug).hint(text).inputDescription(text).outputDescription(text)
                .sampleInput(text).sampleOutput(text).build(), zip());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT hint, input_description, output_description, sample_input, sample_output FROM t_problems WHERE problem_slug = ?", slug);
        assertThat(row.values()).hasSize(5).allSatisfy(v -> assertThat(v).isEqualTo(text));
    }

    @Test
    void rejectsStatementFieldOverCap() {
        CreateProblemDto dto = base("long-pg-cap").hint("x".repeat(65537)).build();

        assertThat(validator.validate(dto)).extracting(v -> v.getPropertyPath().toString()).containsExactly("hint");
    }

    @Test
    void titleOver255Rejected() {
        CreateProblemDto dto = base("long-pg-title").title("x".repeat(256)).build();

        Set<ConstraintViolation<CreateProblemDto>> violations = validator.validate(dto);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("title");
    }
}
