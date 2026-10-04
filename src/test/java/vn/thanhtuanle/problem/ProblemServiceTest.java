package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.common.payload.PageResponse;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;
import vn.thanhtuanle.problem.dto.ProblemStatisticProjection;
import vn.thanhtuanle.problem.dto.ProblemTagRow;
import vn.thanhtuanle.problem.mapper.ProblemMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ProblemServiceTest {

    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private ProblemMapper problemMapper;

    @InjectMocks
    private ProblemService problemService;

    private static ProblemTagRow tagRow(UUID id, String tag) {
        return new ProblemTagRow() {
            public UUID getProblemId() { return id; }
            public String getTag() { return tag; }
        };
    }

    @Test
    void getProblems_forwardsHardnessLevel_andAttachesTagsGroupedByProblem() {
        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

        ProblemStatisticProjection proj1 = mock(ProblemStatisticProjection.class);
        ProblemStatisticProjection proj2 = mock(ProblemStatisticProjection.class);
        ProblemResponseDto dto1 = ProblemResponseDto.builder().id(id1).build();
        ProblemResponseDto dto2 = ProblemResponseDto.builder().id(id2).build();

        Page<ProblemStatisticProjection> page =
                new PageImpl<>(List.of(proj1, proj2), PageRequest.of(0, 10), 2);
        when(problemRepository.findProblemsWithStats(eq("gcd"), eq(1), eq(2), any(Pageable.class)))
                .thenReturn(page);
        when(problemMapper.toDto(proj1)).thenReturn(dto1);
        when(problemMapper.toDto(proj2)).thenReturn(dto2);
        when(problemRepository.findTagsByProblemIds(List.of(id1, id2)))
                .thenReturn(List.of(tagRow(id1, "Math"), tagRow(id1, "Greedy")));

        PageResponse<ProblemResponseDto> result =
                problemService.getProblems(0, 10, "gcd", ProblemStatus.ACTIVE, 2);

        verify(problemRepository).findProblemsWithStats(eq("gcd"), eq(1), eq(2), any(Pageable.class));
        assertEquals(List.of("Math", "Greedy"), result.getData().get(0).getTags());
        assertTrue(result.getData().get(1).getTags().isEmpty());
    }

    @Test
    void getProblems_emptyPage_doesNotQueryTags() {
        Page<ProblemStatisticProjection> empty =
                new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(problemRepository.findProblemsWithStats(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(empty);

        PageResponse<ProblemResponseDto> result =
                problemService.getProblems(0, 10, null, null, null);

        assertTrue(result.getData().isEmpty());
        verify(problemRepository, never()).findTagsByProblemIds(anyList());
    }
}
