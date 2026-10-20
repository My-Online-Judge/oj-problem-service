package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import vn.thanhtuanle.oj.common.web.payload.PageResponse;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;
import vn.thanhtuanle.problem.mapper.ProblemMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** A client cannot ask for an unbounded page: {@code size} is clamped to 100, never a 400. */
@ExtendWith(MockitoExtension.class)
class PageSizeCapTest {

    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private ProblemMapper problemMapper;

    @InjectMocks
    private ProblemService problemService;

    @Test
    void sizeAboveTheCapIsClampedTo100() {
        // Echo the pageable the service built, so the response reports what the repository was asked for.
        when(problemRepository.findProblemsWithStats(isNull(), isNull(), isNull(), isNull(), anyBoolean(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.<Pageable>getArgument(5), 0));

        PageResponse<ProblemResponseDto> result = problemService.getProblems(0, 500, null, null, null, null, false);

        assertThat(result.getSize()).isEqualTo(100);
    }
}
