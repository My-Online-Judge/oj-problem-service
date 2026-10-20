package vn.thanhtuanle.problem.internal;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.thanhtuanle.contest.ContestService;
import vn.thanhtuanle.oj.common.grpc.ProblemRpcReasons;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetSampleTestCasesRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ListVisibleSlugsRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ListVisibleSlugsResponse;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;
import vn.thanhtuanle.oj.common.grpc.problem.v1.SampleTestCase;
import vn.thanhtuanle.oj.common.grpc.problem.v1.SampleTestCases;
import vn.thanhtuanle.problem.CheckerService;
import vn.thanhtuanle.testcase.TestCaseBundleException;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * The internal API the submission side calls (port 9090, oj-net only; GrpcServerConfig decides who may call).
 * Status codes are part of the contract: the caller's circuit breaker counts NOT_FOUND and FAILED_PRECONDITION
 * as answers, and retries only UNAVAILABLE. A contest submission that its contest cannot take is a FAILED_PRECONDITION
 * carrying {@link ProblemRpcReasons#contestNotRunning()}, so the caller tells it apart from a missing bundle. A problem
 * judged by a checker whose source is missing is a FAILED_PRECONDITION too.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProblemInternalGrpcService extends ProblemInternalGrpc.ProblemInternalImplBase {

    /** The most slugs one ListVisibleSlugs call may ask about. */
    static final int MAX_VISIBLE_SLUGS = 100;

    private final InternalProblemQueries queries;
    private final TestCaseBundleStore bundleStore;
    private final ContestService contests;

    @Override
    public void getJudgeSpec(GetJudgeSpecRequest request, StreamObserver<JudgeSpec> responseObserver) {
        String slug = request.getProblemSlug();
        // A named contest is checked first: the answer is the same whether or not the problem exists.
        Optional<UUID> contestId = Optional.empty();
        if (!request.getContestSlug().isEmpty()) {
            contestId = contests.runningContestId(request.getContestSlug(), slug);
            if (contestId.isEmpty()) {
                responseObserver.onError(Status.FAILED_PRECONDITION.withDescription("Contest is not running")
                        .asRuntimeException(ProblemRpcReasons.contestNotRunning()));
                return;
            }
        }
        Optional<InternalProblemQueries.ProblemLimits> problem = queries.liveProblem(slug);
        // An INACTIVE problem is served to its editors and rejudges (allow_inactive), and inside its running contest.
        if (problem.isEmpty() || (!problem.get().active() && !request.getAllowInactive() && contestId.isEmpty())) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Problem not found: " + slug).asRuntimeException());
            return;
        }
        Optional<String> version;
        try {
            version = bundleStore.findCurrentVersion(slug);
        } catch (TestCaseBundleException e) {
            log.warn("Test-case storage unreadable while serving the judge spec of {}: {}", slug, e.getMessage());
            responseObserver.onError(Status.UNAVAILABLE.withDescription("Test-case storage unavailable").asRuntimeException());
            return;
        }
        if (version.isEmpty()) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("No test-case bundle published for problem: " + slug).asRuntimeException());
            return;
        }
        InternalProblemQueries.ProblemLimits p = problem.get();
        JudgeSpec.Builder spec = JudgeSpec.newBuilder()
                .setProblemId(p.id().toString())
                .setProblemSlug(p.slug())
                .setTimeLimitMs(p.timeLimitMs())
                .setMemoryLimitMb(p.memoryLimitMb())
                .setTestCaseVersion(version.get());
        contestId.ifPresent(id -> spec.setContestId(id.toString()));
        if (p.spj()) {
            Optional<String> checker;
            try {
                checker = queries.checkerSource(slug);
            } catch (TestCaseBundleException e) {
                log.warn("Checker storage unreadable while serving the judge spec of {}: {}", slug, e.getMessage());
                responseObserver.onError(Status.UNAVAILABLE.withDescription("Test-case storage unavailable")
                        .asRuntimeException());
                return;
            }
            if (checker.isEmpty()) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Checker source missing for problem: " + slug).asRuntimeException());
                return;
            }
            // The version names exactly the source sent, not the row's: an upload stores its source before it commits
            // its version, and qduoj keeps each sandbox's compiled checker by version.
            spec.setSpjSource(checker.get())
                    .setSpjVersion(CheckerService.versionOf(checker.get().getBytes(StandardCharsets.UTF_8)));
        }
        responseObserver.onNext(spec.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getSampleTestCases(GetSampleTestCasesRequest request, StreamObserver<SampleTestCases> responseObserver) {
        UUID problemId;
        try {
            problemId = UUID.fromString(request.getProblemId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("problem_id is not a UUID: " + request.getProblemId()).asRuntimeException());
            return;
        }
        SampleTestCases.Builder cases = SampleTestCases.newBuilder();
        queries.sampleCases(problemId).forEach(c -> cases.addCases(SampleTestCase.newBuilder()
                .setName(c.name()).setInput(c.input()).setExpectedOutput(c.expectedOutput())));
        responseObserver.onNext(cases.build());
        responseObserver.onCompleted();
    }

    @Override
    public void listVisibleSlugs(ListVisibleSlugsRequest request,
                                 StreamObserver<ListVisibleSlugsResponse> responseObserver) {
        if (request.getSlugsCount() > MAX_VISIBLE_SLUGS) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("At most " + MAX_VISIBLE_SLUGS + " slugs per call").asRuntimeException());
            return;
        }
        responseObserver.onNext(ListVisibleSlugsResponse.newBuilder()
                .addAllSlugs(queries.visibleSlugs(request.getSlugsList())).build());
        responseObserver.onCompleted();
    }
}
