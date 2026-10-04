package vn.thanhtuanle.problem.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.problem.dto.CreateProblemDto;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;
import vn.thanhtuanle.problem.dto.ProblemStatisticProjection;

@Mapper(componentModel = "spring")
public interface ProblemMapper {
    ProblemResponseDto toDto(Problem problem);

    @Mapping(target = "totalSubmission", expression = "java(projection.getTotalSubmission())")
    @Mapping(target = "acceptedSubmission", expression = "java(projection.getAcceptedSubmission())")
    ProblemResponseDto toDto(ProblemStatisticProjection projection);

    @Mapping(target = "testCases", ignore = true)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    Problem toEntity(CreateProblemDto dto);

    default ProblemStatus map(int value) {
        return ProblemStatus.fromValue(value);
    }

    default int map(ProblemStatus status) {
        return status != null ? status.getValue() : 0;
    }
}
