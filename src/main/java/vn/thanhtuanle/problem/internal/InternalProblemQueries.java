package vn.thanhtuanle.problem.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.oj.common.text.TextCaps;
import vn.thanhtuanle.problem.CheckerService;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.problem.TestCaseService;
import vn.thanhtuanle.testcase.TestCaseSourceStore;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the internal API reads: limits in one read-only transaction; samples as rows in one, then their files outside
 * it; a checker's source outside any.
 */
@Service
@RequiredArgsConstructor
public class InternalProblemQueries {

    private final ProblemRepository problemRepository;
    private final TestCaseService testCaseService;
    private final TestCaseSourceStore sources;

    /**
     * A problem's identity and limits, as the judge needs them; {@code active} is false for an INACTIVE problem, and
     * {@code spj} is true for one judged by a checker ({@link #checkerSource}).
     */
    public record ProblemLimits(UUID id, String slug, int timeLimitMs, long memoryLimitMb, boolean active,
                                boolean spj) {
    }

    /** A test case users may see; {@code name} is the judge's name for it (the file stem). */
    public record SampleCase(String name, String input, String expectedOutput) {
    }

    /** Empty for an unknown or deleted slug. */
    @Transactional(readOnly = true)
    public Optional<ProblemLimits> liveProblem(String slug) {
        return problemRepository.findLiveBySlug(slug)
                .map(p -> new ProblemLimits(p.getId(), p.getProblemSlug(), p.getTimeLimit(), p.getMemoryLimit(),
                        p.getStatus() == ProblemStatus.ACTIVE.getValue(), p.isSpj()));
    }

    /**
     * The problem's checker source, or empty when none is stored. Read with no transaction, like the sample files, so a
     * slow MinIO never holds a database connection.
     *
     * @throws vn.thanhtuanle.testcase.TestCaseBundleException when the storage cannot be read
     */
    public Optional<String> checkerSource(String slug) {
        return sources.get(CheckerService.checkerPath(slug)).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /** Those of these slugs anyone may see (ACTIVE), in one query: unknown, INACTIVE and deleted ones drop out. */
    @Transactional(readOnly = true)
    public List<String> visibleSlugs(Collection<String> slugs) {
        return slugs.isEmpty() ? List.of() : problemRepository.findActiveSlugsIn(slugs);
    }

    /**
     * The sample cases only, by name; deleted problems included, so old submissions stay readable. The rows are read in
     * their own read-only transaction and the files afterwards, so a slow MinIO never holds a database connection.
     */
    public List<SampleCase> sampleCases(UUID problemId) {
        return testCaseService.sampleFiles(problemId).stream()
                .map(f -> new SampleCase(f.name(), testCaseService.contentCapped(f.inputPath(), TextCaps.SAMPLE_MAX_CHARS),
                        testCaseService.contentCapped(f.outputPath(), TextCaps.SAMPLE_MAX_CHARS)))
                .sorted(Comparator.comparing(SampleCase::name))
                .toList();
    }
}
