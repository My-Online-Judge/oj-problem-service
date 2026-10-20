package vn.thanhtuanle.problem.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.oj.common.text.TextCaps;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.problem.TestCaseService;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What the internal API reads: limits in one read-only transaction; samples as rows in one, then their files outside it. */
@Service
@RequiredArgsConstructor
public class InternalProblemQueries {

    private final ProblemRepository problemRepository;
    private final TestCaseService testCaseService;

    /** A problem's identity and limits, as the judge needs them; {@code active} is false for an INACTIVE problem. */
    public record ProblemLimits(UUID id, String slug, int timeLimitMs, long memoryLimitMb, boolean active) {
    }

    /** A test case users may see; {@code name} is the judge's name for it (the file stem). */
    public record SampleCase(String name, String input, String expectedOutput) {
    }

    /** Empty for an unknown or deleted slug. */
    @Transactional(readOnly = true)
    public Optional<ProblemLimits> liveProblem(String slug) {
        return problemRepository.findLiveBySlug(slug)
                .map(p -> new ProblemLimits(p.getId(), p.getProblemSlug(), p.getTimeLimit(), p.getMemoryLimit(),
                        p.getStatus() == ProblemStatus.ACTIVE.getValue()));
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
