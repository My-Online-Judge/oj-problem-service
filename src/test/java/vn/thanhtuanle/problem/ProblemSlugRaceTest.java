package vn.thanhtuanle.problem;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.problem.dto.CreateProblemDto;
import vn.thanhtuanle.problem.mapper.ProblemMapper;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseSourceStore;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Two creates of one slug at once: both pass existsByProblemSlug (neither has committed), so only the unique index
 * decides. The loser must be refused like a taken slug (ResourceAlreadyExistException, 400 through oj-common's
 * handler) — and must fail before writing any test-case file, or it would overwrite the winner's files in MinIO.
 */
@ExtendWith(MockitoExtension.class)
class ProblemSlugRaceTest {

    @Mock ProblemRepository problemRepository;
    @Mock ProblemMapper problemMapper;
    @Mock TestCaseSourceStore sources;
    @Mock TestCaseBundlePublisher publisher;
    @InjectMocks ProblemService problemService;

    private final MockMultipartFile zip = new MockMultipartFile("file", "cases.zip", "application/zip", new byte[] {1});

    private static CreateProblemDto dto(String slug) {
        CreateProblemDto dto = new CreateProblemDto();
        dto.setProblemSlug(slug);
        return dto;
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate key", new SQLException("duplicate key", "23505"), constraint));
    }

    @Test
    void theLoserOfAConcurrentCreateIsRefusedBeforeWritingAnyFile() {
        Problem problem = Problem.builder().problemSlug("a-plus-b").title("t").build();
        when(problemMapper.toEntity(any())).thenReturn(problem);
        when(problemRepository.saveAndFlush(problem)).thenThrow(violationOf("ux_problems_slug"));

        assertThatThrownBy(() -> problemService.createProblem(dto("a-plus-b"), zip))
                .isInstanceOf(ResourceAlreadyExistException.class)
                .hasMessageContaining("a-plus-b");
        verifyNoInteractions(sources, publisher);
    }

    @Test
    void anotherIntegrityViolationIsNotReportedAsATakenSlug() {
        Problem problem = Problem.builder().problemSlug("a-plus-b").title("t").build();
        when(problemMapper.toEntity(any())).thenReturn(problem);
        when(problemRepository.saveAndFlush(problem)).thenThrow(violationOf("t_problems_pkey"));

        assertThatThrownBy(() -> problemService.createProblem(dto("a-plus-b"), zip))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
