package vn.thanhtuanle.problem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.common.util.AfterCommit;
import vn.thanhtuanle.common.util.FileUtil;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.oj.common.text.TextCaps;
import vn.thanhtuanle.problem.dto.TestCaseContext;
import vn.thanhtuanle.problem.dto.TestCaseResponse;
import vn.thanhtuanle.problem.dto.TestCaseSummary;
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
 * Manages a problem's test cases after creation (list / read / add / update / import / delete). Files are kept in the
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

    @Transactional(readOnly = true)
    public List<TestCaseSummary> listTestCases(String slug) {
        log.info("Start list test cases for problem: {}", slug);
        Problem problem = getProblemOrThrow(slug);

        List<TestCase> ordered = new ArrayList<>(problem.getTestCases());
        ordered.sort(Comparator.comparingInt(tc -> parseIntOrMax(tc.getInput())));
        List<TestCaseSummary> result = new ArrayList<>();
        for (TestCase tc : ordered) {
            result.add(toSummary(tc, headQuietly(tc.getInput()), headQuietly(tc.getOutput())));
        }
        log.info("End list test cases for problem: {} (count={})", slug, result.size());
        return result;
    }

    @Transactional(readOnly = true)
    public TestCaseResponse getTestCase(String slug, UUID testCaseId) {
        Problem problem = getProblemOrThrow(slug);
        return toResponse(findIn(problem, testCaseId));
    }

    /** Rewrites both source files of a test case (name and sample flag stay) and republishes the bundle. */
    @Transactional
    public TestCaseSummary updateTestCase(String slug, UUID testCaseId, MultipartFile input, MultipartFile output)
            throws IOException {
        log.info("Start update test case {} for problem: {}", testCaseId, slug);
        validateFile(input, "input");
        validateFile(output, "output");

        Problem problem = getProblemForUpdateOrThrow(slug);
        TestCase tc = findIn(problem, testCaseId);

        byte[] inBytes = input.getBytes();
        byte[] outBytes = output.getBytes();
        sources.put(tc.getInput(), inBytes);
        sources.put(tc.getOutput(), outBytes);
        // Published while the problem row is still locked, so a concurrent add cannot slip a stale bundle in between.
        publisher.publish(problem);

        log.info("End update test case {} for problem: {}", testCaseId, slug);
        return toSummary(tc, headOf(inBytes), headOf(outBytes));
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

    /** A sample test case's stored files; {@code name} is the judge's name for it (the file stem). */
    public record SampleFiles(String name, String inputPath, String outputPath) {
    }

    /**
     * The sample test cases of the problem with this id — deleted problems included, so old submissions stay readable —
     * without their contents, which {@link #contentCapped} reads from storage after this transaction. Empty for an unknown id.
     */
    @Transactional(readOnly = true)
    public List<SampleFiles> sampleFiles(UUID problemId) {
        return problemRepository.findById(problemId)
                .map(problem -> problem.getTestCases().stream()
                        .filter(TestCase::isSample)
                        .map(tc -> new SampleFiles(baseNameOf(tc.getInput()), tc.getInput(), tc.getOutput()))
                        .toList())
                .orElse(List.of());
    }

    /**
     * A stored test-case file as text of at most {@code maxChars} characters, marked when cut; empty when it is missing
     * or unreadable (logged). Reads only the first {@code 4 * maxChars} bytes (a UTF-8 character is at most 4), so a
     * huge file never comes into memory whole.
     */
    public String contentCapped(String path, int maxChars) {
        TestCaseSourceStore.Head head = headQuietly(path, 4 * maxChars);
        String text = decodePrefix(head);
        return TextCaps.cap(text, maxChars, head.size() > head.prefix().length);
    }

    @Transactional
    public TestCaseSummary addTestCase(String slug, MultipartFile input, MultipartFile output) throws IOException {
        log.info("Start add test case for problem: {}", slug);
        validateFile(input, "input");
        validateFile(output, "output");

        Problem problem = getProblemForUpdateOrThrow(slug);
        int index = nextIndexFor(problem);
        problem.setTestCaseHighWater(index);

        byte[] inBytes = input.getBytes();
        byte[] outBytes = output.getBytes();
        TestCase created = persistTestCase(problem, slug, index, inBytes, outBytes);

        problemRepository.saveAndFlush(problem); // a constraint failure surfaces before CURRENT moves
        publisher.publish(problem);

        log.info("End add test case for problem: {} as index {}", slug, index);
        return toSummary(created, headOf(inBytes), headOf(outBytes));
    }

    @Transactional
    public List<TestCaseSummary> importTestCases(String slug, MultipartFile zipFile) throws IOException {
        log.info("Start import test cases for problem: {}", slug);
        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("Zip file cannot be empty");
        }

        Problem problem = getProblemForUpdateOrThrow(slug);

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

        if (!newCases.isEmpty()) {
            problem.setTestCaseHighWater(index - 1);
        }
        problemRepository.saveAndFlush(problem); // a constraint failure surfaces before CURRENT moves
        publisher.publish(problem);

        List<TestCaseSummary> responses = new ArrayList<>();
        for (int i = 0; i < newCases.size(); i++) {
            responses.add(toSummary(newCases.get(i), headOf(newInputs.get(i)), headOf(newOutputs.get(i))));
        }
        log.info("End import test cases for problem: {} (added={})", slug, responses.size());
        return responses;
    }

    @Transactional
    public void deleteTestCase(String slug, UUID testCaseId) {
        log.info("Start delete test case {} for problem: {}", testCaseId, slug);
        Problem problem = getProblemForUpdateOrThrow(slug);
        TestCase tc = findIn(problem, testCaseId);
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
    public TestCaseSummary setSample(String slug, UUID testCaseId, boolean sample) {
        log.info("Start set sample={} for test case {} of problem: {}", sample, testCaseId, slug);
        TestCase tc = getOwnedTestCaseOrThrow(slug, testCaseId);

        tc.setSample(sample);
        testCaseRepository.save(tc);
        log.info("End set sample={} for test case {} of problem: {}", sample, testCaseId, slug);
        return toSummary(tc, headQuietly(tc.getInput()), headQuietly(tc.getOutput()));
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

    /** The live problem, row-locked until the transaction ends: test-case changes of one problem run one at a time. */
    private Problem getProblemForUpdateOrThrow(String slug) {
        return problemRepository.findBySlugForUpdate(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Problem not found with slug: " + slug));
    }

    private static TestCase findIn(Problem problem, UUID testCaseId) {
        return problem.getTestCases().stream()
                .filter(tc -> testCaseId.equals(tc.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Test case " + testCaseId + " not found for problem " + problem.getProblemSlug()));
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
        return nextIndex(existing, problem.getTestCaseHighWater());
    }

    private static final int PREVIEW_BYTES = 1024;

    private TestCaseSummary toSummary(TestCase tc, TestCaseSourceStore.Head in, TestCaseSourceStore.Head out) {
        return new TestCaseSummary(tc.getId(), baseNameOf(tc.getInput()), tc.isSample(),
                in.size(), out.size(), previewOf(in), previewOf(out));
    }

    private static TestCaseSourceStore.Head headOf(byte[] bytes) {
        return new TestCaseSourceStore.Head(bytes.length,
                java.util.Arrays.copyOf(bytes, Math.min(bytes.length, PREVIEW_BYTES)));
    }

    /** Size and first bytes of a stored file; zero-size when missing or unreadable (logged). */
    private TestCaseSourceStore.Head headQuietly(String relativePath) {
        return headQuietly(relativePath, PREVIEW_BYTES);
    }

    private TestCaseSourceStore.Head headQuietly(String relativePath, int maxBytes) {
        try {
            return sources.head(relativePath, maxBytes).orElseGet(() -> {
                log.warn("Test case file not stored, listing it as empty: {}", relativePath);
                return new TestCaseSourceStore.Head(0, new byte[0]);
            });
        } catch (RuntimeException e) {
            log.warn("Test case file not readable, listing it as empty: {}", relativePath, e);
            return new TestCaseSourceStore.Head(0, new byte[0]);
        }
    }

    /** Preview of the first bytes; a multi-byte character cut by the byte limit is dropped, not shown as garbage. */
    private static String previewOf(TestCaseSourceStore.Head head) {
        return TestCasePreviews.preview(decodePrefix(head));
    }

    /** The prefix as UTF-8 text; a multi-byte character cut by the byte limit is dropped, not decoded as garbage. */
    private static String decodePrefix(TestCaseSourceStore.Head head) {
        byte[] p = head.prefix();
        int end = p.length;
        if (head.size() > p.length) {
            while (end > 0 && (p[end - 1] & 0xC0) == 0x80) {
                end--; // continuation bytes of a cut character
            }
            if (end > 0 && (p[end - 1] & 0x80) != 0) {
                end--; // its lead byte
            }
        }
        return new String(p, 0, end, StandardCharsets.UTF_8);
    }

    private byte[] readBytesQuietly(String relativePath) {
        try {
            return sources.get(relativePath).orElseGet(() -> {
                log.warn("Test case file not stored, returning empty content: {}", relativePath);
                return new byte[0];
            });
        } catch (RuntimeException e) {
            log.warn("Test case file not readable, returning empty content: {}", relativePath, e);
            return new byte[0];
        }
    }

    private String readContentQuietly(String relativePath) {
        return new String(readBytesQuietly(relativePath), StandardCharsets.UTF_8);
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
     * Next 1-based test case index: one above the larger of the existing indices and the problem's high-water mark
     * (the highest number ever used), so a deleted case's number is never reused.
     */
    static int nextIndex(Collection<Integer> existingIndices, int highWater) {
        return Math.max(highWater, existingIndices.stream().mapToInt(Integer::intValue).max().orElse(0)) + 1;
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
