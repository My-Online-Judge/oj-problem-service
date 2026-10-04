package vn.thanhtuanle.problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Test-case files go to the source store, and every change to the set of test cases republishes. */
@ExtendWith(MockitoExtension.class)
class TestCaseServicePublishTest {

    @Mock ProblemRepository problemRepository;
    @Mock TestCaseRepository testCaseRepository;
    @Mock TestCaseBundlePublisher publisher;
    private final InMemoryTestCaseSourceStore sources = new InMemoryTestCaseSourceStore();
    private TestCaseService service;

    @BeforeEach
    void setUp() {
        service = new TestCaseService(problemRepository, testCaseRepository, sources, publisher);
    }

    private Problem problemWith(int... indices) {
        Problem problem = Problem.builder().problemSlug("a-plus-b").testCases(new ArrayList<>()).build();
        for (int i : indices) {
            TestCase tc = TestCase.builder()
                    .input("a-plus-b/" + i + ".in").output("a-plus-b/" + i + ".out").problem(problem).build();
            tc.setId(UUID.randomUUID());
            problem.getTestCases().add(tc);
            sources.put(tc.getInput(), ("in " + i).getBytes(StandardCharsets.UTF_8));
            sources.put(tc.getOutput(), ("out " + i).getBytes(StandardCharsets.UTF_8));
        }
        return problem;
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile(name, name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void addingATestCaseStoresItsFilesAndRepublishes() throws Exception {
        Problem problem = problemWith(1);
        when(problemRepository.findLiveBySlug("a-plus-b")).thenReturn(Optional.of(problem));

        service.addTestCase("a-plus-b", file("input", "2 3\n"), file("output", "5\n"));

        assertThat(new String(sources.files.get("a-plus-b/2.in"), StandardCharsets.UTF_8)).isEqualTo("2 3\n");
        assertThat(new String(sources.files.get("a-plus-b/2.out"), StandardCharsets.UTF_8)).isEqualTo("5\n");
        assertThat(problem.getTestCases()).hasSize(2);
        verify(publisher).publish(problem);
    }

    @Test
    void deletingATestCaseRepublishesWithoutItAndRemovesItsFiles() {
        Problem problem = problemWith(1, 2);
        TestCase second = problem.getTestCases().get(1);
        when(testCaseRepository.findById(second.getId())).thenReturn(Optional.of(second));

        service.deleteTestCase("a-plus-b", second.getId());

        verify(testCaseRepository).delete(second);
        verify(publisher).publish(argThat(p -> p.getTestCases().equals(List.of(problem.getTestCases().get(0)))));
        assertThat(sources.files).containsOnlyKeys("a-plus-b/1.in", "a-plus-b/1.out");
    }

    @Test
    void theLastTestCaseCannotBeDeleted() {
        Problem problem = problemWith(1);
        TestCase only = problem.getTestCases().get(0);
        when(testCaseRepository.findById(only.getId())).thenReturn(Optional.of(only));

        assertThatThrownBy(() -> service.deleteTestCase("a-plus-b", only.getId()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(testCaseRepository, never()).delete(any());
        verifyNoInteractions(publisher);
        assertThat(sources.files).containsOnlyKeys("a-plus-b/1.in", "a-plus-b/1.out");
    }
}
