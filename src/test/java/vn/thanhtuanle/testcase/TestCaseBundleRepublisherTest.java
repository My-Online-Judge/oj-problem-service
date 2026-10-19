package vn.thanhtuanle.testcase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.support.TransactionOperations;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.problem.ProblemRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** After a bundle-format change every live problem whose bundle hash moved is republished, once. */
@ExtendWith(MockitoExtension.class)
class TestCaseBundleRepublisherTest {

    @Mock TestCaseBundleStore store;
    @Mock TestCaseBundlePublisher publisher;
    @Mock ProblemRepository problems;
    private TestCaseBundleRepublisher republisher;

    @BeforeEach
    void setUp() {
        republisher = new TestCaseBundleRepublisher(store, publisher, problems,
                TransactionOperations.withoutTransaction(), true);
    }

    private Problem live(String slug) {
        Problem p = Problem.builder().problemSlug(slug).testCases(new java.util.ArrayList<>()).build();
        p.getTestCases().add(TestCase.builder().input(slug + "/1.in").output(slug + "/1.out").problem(p).build());
        when(problems.findBySlugForUpdate(slug)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void aChangedBundleIsRepublishedAndTheMarkerWritten() {
        Problem a = live("a");
        Problem b = live("b");
        when(problems.findLiveSlugs()).thenReturn(List.of("a", "b"));
        when(store.readText(TestCaseBundleStore.FORMAT_MARKER_KEY)).thenReturn(Optional.of("1"));
        when(publisher.bundleHash(a)).thenReturn("old-a");
        when(publisher.bundleHash(b)).thenReturn("new-b");
        when(store.findCurrentVersion("a")).thenReturn(Optional.of("old-a"));
        when(store.findCurrentVersion("b")).thenReturn(Optional.of("old-b"));

        assertThat(republisher.republishIfFormatChanged()).isEqualTo(1);

        verify(publisher).publish(b);
        verify(publisher, never()).publish(a);
        verify(store).writeText(".bundle-format", "2");
    }

    @Test
    void aCurrentMarkerSkipsThePass() {
        when(store.readText(TestCaseBundleStore.FORMAT_MARKER_KEY)).thenReturn(Optional.of("2"));

        assertThat(republisher.republishIfFormatChanged()).isZero();

        verifyNoInteractions(publisher, problems);
        verify(store, never()).writeText(anyString(), anyString());
    }

    @Test
    void aFailingProblemLeavesTheMarkerUnwritten() {
        Problem a = live("a");
        Problem b = live("b");
        when(problems.findLiveSlugs()).thenReturn(List.of("a", "b"));
        when(store.readText(TestCaseBundleStore.FORMAT_MARKER_KEY)).thenReturn(Optional.empty());
        when(publisher.bundleHash(a)).thenThrow(new TestCaseBundleException("file missing"));
        when(publisher.bundleHash(b)).thenReturn("new-b");
        when(store.findCurrentVersion("b")).thenReturn(Optional.empty());

        assertThat(republisher.republishIfFormatChanged()).isEqualTo(1);

        verify(publisher).publish(b);
        verify(store, never()).writeText(anyString(), anyString());
    }

    @Test
    void onlyLiveProblemsAreConsidered() {
        when(store.readText(TestCaseBundleStore.FORMAT_MARKER_KEY)).thenReturn(Optional.empty());
        when(problems.findLiveSlugs()).thenReturn(List.of());

        assertThat(republisher.republishIfFormatChanged()).isZero();

        verify(problems).findLiveSlugs();
        verify(problems, never()).findAll();
        verify(store).writeText(".bundle-format", "2");
    }

    @Test
    void theRunnerDoesNothingWhenSwitchedOff() throws Exception {
        TestCaseBundleRepublisher off = new TestCaseBundleRepublisher(store, publisher, problems,
                TransactionOperations.withoutTransaction(), false);

        off.run(new DefaultApplicationArguments());

        verifyNoInteractions(store, publisher, problems);
    }
}
