package vn.thanhtuanle.problem.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.problem.CheckerService;
import vn.thanhtuanle.problem.ProblemService;
import vn.thanhtuanle.problem.dto.CreateProblemDto;
import vn.thanhtuanle.testcase.BundleFile;
import vn.thanhtuanle.testcase.TestCaseBundleException;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Export a problem as a package and import it back as a new problem, on Postgres. The export decides everything that
 * can fail (an unknown slug, a missing file) before it streams, and streams with no database connection held.
 */
class ProblemPackageRoundTripTest extends PackagingPostgresTest {

    private static final String EXPORT = "/api/v1/problems/{slug}/export";

    /** Test case name, then its input and output. */
    private static final Map<String, String[]> CASES = new LinkedHashMap<>();

    static {
        CASES.put("1", new String[] {"2\n1 2\n", "3\n"});
        CASES.put("2", new String[] {"3\n4 5 6\n", "15\n"});
        CASES.put("10", new String[] {"1\n-7\n", "-7\n"});
    }

    @Autowired ProblemService problems;
    @Autowired ObjectMapper objectMapper;
    @Autowired DataSource dataSource;
    @Autowired CheckerService checkers;

    private static RequestPostProcessor admin(String authority) {
        return user("admin").authorities(new SimpleGrantedAuthority(authority));
    }

    /** The problem the round trip starts from: test cases 1, 2 and 10 (1 is a sample), two tags, INACTIVE. */
    private void seed(String slug) throws Exception {
        CreateProblemDto dto = CreateProblemDto.builder().title("Two sum").subject("Arrays")
                .description("Find two numbers.").timeLimit(2000).memoryLimit(128).hardnessLevel(2).problemSlug(slug)
                .inputDescription("n, then n numbers").outputDescription("Their sum").sampleInput("2\n1 2\n")
                .sampleOutput("3\n").hint("Add them up.").tags(List.of("graph", "dp")).status(ProblemStatus.INACTIVE)
                .build();
        Map<String, byte[]> files = new LinkedHashMap<>();
        CASES.forEach((name, io) -> {
            files.put(name + ".in", utf8(io[0]));
            files.put(name + ".out", utf8(io[1]));
        });
        problems.createProblem(dto, new MockMultipartFile("file", "cases.zip", "application/zip", zip(files)));
        jdbc.update("""
                UPDATE t_test_cases SET is_sample = (input = ?)
                WHERE problem_id = (SELECT id FROM t_problems WHERE problem_slug = ?)""", slug + "/1.in", slug);
        clearInvocations(bundleStore);
    }

