package vn.thanhtuanle.problem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.common.exception.ResourceNotFoundException;
import vn.thanhtuanle.common.util.AfterCommit;
import vn.thanhtuanle.common.util.FileUtil;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.problem.dto.TestCaseContext;
import vn.thanhtuanle.problem.dto.TestCaseResponse;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseSourceStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages a problem's test cases after creation (list / add / import / delete). Files are kept in the
 * {@link TestCaseSourceStore} under {@code {slug}/{index}.in|out}, named with the next integer index so
 * the judge (which sorts {@code .in} files numerically) keeps a stable order. After every change to the
 * set of test cases the problem's bundle is rebuilt from the rows and republished.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestCaseService {

    private final ProblemRepository problemRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseSourceStore sources;
    private final TestCaseBundlePublisher publisher;

    private static final Comparator<TestCaseResponse> BY_NUMERIC_NAME =
            Comparator.comparingInt(r -> parseIntOrMax(r.getName()));

    @Transactional(readOnly = true)
    public List<TestCaseResponse> listTestCases(String slug) {
        log.info("Start list test cases for problem: {}", slug);
        Problem problem = getProblemOrThrow(slug);

        List<TestCaseResponse> result = new ArrayList<>();
        for (TestCase tc : problem.getTestCases()) {
            result.add(toResponse(tc));
        }
        result.sort(BY_NUMERIC_NAME);
        log.info("End list test cases for problem: {} (count={})", slug, result.size());
        return result;
    }

    /**
     * Numeric base name ("1", "2") -> visibility + contents, for correlating judge results back
     * to test cases. The judge reports {@code test_case} as the file stem, not a UUID, so this
     * is the only available join key.
     */
    public Map<String, TestCaseContext> contextByName(Problem problem) {
        Map<String, TestCaseContext> byName = new HashMap<>();
        for (TestCase tc : problem.getTestCases()) {
            String name = baseNameOf(tc.getInput());
            byName.put(name, tc.isSample()
                    ? new TestCaseContext(true,
                            readContentQuietly(tc.getInput()),
                            readContentQuietly(tc.getOutput()))
                    : TestCaseContext.hidden());
        }
        return byName;
    }

    /**
     * {@link #contextByName} for the problem with this id — deleted problems included, so old submissions
     * stay readable. Empty for an unknown id: every row then shows as hidden.
     *
     * <p>Deliberately not {@code @Transactional}: it runs inside its caller's transaction (the verdict
     * transaction among them), and a transactional proxy here would mark that transaction rollback-only
     * on any exception before {@code LocalProblemCatalog.sampleCases} can fail closed.
     */
    public Map<String, TestCaseContext> contextByProblemId(UUID problemId) {
        return problemRepository.findById(problemId).map(this::contextByName).orElse(Map.of());
    }

    @Transactional
    public TestCaseResponse addTestCase(String slug, MultipartFile input, MultipartFile output) throws IOException {
        log.info("Start add test case for problem: {}", slug);
        validateFile(input, "input");
        validateFile(output, "output");

        Problem problem = getProblemOrThrow(slug);
        int index = nextIndexFor(problem);

        byte[] inBytes = input.getBytes();
        byte[] outBytes = output.getBytes();
        TestCase created = persistTestCase(problem, slug, index, inBytes, outBytes);

        problemRepository.save(problem);
        publisher.publish(problem);

        log.info("End add test case for problem: {} as index {}", slug, index);
        return toResponse(created, inBytes, outBytes);
    }

    @Transactional
    public List<TestCaseResponse> importTestCases(String slug, MultipartFile zipFile) throws IOException {
        log.info("Start import test cases for problem: {}", slug);
        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("Zip file cannot be empty");
        }

        Problem problem = getProblemOrThrow(slug);

        Map<String, byte[]> extracted = FileUtil.extractZip(zipFile);
        Map<String, byte[]> inputFiles = new HashMap<>();
        Map<String, byte[]> outputFiles = new HashMap<>();
        extracted.forEach((name, content) -> {
            if (name.endsWith(AppProperties.INPUT_FILE_EXTENSION)) {
                inputFiles.put(name, content);
            } else if (name.endsWith(AppProperties.OUTPUT_FILE_EXTENSION)) {
                outputFiles.put(name, content);
            }
        });

        // Keep only inputs that have a matching output, re-numbered deterministically.
        List<String> pairedInputNames = inputFiles.keySet().stream()
                .filter(inName -> outputFiles.containsKey(outputNameFor(inName)))
                .sorted(numericAwareNameComparator())
                .toList();

        int index = nextIndexFor(problem);
        List<TestCase> newCases = new ArrayList<>();
        List<byte[]> newInputs = new ArrayList<>();
        List<byte[]> newOutputs = new ArrayList<>();

        for (String inName : pairedInputNames) {
            byte[] inBytes = inputFiles.get(inName);
            byte[] outBytes = outputFiles.get(outputNameFor(inName));
            newCases.add(persistTestCase(problem, slug, index, inBytes, outBytes));
            newInputs.add(inBytes);
            newOutputs.add(outBytes);
            index++;
        }

        problemRepository.save(problem);
        publisher.publish(problem);

        List<TestCaseResponse> responses = new ArrayList<>();
        for (int i = 0; i < newCases.size(); i++) {
            responses.add(toResponse(newCases.get(i), newInputs.get(i), newOutputs.get(i)));
        }
        log.info("End import test cases for problem: {} (added={})", slug, responses.size());
        return responses;
    }

    @Transactional
    public void deleteTestCase(String slug, UUID testCaseId) {
        log.info("Start delete test case {} for problem: {}", testCaseId, slug);
        TestCase tc = getOwnedTestCaseOrThrow(slug, testCaseId);
        Problem problem = tc.getProblem();
        if (problem.getTestCases().size() <= 1) {
            // A bundle needs at least one test case; replace the last one by adding first, then deleting.
            throw new IllegalArgumentException("A problem needs at least one test case: add another before deleting this one");
        }

        problem.getTestCases().remove(tc);
        testCaseRepository.delete(tc);
        publisher.publish(problem);
        // Only after the commit: if the transaction rolls back, the row still needs its files.
        AfterCommit.run(() -> {
            sources.deleteQuietly(tc.getInput());
            sources.deleteQuietly(tc.getOutput());
        });
        log.info("End delete test case {} for problem: {}", testCaseId, slug);
    }

    /**
     * Toggle whether a test case's input/output may be shown to users. Deliberately does NOT
     * republish the bundle — visibility is database-only metadata,
     * and changing the bundle hash would force a needless re-judge.
     */
    @Transactional
    public TestCaseResponse setSample(String slug, UUID testCaseId, boolean sample) {
        log.info("Start set sample={} for test case {} of problem: {}", sample, testCaseId, slug);
        TestCase tc = getOwnedTestCaseOrThrow(slug, testCaseId);

        tc.setSample(sample);
        testCaseRepository.save(tc);
        log.info("End set sample={} for test case {} of problem: {}", sample, testCaseId, slug);
        return toResponse(tc);
    }

    // --- persistence / IO helpers -------------------------------------------------------------

    private TestCase persistTestCase(Problem problem, String slug, int index, byte[] inBytes, byte[] outBytes) {
        String inRelative = relativePath(slug, index, AppProperties.INPUT_FILE_EXTENSION);
        String outRelative = relativePath(slug, index, AppProperties.OUTPUT_FILE_EXTENSION);

        sources.put(inRelative, inBytes);
        sources.put(outRelative, outBytes);

        TestCase testCase = TestCase.builder()
                .input(inRelative)
                .output(outRelative)
                .problem(problem)
                .build();
        problem.getTestCases().add(testCase);
        return testCase;
    }

    private Problem getProblemOrThrow(String slug) {
        return problemRepository.findLiveBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Problem not found with slug: " + slug));
    }

    /**
     * Looks up a test case by id and verifies it belongs to the {@code slug} problem. Shared by
     * every mutation that must not act on a test case smuggled in under the wrong problem's slug.
     */
    private TestCase getOwnedTestCaseOrThrow(String slug, UUID testCaseId) {
        TestCase tc = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Test case not found: " + testCaseId));

        // Ownership check: the test case must belong to the {slug} problem, which must not be deleted.
        if (tc.getProblem() == null || !slug.equals(tc.getProblem().getProblemSlug())
                || tc.getProblem().getStatus() == ProblemStatus.DELETED.getValue()) {
            throw new ResourceNotFoundException(
                    "Test case " + testCaseId + " not found for problem " + slug);
        }
        return tc;
    }

    private int nextIndexFor(Problem problem) {
        List<Integer> existing = problem.getTestCases().stream()
                .map(tc -> baseIndexOf(tc.getInput()))
                .filter(Objects::nonNull)
                .toList();
        return nextIndex(existing);
    }

    private String readContentQuietly(String relativePath) {
        try {
            return sources.get(relativePath)
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .orElseGet(() -> {
                        log.warn("Test case file not stored, returning empty content: {}", relativePath);
                        return "";
                    });
        } catch (RuntimeException e) {
            log.warn("Test case file not readable, returning empty content: {}", relativePath, e);
            return "";
        }
    }

    private static void validateFile(MultipartFile file, String field) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(field + " file cannot be empty");
        }
    }

    private TestCaseResponse toResponse(TestCase tc) {
        return TestCaseResponse.builder()
                .id(tc.getId())
                .name(baseNameOf(tc.getInput()))
                .input(readContentQuietly(tc.getInput()))
                .output(readContentQuietly(tc.getOutput()))
                .sample(tc.isSample())
                .build();
    }

    private static TestCaseResponse toResponse(TestCase tc, byte[] inBytes, byte[] outBytes) {
        return TestCaseResponse.builder()
                .id(tc.getId())
                .name(baseNameOf(tc.getInput()))
                .input(new String(inBytes, StandardCharsets.UTF_8))
                .output(new String(outBytes, StandardCharsets.UTF_8))
                .sample(tc.isSample())
                .build();
    }

    private static String relativePath(String slug, int index, String extension) {
        return String.format("%s/%d%s", slug, index, extension);
    }

    private static String outputNameFor(String inputName) {
        return inputName.substring(0, inputName.length() - AppProperties.INPUT_FILE_EXTENSION.length())
                + AppProperties.OUTPUT_FILE_EXTENSION;
    }

    private static Comparator<String> numericAwareNameComparator() {
        return Comparator.<String>comparingInt(TestCaseService::parseIntOrMax)
                .thenComparing(Comparator.naturalOrder());
    }

    // --- pure helpers (unit-tested) -----------------------------------------------------------

    /**
     * Next 1-based test case index: {@code max(existingIndices) + 1}, or {@code 1} when empty.
     */
    static int nextIndex(Collection<Integer> existingIndices) {
        return existingIndices.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    /**
     * Numeric base name of a path/file name, or {@code null} when it is not a plain integer.
     * e.g. {@code "a-plus-b/12.in"} -> {@code 12}, {@code "a-plus-b/sample.in"} -> {@code null}.
     */
    static Integer baseIndexOf(String pathOrName) {
        try {
            return Integer.parseInt(baseNameOf(pathOrName).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * File name without directory or extension. {@code "a-plus-b/12.in"} -> {@code "12"}.
     */
    static String baseNameOf(String pathOrName) {
        if (pathOrName == null) {
            return "";
        }
        String name = pathOrName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    private static int parseIntOrMax(String pathOrName) {
        Integer idx = baseIndexOf(pathOrName);
        return idx == null ? Integer.MAX_VALUE : idx;
    }
}
