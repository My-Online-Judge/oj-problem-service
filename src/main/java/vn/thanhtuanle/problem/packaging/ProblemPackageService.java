package vn.thanhtuanle.problem.packaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.common.util.FileUtil;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.problem.CheckerService;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.problem.ProblemService;
import vn.thanhtuanle.problem.dto.CreateProblemDto;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;
import vn.thanhtuanle.testcase.TestCaseBundleException;
import vn.thanhtuanle.testcase.TestCaseSourceStore;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Problem packages, format v1: a zip of {@code problem.json} ({@link ProblemPackage}) and the test cases as
 * {@code testcases/<name>.in} and {@code testcases/<name>.out}, plus {@code spj.cpp} at the root for a problem judged
 * by a checker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProblemPackageService {

    static final String PROBLEM_JSON = "problem.json";
    static final String TEST_CASE_DIR = "testcases/";
    private static final String IN = AppProperties.INPUT_FILE_EXTENSION;
    private static final String OUT = AppProperties.OUTPUT_FILE_EXTENSION;
    /** A test case name; anything else is ignored, such as the {@code ._1.in} resource forks of a zip made on macOS. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** A name that counts as a test-case number, like V6's high-water mark: at most 9 digits, so never an overflow. */
    private static final Pattern NUMBER = Pattern.compile("^[0-9]{1,9}$");

    private final ProblemRepository problemRepository;
    private final ProblemService problemService;
    private final TestCaseSourceStore sources;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final PlatformTransactionManager transactionManager;

    /**
     * Everything an export writes, read before the response streams; {@link #write} needs no database.
     * {@code checkerPath} is where the problem's checker source is stored, or null without a checker.
     */
    public record PackagePlan(CreateProblemDto problem, List<PlannedTestCase> testCases, String checkerPath) {

        ProblemPackage toPackage() {
            List<String> samples = testCases.stream().filter(PlannedTestCase::sample).map(PlannedTestCase::name).toList();
            return new ProblemPackage(ProblemPackage.SCHEMA_VERSION, problem, samples);
        }
    }

    /** A test case to export: its name, where its files are stored, and whether it is a sample. */
    public record PlannedTestCase(String name, String inputPath, String outputPath, boolean sample) {
    }

    /**
     * The first phase of an export, before the response streams: an unknown slug is a 404 and a missing file fails
     * here, while an error can still be answered. The rows are copied in their own read-only transaction and the files
     * are checked after it, so a slow MinIO never holds a database connection.
     */
    public PackagePlan plan(String slug) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        PackagePlan plan = readOnly.execute(tx -> planFromRows(slug));
        for (PlannedTestCase tc : plan.testCases()) {
            requireStored(tc.inputPath());
            requireStored(tc.outputPath());
        }
        if (plan.checkerPath() != null && !sources.exists(plan.checkerPath())) {
            throw new TestCaseBundleException(
                    "Checker source " + plan.checkerPath() + " is not stored; upload the checker again");
        }
        return plan;
    }

    /** The plan from the database alone; needs a transaction for the problem's lazy tags and test cases. */
    private PackagePlan planFromRows(String slug) {
        Problem problem = problemRepository.findLiveBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Problem not found with slug: " + slug));
        CreateProblemDto dto = CreateProblemDto.builder()
                .title(problem.getTitle())
                .subject(problem.getSubject())
                .description(problem.getDescription())
                .timeLimit(problem.getTimeLimit())
                .memoryLimit(problem.getMemoryLimit() == null ? 0 : problem.getMemoryLimit().intValue())
                .hardnessLevel(problem.getHardnessLevel())
                .problemSlug(problem.getProblemSlug())
                .inputDescription(problem.getInputDescription())
                .outputDescription(problem.getOutputDescription())
                .sampleInput(problem.getSampleInput())
                .sampleOutput(problem.getSampleOutput())
                .hint(problem.getHint())
                .tags(new ArrayList<>(problem.getTags()))
                .status(ProblemStatus.fromValue(problem.getStatus()))
                .build();
        List<PlannedTestCase> testCases = problem.getTestCases().stream()
                .map(tc -> new PlannedTestCase(nameOf(tc.getInput()), tc.getInput(), tc.getOutput(), tc.isSample()))
                .sorted(byName(PlannedTestCase::name).thenComparing(PlannedTestCase::inputPath))
                .toList();
        return new PackagePlan(dto, withImportableNames(testCases),
                problem.isSpj() ? CheckerService.checkerPath(problem.getProblemSlug()) : null);
    }

    /**
     * The test cases under names the import accepts, each name once, so that a copy loses no test case. A name that
     * fails {@link #NAME} (older uploads kept any file name), or repeats an earlier one, becomes the next free integer
     * above the largest numeric name, in plan order; the sample flag goes with it.
     */
    private static List<PlannedTestCase> withImportableNames(List<PlannedTestCase> testCases) {
        Set<String> taken = new HashSet<>();
        List<Integer> toRename = new ArrayList<>();
        for (int i = 0; i < testCases.size(); i++) {
            String name = testCases.get(i).name();
            if (!NAME.matcher(name).matches() || !taken.add(name)) {
                toRename.add(i);
            }
        }
        if (toRename.isEmpty()) {
            return testCases;
        }
        int next = taken.stream().filter(n -> NUMBER.matcher(n).matches()).mapToInt(Integer::parseInt).max().orElse(0);
        List<PlannedTestCase> renamed = new ArrayList<>(testCases);
        for (int i : toRename) {
            do {
                next++;
            } while (taken.contains(String.valueOf(next)));
            PlannedTestCase tc = renamed.get(i);
            renamed.set(i, new PlannedTestCase(String.valueOf(next), tc.inputPath(), tc.outputPath(), tc.sample()));
        }
        renamed.sort(byName(PlannedTestCase::name));
        return renamed;
    }

    /** The second phase of an export: the zip, from the plan and the stored files, with no transaction. */
    public void write(PackagePlan plan, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        putEntry(zip, PROBLEM_JSON, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(plan.toPackage()));
        if (plan.checkerPath() != null) {
            putEntry(zip, CheckerService.CHECKER_FILE, sources.get(plan.checkerPath()).orElseThrow(
                    () -> new TestCaseBundleException("Checker source " + plan.checkerPath() + " is not stored")));
        }
        for (PlannedTestCase tc : plan.testCases()) {
            putEntry(zip, TEST_CASE_DIR + tc.name() + IN, read(tc.inputPath()));
            putEntry(zip, TEST_CASE_DIR + tc.name() + OUT, read(tc.outputPath()));
        }
        zip.finish(); // the servlet container closes the response stream
    }

    /**
     * Creates a new problem from a package. Entries are matched by file name, whatever their folder; {@code slugOverride},
     * when given, replaces the package's slug before the problem is validated. An {@code spj.cpp} makes the problem a
     * checker problem, by an upload's rules.
     */
    public ProblemResponseDto importPackage(MultipartFile file, String slugOverride) throws IOException {
        Map<String, byte[]> entries = FileUtil.extractZip(file);
        byte[] json = entries.get(PROBLEM_JSON);
        if (json == null) {
            throw new IllegalArgumentException("Package has no problem.json");
        }
        ProblemPackage pkg = parse(json);
        if (pkg.schemaVersion() != ProblemPackage.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported package schemaVersion: " + pkg.schemaVersion());
        }
        CreateProblemDto dto = pkg.problem();
        if (dto == null) {
            throw new IllegalArgumentException("problem.json: problem is required");
        }
        if (slugOverride != null && !slugOverride.isBlank()) {
            dto.setProblemSlug(slugOverride);
        }
        validate(dto);
        List<PackagedTestCase> testCases = testCasesOf(entries,
                pkg.samples() == null ? Set.of() : new HashSet<>(pkg.samples()));
        if (testCases.isEmpty()) {
            throw new IllegalArgumentException("Package has no test cases");
        }
        log.info("Importing package as problem {} with {} test cases", dto.getProblemSlug(), testCases.size());
        return problemService.createFromPackage(dto, testCases, entries.get(CheckerService.CHECKER_FILE));
    }

    private ProblemPackage parse(byte[] json) throws IOException {
        try {
            ProblemPackage pkg = objectMapper.readValue(json, ProblemPackage.class);
            if (pkg == null) {
                throw new IllegalArgumentException("problem.json is not valid JSON");
            }
            return pkg;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("problem.json is not valid JSON");
        }
    }

    /** The first violation by property path, so the message does not depend on the validator's set order. */
    private void validate(CreateProblemDto dto) {
        validator.validate(dto).stream()
                .min(Comparator.<ConstraintViolation<CreateProblemDto>, String>comparing(v -> v.getPropertyPath().toString())
                        .thenComparing(ConstraintViolation::getMessage))
                .ifPresent(v -> {
                    throw new IllegalArgumentException("problem.json: " + v.getPropertyPath() + " " + v.getMessage());
                });
    }

    /** Every {@code <name>.in} with a valid name and a matching {@code <name>.out}; the rest is ignored. */
    private static List<PackagedTestCase> testCasesOf(Map<String, byte[]> entries, Set<String> samples) {
        List<PackagedTestCase> testCases = new ArrayList<>();
        entries.forEach((file, input) -> {
            if (!file.endsWith(IN)) {
                return;
            }
            String name = file.substring(0, file.length() - IN.length());
            byte[] output = entries.get(name + OUT);
            if (NAME.matcher(name).matches() && output != null) {
                testCases.add(new PackagedTestCase(name, input, output, samples.contains(name)));
            }
        });
        testCases.sort(byName(PackagedTestCase::name));
        return testCases;
    }

    private void requireStored(String path) {
        if (!sources.exists(path)) {
            throw new TestCaseBundleException(
                    "Test-case file " + path + " is not stored; upload it again or delete its test case");
        }
    }

    private byte[] read(String path) {
        return sources.get(path).orElseThrow(() -> new TestCaseBundleException("Test-case file " + path + " is not stored"));
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /** A stored path's file name without its extension: {@code two-sum/10.in} is test case {@code 10}. */
    private static String nameOf(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot >= 0 ? file.substring(0, dot) : file;
    }

    /** Numeric names in numeric order ({@code 2} before {@code 10}), then the other names in string order. */
    private static <T> Comparator<T> byName(Function<T, String> name) {
        return Comparator.<T>comparingInt(t -> numericOrMax(name.apply(t))).thenComparing(name);
    }

    private static int numericOrMax(String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
