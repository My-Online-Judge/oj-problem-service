package vn.thanhtuanle.problem.internal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** A slow MinIO must never hold a database connection: the rows are read in a transaction, the files after it. */
@SpringBootTest
@ActiveProfiles("test")
class SampleCasesTransactionTest {

    /** Records, for every file read, whether a transaction was open at that moment. */
    static class RecordingSources extends InMemoryTestCaseSourceStore {
        final List<Boolean> transactionOpenOnRead = new CopyOnWriteArrayList<>();

        @Override
        public Optional<byte[]> get(String path) {
            transactionOpenOnRead.add(TransactionSynchronizationManager.isActualTransactionActive());
            return super.get(path);
        }
    }

    @TestConfiguration
    static class Sources {
        @Bean
        @Primary
        RecordingSources recordingSources() {
            return new RecordingSources();
        }
    }

    @Autowired InternalProblemQueries queries;
    @Autowired ProblemRepository problems;
    @Autowired RecordingSources sources;
    @MockitoBean TestCaseBundleStore bundleStore;

    private UUID problemWith(String slug) {
        Problem p = Problem.builder().title("tx").problemSlug(slug).status(ProblemStatus.ACTIVE.getValue())
                .timeLimit(1000).memoryLimit(64L).testCases(new ArrayList<>()).build();
        p.getTestCases().add(TestCase.builder().input(slug + "/1.in").output(slug + "/1.out").sample(true).problem(p).build());
        p.getTestCases().add(TestCase.builder().input(slug + "/2.in").output(slug + "/2.out").sample(false).problem(p).build());
        // Sample 3's files were never stored (or MinIO lost them): it is still listed, with empty contents.
        p.getTestCases().add(TestCase.builder().input(slug + "/3.in").output(slug + "/3.out").sample(true).problem(p).build());
        sources.put(slug + "/1.in", "1 2\n".getBytes(StandardCharsets.UTF_8));
        sources.put(slug + "/1.out", "3\n".getBytes(StandardCharsets.UTF_8));
        return problems.save(p).getId();
    }

    @Test
    void sampleContentsAreReadWithNoTransactionOpen() {
        UUID id = problemWith("tx-" + UUID.randomUUID());
        sources.transactionOpenOnRead.clear();

        List<InternalProblemQueries.SampleCase> cases = queries.sampleCases(id);

        assertThat(cases).containsExactly(
                new InternalProblemQueries.SampleCase("1", "1 2\n", "3\n"),
                new InternalProblemQueries.SampleCase("3", "", ""));
        assertThat(sources.transactionOpenOnRead).hasSize(4).containsOnly(false);
    }
}
