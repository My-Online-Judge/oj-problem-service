package vn.thanhtuanle.problem.internal;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetSampleTestCasesRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;
import vn.thanhtuanle.oj.common.grpc.problem.v1.SampleTestCase;
import vn.thanhtuanle.oj.common.grpc.problem.v1.SampleTestCases;
import vn.thanhtuanle.testcase.TestCaseBundleException;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.util.Optional;
import java.util.UUID;

/**
 * The internal API the submission side calls (port 9090, oj-net only, service token required). Status codes
 * are part of the contract: the caller's circuit breaker counts NOT_FOUND and FAILED_PRECONDITION as
 * answers, and retries only UNAVAILABLE.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProblemInternalGrpcService extends ProblemInternalGrpc.ProblemInternalImplBase {

    private final InternalProblemQueries queries;
    private final TestCaseBundleStore bundleStore;

    @Override
    public void getJudgeSpec(GetJudgeSpecRequest request, StreamObserver<JudgeSpec> responseObserver) {
        String slug = request.getProblemSlug();
        Optional<InternalProblemQueries.ProblemLimits> problem = queries.liveProblem(slug);
        if (problem.isEmpty()) {
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
        responseObserver.onNext(JudgeSpec.newBuilder()
                .setProblemId(p.id().toString())
                .setProblemSlug(p.slug())
                .setTimeLimitMs(p.timeLimitMs())
                .setMemoryLimitMb(p.memoryLimitMb())
                .setTestCaseVersion(version.get())
                .build());
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
}
