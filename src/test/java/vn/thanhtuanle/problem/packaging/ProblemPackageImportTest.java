package vn.thanhtuanle.problem.packaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Importing a package through POST /problems/import, on Postgres: what is refused, what is ignored. */
class ProblemPackageImportTest extends PackagingPostgresTest {

    @Autowired ObjectMapper objectMapper;

    /** A valid problem.json for this slug, with test case 1 as its sample. */
    private ObjectNode problemJson(String slug) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        ObjectNode p = root.putObject("problem");
        p.put("title", "Add two numbers");
        p.put("subject", "Basics");
        p.put("description", "Print a + b.");
        p.put("timeLimit", 1000);
        p.put("memoryLimit", 256);
        p.put("hardnessLevel", 1);
        p.put("problemSlug", slug);
        p.put("inputDescription", "a and b");
        p.put("outputDescription", "a + b");
        p.put("sampleInput", "1 2");
        p.put("sampleOutput", "3");
        p.put("status", 1);
        p.putArray("tags").add("math");
        root.putArray("samples").add("1");
        return root;
    }

    private Map<String, byte[]> entries(ObjectNode problemJson) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("problem.json", objectMapper.writeValueAsBytes(problemJson));
        entries.put("testcases/1.in", utf8("1 2\n"));
        entries.put("testcases/1.out", utf8("3\n"));
        return entries;
    }

    private ResultActions importZip(byte[] zip) throws Exception {
        return mvc.perform(multipart("/api/v1/problems/import")
                .file(new MockMultipartFile("file", "package.zip", "application/zip", zip))
                .with(user("admin").authorities(new SimpleGrantedAuthority("problem:create"))));
    }

    private String storedStatus(String slug) {
        return jdbc.queryForObject("SELECT status FROM t_problems WHERE problem_slug = ?", String.class, slug);
    }

    @Test
    void duplicateSlugIs409() throws Exception {
        String slug = newSlug("imp");
        byte[] pkg = zip(entries(problemJson(slug)));
        importZip(pkg).andExpect(status().isCreated());

        importZip(pkg)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString(slug)));
        assertThat(problemCount(slug)).isEqualTo(1);
    }

    @Test
    void aDeletedProblemsSlugIs409() throws Exception {
        String slug = newSlug("imp");
        jdbc.update("""
                INSERT INTO t_problems (id, hardness_level, memory_limit, status, time_limit, created_at, updated_at, title, problem_slug)
                VALUES (?, 1, 64, 2, 1000, now(), now(), 'deleted', ?)""", UUID.randomUUID(), slug);

        importZip(zip(entries(problemJson(slug))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        assertThat(problemCount(slug)).isEqualTo(1);
    }

    @Test
    void missingStatusImportsInactive() throws Exception {
        String slug = newSlug("imp");
        ObjectNode json = problemJson(slug);
        ((ObjectNode) json.get("problem")).remove("status");

        importZip(zip(entries(json)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value(0));
        assertThat(storedStatus(slug)).isEqualTo("0");
    }

    /** Cut off inside a test case's data (a cut between entries reads as a shorter zip), or not a zip at all. */
    @Test
    void aCorruptOrNonZipUploadIs400() throws Exception {
        Map<String, byte[]> entries = entries(problemJson(newSlug("imp")));
        byte[] incompressible = new byte[64 * 1024];
        new java.util.Random(7).nextBytes(incompressible);
        entries.put("testcases/1.in", incompressible);
        byte[] pkg = zip(entries);

        importZip(java.util.Arrays.copyOf(pkg, pkg.length / 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The upload is not a valid zip file"));
        importZip(utf8("this is not a zip"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noTestCasesIs400() throws Exception {
        String slug = newSlug("imp");
        Map<String, byte[]> entries = entries(problemJson(slug));
        entries.remove("testcases/1.in");
        entries.remove("testcases/1.out");

        importZip(zip(entries))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Package has no test cases"));
        assertThat(problemCount(slug)).isZero();
    }

    @Test
    void anUnpairedFileIsIgnored() throws Exception {
        String slug = newSlug("imp");
        Map<String, byte[]> entries = entries(problemJson(slug));
        entries.put("testcases/2.in", utf8("5 5\n"));
        entries.put("testcases/3.out", utf8("7\n"));

        importZip(zip(entries)).andExpect(status().isCreated());

        assertThat(jdbc.queryForList("""
                SELECT tc.input FROM t_test_cases tc JOIN t_problems p ON p.id = tc.problem_id
                WHERE p.problem_slug = ?""", String.class, slug)).containsExactly(slug + "/1.in");
    }

    @Test
    void noProblemJsonIs400() throws Exception {
        String slug = newSlug("imp");
        Map<String, byte[]> entries = entries(problemJson(slug));
        entries.remove("problem.json");

        importZip(zip(entries))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Package has no problem.json"));
    }

    @Test
    void problemJsonThatIsNotJsonIs400() throws Exception {
        Map<String, byte[]> entries = entries(problemJson(newSlug("imp")));
        entries.put("problem.json", utf8("{\"schemaVersion\": 1, \"problem\": {"));

        importZip(zip(entries))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("problem.json is not valid JSON"));
    }

    @Test
    void schemaVersion2Is400() throws Exception {
        String slug = newSlug("imp");
        ObjectNode json = problemJson(slug);
        json.put("schemaVersion", 2);

        importZip(zip(entries(json)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported package schemaVersion: 2"));
        assertThat(problemCount(slug)).isZero();
    }

    @Test
    void blankTitleIs400() throws Exception {
        String slug = newSlug("imp");
        ObjectNode json = problemJson(slug);
        ((ObjectNode) json.get("problem")).put("title", "  ");

        importZip(zip(entries(json)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("problem.json: title Title is required"));
        assertThat(problemCount(slug)).isZero();
    }

    @Test
    void macOsResourceForksAreIgnored() throws Exception {
        String slug = newSlug("imp");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("problem.json", objectMapper.writeValueAsBytes(problemJson(slug)));
        entries.put("__MACOSX/testcases/._1.in", new byte[] {0, 5, 22, 7});
        entries.put("__MACOSX/testcases/._1.out", new byte[] {0, 5, 22, 7});
        entries.put("testcases/1.in", utf8("1 2\n"));
        entries.put("testcases/1.out", utf8("3\n"));

        importZip(zip(entries)).andExpect(status().isCreated());

        assertThat(jdbc.queryForList("""
                SELECT tc.input FROM t_test_cases tc JOIN t_problems p ON p.id = tc.problem_id
                WHERE p.problem_slug = ?""", String.class, slug)).containsExactly(slug + "/1.in");
        assertThat(sources.files.get(slug + "/1.in")).isEqualTo(utf8("1 2\n"));
    }

    @Test
    void oversizedEntryIs400() throws Exception {
        String slug = newSlug("imp");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("problem.json"));
            zos.write(objectMapper.writeValueAsBytes(problemJson(slug)));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("testcases/1.in"));
            byte[] mib = new byte[1 << 20];
            for (int i = 0; i < 50; i++) {
                zos.write(mib);
            }
            zos.write(0); // one byte over 50 MiB; zeros compress to a few dozen KiB
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("testcases/1.out"));
            zos.write(utf8("0\n"));
            zos.closeEntry();
        }

        importZip(bytes.toByteArray())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Zip entry 1.in exceeds 50 MiB"));
        assertThat(problemCount(slug)).isZero();
    }
}
