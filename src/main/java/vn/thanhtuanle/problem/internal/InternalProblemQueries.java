package vn.thanhtuanle.problem.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.problem.TestCaseService;

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

    /** A problem's identity and limits, as the judge needs them. */
    public record ProblemLimits(UUID id, String slug, int timeLimitMs, long memoryLimitMb) {
    }

    /** A test case users may see; {@code name} is the judge's name for it (the file stem). */
    public record SampleCase(String name, String input, String expectedOutput) {
    }

    /** Empty for an unknown or deleted slug. */
    @Transactional(readOnly = true)
    public Optional<ProblemLimits> liveProblem(String slug) {
        return problemRepository.findLiveBySlug(slug)
                .map(p -> new ProblemLimits(p.getId(), p.getProblemSlug(), p.getTimeLimit(), p.getMemoryLimit()));
    }

    /**
     * The sample cases only, by name; deleted problems included, so old submissions stay readable. The rows are read in
     * their own read-only transaction and the files afterwards, so a slow MinIO never holds a database connection.
     */
    public List<SampleCase> sampleCases(UUID problemId) {
        return testCaseService.sampleFiles(problemId).stream()
                .map(f -> new SampleCase(f.name(), testCaseService.content(f.inputPath()), testCaseService.content(f.outputPath())))
                .sorted(Comparator.comparing(SampleCase::name))
                .toList();
    }
}
