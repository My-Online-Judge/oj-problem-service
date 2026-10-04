package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.common.exception.ResourceAlreadyExistException;
import vn.thanhtuanle.common.exception.ResourceNotFoundException;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.problem.dto.CreateProblemDto;
import vn.thanhtuanle.problem.dto.UpdateProblemDto;
import vn.thanhtuanle.problem.mapper.ProblemMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemSoftDeleteTest {

    @Mock ProblemRepository problemRepository;
    @Mock ProblemMapper problemMapper;
    @InjectMocks ProblemService problemService;

    private static Problem live(String slug) {
        Problem p = Problem.builder().problemSlug(slug).title("t").build();
        p.setStatus(ProblemStatus.ACTIVE.getValue());
        return p;
    }

    @Test
    void deletingMarksTheProblemDeletedAndKeepsTheRow() {
        Problem problem = live("a-plus-b");
        when(problemRepository.findLiveBySlug("a-plus-b")).thenReturn(Optional.of(problem));

        problemService.deleteProblem("a-plus-b");

        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.DELETED.getValue());
        verify(problemRepository).save(problem);
        verify(problemRepository, never()).delete(any(Problem.class));
    }

    @Test
    void deletingAMissingOrAlreadyDeletedProblemIs404() {
        when(problemRepository.findLiveBySlug("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> problemService.deleteProblem("gone"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatingADeletedProblemIs404() {
        when(problemRepository.findLiveBySlug("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> problemService.updateProblem("gone", UpdateProblemDto.builder().build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theStatusCannotBeSetToDeletedByAnUpdate() {
        UpdateProblemDto dto = UpdateProblemDto.builder().status(ProblemStatus.DELETED).build();

        assertThatThrownBy(() -> problemService.updateProblem("a-plus-b", dto))
                .isInstanceOf(IllegalArgumentException.class);
        verify(problemRepository, never()).save(any());
    }

    @Test
    void aProblemCannotBeCreatedDeleted() {
        CreateProblemDto dto = new CreateProblemDto();
        dto.setProblemSlug("new-one");
        dto.setStatus(ProblemStatus.DELETED);

        assertThatThrownBy(() -> problemService.createProblem(dto, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(problemRepository, never()).save(any());
    }

    @Test
    void theSlugOfADeletedProblemCannotBeReused() {
        // existsByProblemSlug counts deleted problems (pinned against the database in ProblemRepositorySoftDeleteTest).
        when(problemRepository.existsByProblemSlug("a-plus-b")).thenReturn(true);
        CreateProblemDto dto = new CreateProblemDto();
        dto.setProblemSlug("a-plus-b");

        assertThatThrownBy(() -> problemService.createProblem(dto, null))
                .isInstanceOf(ResourceAlreadyExistException.class);
    }
}
