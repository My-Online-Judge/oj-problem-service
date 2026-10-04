package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.problem.dto.TestCaseResponse;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseSourceStore;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TestCaseServiceSampleToggleTest {

    @Mock ProblemRepository problemRepository;
    @Mock TestCaseRepository testCaseRepository;
    @Mock TestCaseSourceStore sources;
    @Mock TestCaseBundlePublisher publisher;
    @InjectMocks TestCaseService service;

    @Test
    void setsFlag_withoutRepublishingBundle() {
        UUID id = UUID.randomUUID();
        Problem problem = new Problem();
        problem.setProblemSlug("a-plus-b");
        TestCase tc = TestCase.builder().input("a-plus-b/1.in").output("a-plus-b/1.out")
                .problem(problem).build();
        tc.setId(id);
        when(testCaseRepository.findById(id)).thenReturn(Optional.of(tc));

        TestCaseResponse response = service.setSample("a-plus-b", id, true);

        assertThat(tc.isSample()).isTrue();
        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.isSample()).isTrue();
        // The bundle hash must not change: no re-judge churn from a visibility toggle.
        verifyNoInteractions(publisher);
    }

    @Test
    void slugMismatch_throwsAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        Problem problem = new Problem();
        problem.setProblemSlug("a-plus-b");
        TestCase tc = TestCase.builder().input("a-plus-b/1.in").output("a-plus-b/1.out")
                .problem(problem).build();
        tc.setId(id);
        when(testCaseRepository.findById(id)).thenReturn(Optional.of(tc));

        assertThatThrownBy(() -> service.setSample("other-slug", id, true))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(testCaseRepository, never()).save(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void nullProblem_throwsAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        TestCase tc = TestCase.builder().input("a-plus-b/1.in").output("a-plus-b/1.out").build();
        tc.setId(id);
        when(testCaseRepository.findById(id)).thenReturn(Optional.of(tc));

        assertThatThrownBy(() -> service.setSample("a-plus-b", id, true))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(testCaseRepository, never()).save(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void aTestCaseOfADeletedProblemIsNotFound() {
        UUID id = UUID.randomUUID();
        Problem problem = new Problem();
        problem.setProblemSlug("a-plus-b");
        problem.setStatus(ProblemStatus.DELETED.getValue());
        TestCase tc = TestCase.builder().input("a-plus-b/1.in").output("a-plus-b/1.out")
                .problem(problem).build();
        tc.setId(id);
        when(testCaseRepository.findById(id)).thenReturn(Optional.of(tc));

        assertThatThrownBy(() -> service.setSample("a-plus-b", id, true))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void theTestCasesOfADeletedProblemAreNotListed() {
        when(problemRepository.findLiveBySlug("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listTestCases("gone"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