    /** GET export through MockMvc: the controller has returned before the body streams. */
    private byte[] export(String slug) throws Exception {
        MvcResult started = mvc.perform(get(EXPORT, slug).with(admin("problem:update")))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    /** The stored problem as the fields of a create request, tags sorted. */
    private CreateProblemDto stored(String slug) {
        CreateProblemDto dto = jdbc.queryForObject("""
                SELECT title, subject, description, time_limit, memory_limit, hardness_level, problem_slug,
                       input_description, output_description, sample_input, sample_output, hint, status
                FROM t_problems WHERE problem_slug = ?""", (rs, i) -> CreateProblemDto.builder()
                .title(rs.getString("title")).subject(rs.getString("subject"))
                .description(rs.getString("description")).timeLimit(rs.getInt("time_limit"))
                .memoryLimit(rs.getInt("memory_limit")).hardnessLevel(rs.getInt("hardness_level"))
                .problemSlug(rs.getString("problem_slug")).inputDescription(rs.getString("input_description"))
                .outputDescription(rs.getString("output_description")).sampleInput(rs.getString("sample_input"))
                .sampleOutput(rs.getString("sample_output")).hint(rs.getString("hint"))
                .status(ProblemStatus.fromValue(rs.getInt("status"))).build(), slug);
        dto.setTags(jdbc.queryForList("""
                SELECT t.tag FROM t_problem_tags t JOIN t_problems p ON p.id = t.problem_id
                WHERE p.problem_slug = ? ORDER BY t.tag""", String.class, slug));
        return dto;
    }

    /** Stored input file name ("1.in") to its sample flag. */
    private Map<String, Boolean> sampleFlags(String slug) {
        Map<String, Boolean> flags = new TreeMap<>();
        jdbc.query("""
                SELECT tc.input, tc.is_sample FROM t_test_cases tc JOIN t_problems p ON p.id = tc.problem_id
                WHERE p.problem_slug = ?""", rs -> {
            flags.put(rs.getString(1).substring(slug.length() + 1), rs.getBoolean(2));
        }, slug);
        return flags;
    }

    @Test
    void exportThenImportGivesAnEqualProblem() throws Exception {
        String slug = newSlug("rt");
        String copy = slug + "-copy";
        seed(slug);

        byte[] pkg = export(slug);
        mvc.perform(multipart("/api/v1/problems/import")
                        .file(new MockMultipartFile("file", slug + ".zip", "application/zip", pkg))
                        .param("slugOverride", copy)
                        .with(admin("problem:create")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.problemSlug").value(copy));

        CreateProblemDto original = stored(slug);
        assertThat(original).hasNoNullFieldsOrProperties();
        assertThat(original.getTags()).containsExactly("dp", "graph");
        assertThat(original.getStatus()).isEqualTo(ProblemStatus.INACTIVE);
        assertThat(stored(copy)).usingRecursiveComparison().ignoringFields("problemSlug").isEqualTo(original);
        for (String name : CASES.keySet()) {
            for (String file : List.of(name + ".in", name + ".out")) {
                assertThat(sources.files.get(copy + "/" + file)).as(file)
                        .isNotNull().isEqualTo(sources.files.get(slug + "/" + file));
            }
        }
        assertThat(sampleFlags(copy)).isEqualTo(sampleFlags(slug))
                .isEqualTo(Map.of("1.in", true, "2.in", false, "10.in", false));
        assertThat(jdbc.queryForObject("SELECT test_case_high_water FROM t_problems WHERE problem_slug = ?",
                Integer.class, copy)).isEqualTo(10);
        verify(bundleStore, times(1)).publish(anyString(), anyList());
        verify(bundleStore).publish(eq(copy), anyList());
    }

    @Test
    void exportWritesProblemJsonAndTestcases() throws Exception {
        String slug = newSlug("rt");
        seed(slug);

        Map<String, byte[]> entries = unzip(export(slug));

        assertThat(entries.keySet()).containsExactly("problem.json", "testcases/1.in", "testcases/1.out",
                "testcases/2.in", "testcases/2.out", "testcases/10.in", "testcases/10.out");
        assertThat(entries.get("testcases/10.out")).isEqualTo(utf8(CASES.get("10")[1]));
        String text = new String(entries.get("problem.json"), StandardCharsets.UTF_8);
        assertThat(text).as("pretty-printed").contains("\"schemaVersion\" : 1");
        JsonNode json = objectMapper.readTree(text);
        assertThat(json.get("schemaVersion").intValue()).isEqualTo(1);
        List<String> samples = new ArrayList<>();
        json.get("samples").forEach(n -> samples.add(n.textValue()));
        assertThat(samples).containsExactly("1");
        List<String> tags = new ArrayList<>();
        json.at("/problem/tags").forEach(n -> tags.add(n.textValue()));
        assertThat(tags).containsExactlyInAnyOrder("graph", "dp");
        assertThat(json.at("/problem/status").isInt()).as("status is written as its value").isTrue();
        assertThat(json.at("/problem/status").intValue()).isEqualTo(ProblemStatus.INACTIVE.getValue());
        assertThat(json.at("/problem/problemSlug").textValue()).isEqualTo(slug);
    }

    /** Stored input text, output text and sample flag of every test case, in a stable order. */
    private List<String> contents(String slug) {
        return jdbc.query("""
                SELECT tc.input, tc.output, tc.is_sample FROM t_test_cases tc JOIN t_problems p ON p.id = tc.problem_id
                WHERE p.problem_slug = ?""", (rs, i) -> new String(sources.files.get(rs.getString(1)), StandardCharsets.UTF_8)
                + " | " + new String(sources.files.get(rs.getString(2)), StandardCharsets.UTF_8)
                + " | " + (rs.getBoolean(3) ? "sample" : "hidden"), slug)
                .stream().sorted().toList();
    }

    /**
     * Names the import would drop (a dot, a space, over 64 characters) and a second row named like the first are
     * renamed to the next free integers, in plan order, so the copy keeps every test case and its sample flag.
     */
    @Test
    void exportRenamesWhatTheImportWouldDrop() throws Exception {
        String slug = newSlug("odd");
        String copy = slug + "-copy";
        String longName = "x".repeat(65);
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : List.of("1", "2", "10", "case 1", "test.1", longName)) {
            files.put(name + ".in", utf8("in " + name + "\n"));
            files.put(name + ".out", utf8("out " + name + "\n"));
        }
        problems.createProblem(CreateProblemDto.builder().title("Odd names").subject("s").timeLimit(1000).memoryLimit(64)
                .hardnessLevel(1).problemSlug(slug).inputDescription("i").outputDescription("o").sampleInput("1")
                .sampleOutput("1").status(ProblemStatus.INACTIVE).build(),
                new MockMultipartFile("file", "cases.zip", "application/zip", zip(files)));
        // A row from before today's naming, stored in a folder: its name is 2 as well.
        jdbc.update("""
                INSERT INTO t_test_cases (id, problem_id, created_at, updated_at, input, output, is_sample)
                SELECT gen_random_uuid(), id, now(), now(), ?, ?, false FROM t_problems WHERE problem_slug = ?""",
                slug + "/extra/2.in", slug + "/extra/2.out", slug);
        sources.put(slug + "/extra/2.in", utf8("in extra 2\n"));
        sources.put(slug + "/extra/2.out", utf8("out extra 2\n"));
        jdbc.update("UPDATE t_test_cases SET is_sample = (input = ?) WHERE input LIKE ?", slug + "/test.1.in", slug + "/%");

        byte[] pkg = export(slug);

        Map<String, byte[]> entries = unzip(pkg);
        List<String> expected = new ArrayList<>(List.of("problem.json"));
        for (String name : List.of("1", "2", "10", "11", "12", "13", "14")) {
            expected.add("testcases/" + name + ".in");
            expected.add("testcases/" + name + ".out");
        }
        assertThat(entries.keySet()).containsExactlyElementsOf(expected);
        assertThat(entries.get("testcases/2.in")).isEqualTo(utf8("in 2\n"));
        assertThat(entries.get("testcases/11.in")).isEqualTo(utf8("in extra 2\n"));
        assertThat(entries.get("testcases/12.out")).isEqualTo(utf8("out case 1\n"));
        assertThat(entries.get("testcases/13.in")).isEqualTo(utf8("in test.1\n"));
        assertThat(entries.get("testcases/14.in")).isEqualTo(utf8("in " + longName + "\n"));
        List<String> samples = new ArrayList<>();
        objectMapper.readTree(entries.get("problem.json")).get("samples").forEach(n -> samples.add(n.textValue()));
        assertThat(samples).containsExactly("13");

        mvc.perform(multipart("/api/v1/problems/import")
                        .file(new MockMultipartFile("file", slug + ".zip", "application/zip", pkg))
                        .param("slugOverride", copy)
                        .with(admin("problem:create")))
                .andExpect(status().isCreated());
        assertThat(contents(copy)).hasSize(7).isEqualTo(contents(slug));
    }

    private static final String CHECKER = """
            #include <fstream>
            int main(int argc, char** argv) {
                std::ifstream in(argv[1]), out(argv[2]);
                long long n, x, sum = 0, answer;
                in >> n;
                while (n-- > 0 && in >> x) sum += x;
                return (out >> answer) && answer == sum ? 0 : 1;
            }
            """;

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** The info file of the last bundle published for this slug. */
    private String publishedInfo(String slug) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BundleFile>> files = ArgumentCaptor.forClass(List.class);
        verify(bundleStore, atLeastOnce()).publish(eq(slug), files.capture());
        return files.getValue().stream().filter(f -> f.name().equals("info")).findFirst()
                .map(f -> new String(f.content(), StandardCharsets.UTF_8)).orElseThrow();
    }

    /**
     * A checker problem's package carries {@code spj.cpp} at its root, and the import makes the copy a checker problem
     * with the same source and version. The plan's existence check and the stream's read of the checker hold no
     * connection either.
     */
    @Test
    void roundTripKeepsTheChecker() throws Exception {
        String slug = newSlug("rt");
        String copy = slug + "-copy";
        seed(slug);
        byte[] checker = utf8(CHECKER);
        checkers.upload(slug, new MockMultipartFile("file", "spj.cpp", "text/x-c++src", checker));
        clearInvocations(bundleStore);
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        List<Integer> activeWhileCalling = new CopyOnWriteArrayList<>();
        sources.beforeRead = () -> activeWhileCalling.add(pool.getActiveConnections());

        byte[] pkg = export(slug);

        sources.beforeRead = () -> { };
        assertThat(activeWhileCalling).hasSize(4 * CASES.size() + 2).containsOnly(0);
        Map<String, byte[]> entries = unzip(pkg);
        assertThat(entries.keySet()).containsExactly("problem.json", "spj.cpp", "testcases/1.in", "testcases/1.out",
                "testcases/2.in", "testcases/2.out", "testcases/10.in", "testcases/10.out");
        assertThat(entries.get("spj.cpp")).isEqualTo(checker);

        mvc.perform(multipart("/api/v1/problems/import")
                        .file(new MockMultipartFile("file", slug + ".zip", "application/zip", pkg))
                        .param("slugOverride", copy)
                        .with(admin("problem:create")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.spj").value(true));

        String version = sha256(checker);
        assertThat(jdbc.queryForMap("SELECT spj, spj_version FROM t_problems WHERE problem_slug = ?", copy))
                .containsEntry("spj", true).containsEntry("spj_version", version);
        assertThat(sources.files.get(copy + "/spj.cpp")).isEqualTo(checker);
        verify(bundleStore, times(1)).publish(anyString(), anyList());
        assertThat(publishedInfo(copy)).contains("\"spj\" : true").contains("\"spj_version\" : \"" + version + "\"");
    }

    @Test
    void missingCheckerSourceFailsBeforeStreaming() throws Exception {
        String slug = newSlug("rt");
        seed(slug);
        checkers.upload(slug, new MockMultipartFile("file", "spj.cpp", "text/x-c++src", utf8(CHECKER)));
        sources.files.remove(slug + "/spj.cpp");

        assertThatThrownBy(() -> packages.plan(slug))
                .isInstanceOf(TestCaseBundleException.class)
                .hasMessageContaining(slug + "/spj.cpp");
        mvc.perform(get(EXPORT, slug).with(admin("problem:update")))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isInternalServerError());
    }

    @Test
    void exportOfAnUnknownSlugIs404() throws Exception {
        mvc.perform(get(EXPORT, PREFIX + "no-such-problem").with(admin("problem:update")))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void missingSourceFileFailsBeforeStreaming() throws Exception {
        String slug = newSlug("rt");
        seed(slug);
        sources.files.remove(slug + "/2.out");

        assertThatThrownBy(() -> packages.plan(slug))
                .isInstanceOf(TestCaseBundleException.class)
                .hasMessageContaining(slug + "/2.out");
        mvc.perform(get(EXPORT, slug).with(admin("problem:update")))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /** Neither the plan's existence checks (2 per test case) nor the stream's reads (2 more) hold a connection. */
    @Test
    void exportHoldsNoDatabaseConnectionWhileCallingTheFileStore() throws Exception {
        String slug = newSlug("rt");
        seed(slug);
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        List<Integer> activeWhileCalling = new CopyOnWriteArrayList<>();
        sources.beforeRead = () -> activeWhileCalling.add(pool.getActiveConnections());

        export(slug);

        assertThat(activeWhileCalling).hasSize(4 * CASES.size()).containsOnly(0);
    }
}
