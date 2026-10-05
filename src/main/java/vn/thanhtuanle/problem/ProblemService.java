package vn.thanhtuanle.problem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.oj.common.web.payload.PageResponse;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.common.util.FileUtil;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.problem.dto.CreateProblemDto;
import vn.thanhtuanle.problem.dto.UpdateProblemDto;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;
import vn.thanhtuanle.problem.dto.ProblemStatisticProjection;
import vn.thanhtuanle.problem.dto.ProblemStatisticsInfo;
import vn.thanhtuanle.problem.mapper.ProblemMapper;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseSourceStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Arrays;

import vn.thanhtuanle.problem.dto.ProblemTagRow;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProblemService {

    private static final String SLUG_INDEX = "ux_problems_slug";

    private final ProblemRepository problemRepository;
    private final ProblemMapper problemMapper;
    private final TestCaseSourceStore sources;
    private final TestCaseBundlePublisher publisher;

    @Transactional
    public ProblemResponseDto createProblem(CreateProblemDto dto, MultipartFile zipFile) throws IOException {
        log.info("Starting createProblem logic for: {}", dto.getProblemSlug());

        validateCreateRequest(dto, zipFile);

        Problem problem = problemMapper.toEntity(dto);
        problem.setTestCases(new ArrayList<>());
        // Claim the slug before any test-case file is written: of two concurrent creates of one slug the unique index
        // lets one insert through, and the other fails here — before it could overwrite the winner's files.
        problem = claimSlug(problem);

        // Process files
        processTestCases(zipFile, problem);

        // Sort test cases by name
        problem.getTestCases().sort(Comparator.comparing(TestCase::getInput));

        Problem savedProblem = problemRepository.save(problem);
        publisher.publish(savedProblem);
        log.info("Problem created successfully with ID: {}", savedProblem.getId());
        return problemMapper.toDto(savedProblem);
    }

    private Problem claimSlug(Problem problem) {
        try {
            return problemRepository.saveAndFlush(problem);
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && SLUG_INDEX.equals(violation.getConstraintName())) {
                throw new ResourceAlreadyExistException("Problem slug already exists: " + problem.getProblemSlug());
            }
            throw e;
        }
    }

    private void validateCreateRequest(CreateProblemDto dto, MultipartFile zipFile) {
        rejectDeletedStatus(dto.getStatus());
        // Deleted problems count: their slug stays taken forever, because submissions keep the slug.
        if (problemRepository.existsByProblemSlug(dto.getProblemSlug())) {
            throw new ResourceAlreadyExistException("Problem slug already exists: " + dto.getProblemSlug());
        }

        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("Zip file cannot be empty");
        }
    }

    private void processTestCases(MultipartFile zipFile, Problem problem) throws IOException {
        // Extract zip to memory
        log.info("Extracting zip file for problem: {}", problem.getProblemSlug());
        Map<String, byte[]> extractedFiles = FileUtil.extractZip(zipFile);
        log.info("Extracted {} files from zip.", extractedFiles.size());

        // Filter files
        Map<String, byte[]> inputFiles = new HashMap<>();
        Map<String, byte[]> outputFiles = new HashMap<>();

        extractedFiles.forEach((name, content) -> {
            if (name.endsWith(AppProperties.INPUT_FILE_EXTENSION)) {
                inputFiles.put(name, content);
            } else if (name.endsWith(AppProperties.OUTPUT_FILE_EXTENSION)) {
                outputFiles.put(name, content);
            }
        });

        log.info("Validating and processing test case files for problem: {}", problem.getProblemSlug());
        matchAndSaveTestCases(inputFiles, outputFiles, problem);
    }

    private void matchAndSaveTestCases(Map<String, byte[]> inputFiles, Map<String, byte[]> outputFiles,
            Problem problem) {
        inputFiles.forEach((inName, inContent) -> {
            String baseName = inName.substring(0, inName.length() - AppProperties.INPUT_FILE_EXTENSION.length());
            String outName = baseName + AppProperties.OUTPUT_FILE_EXTENSION;

            if (outputFiles.containsKey(outName)) {
                log.info("Found valid test case pair: {} - {}", inName, outName);
                byte[] outContent = outputFiles.get(outName);

                String inputPath = String.format("%s/%s", problem.getProblemSlug(), new File(inName).getName());
                String outputPath = String.format("%s/%s", problem.getProblemSlug(), new File(outName).getName());
                sources.put(inputPath, inContent);
                sources.put(outputPath, outContent);

                TestCase testCase = TestCase.builder()
                        .input(inputPath)
                        .output(outputPath)
                        .problem(problem)
                        .build();
                problem.getTestCases().add(testCase);
            }
        });
    }

    public PageResponse<ProblemResponseDto> getProblems(int page, int size, String search, ProblemStatus status,
            Integer hardnessLevel) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(AppProperties.DEFAULT_SORT_BY).descending());

        Integer statusValue = status != null ? status.getValue() : null;
        Page<ProblemResponseDto> dtoPage = problemRepository
                .findProblemsWithStats(search, statusValue, hardnessLevel, pageable)
                .map(problemMapper::toDto);

        attachTags(dtoPage.getContent());
        return PageResponse.of(dtoPage);
    }

    private void attachTags(List<ProblemResponseDto> problems) {
        if (problems.isEmpty()) {
            return;
        }
        List<UUID> ids = problems.stream().map(ProblemResponseDto::getId).toList();
        Map<UUID, List<String>> tagsByProblem = problemRepository.findTagsByProblemIds(ids).stream()
                .collect(Collectors.groupingBy(
                        ProblemTagRow::getProblemId,
                        Collectors.mapping(ProblemTagRow::getTag, Collectors.toList())));
        problems.forEach(p -> p.setTags(tagsByProblem.getOrDefault(p.getId(), List.of())));
    }

    public ProblemResponseDto getProblemBySlug(String slug) {
        log.info("Start fetch problem details for slug: {}", slug);
        ProblemStatisticProjection projection = problemRepository.findByProblemSlugWithStats(slug)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Problem not found with slug: " + slug));

        ProblemResponseDto dto = problemMapper.toDto(projection);

        List<ProblemStatisticsInfo> rawStats = problemRepository.countSubmissionsByResult(projection.getId());

        log.info("Initializing submission result statistics for problem slug: {}", slug);
        Map<String, Integer> statisticInfo = Arrays.stream(SubmissionResult.values())
                .collect(Collectors.toMap(
                        result -> String.valueOf(result.getValue()),
                        result -> 0));

        log.info("Updating submission result statistics with actual counts for problem slug: {}", slug);
        rawStats.forEach(stat -> statisticInfo.put(
                String.valueOf(stat.getResult()),
                stat.getCount().intValue()));

        dto.setStatisticInfo(statisticInfo);

        log.info("End fetch problem details for slug: {}", slug);
        return dto;
    }

    @Transactional
    public ProblemResponseDto updateProblem(String slug, UpdateProblemDto dto) {
        log.info("Start update problem: {}", slug);
        rejectDeletedStatus(dto.getStatus());
        Problem problem = problemRepository.findLiveBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Problem not found with slug: " + slug));

        problem.setTitle(dto.getTitle());
        problem.setSubject(dto.getSubject());
        problem.setDescription(dto.getDescription());
        problem.setTimeLimit(dto.getTimeLimit());
        problem.setMemoryLimit((long) dto.getMemoryLimit());
        problem.setHardnessLevel(dto.getHardnessLevel());
        problem.setInputDescription(dto.getInputDescription());
        problem.setOutputDescription(dto.getOutputDescription());
        problem.setSampleInput(dto.getSampleInput());
        problem.setSampleOutput(dto.getSampleOutput());
        problem.setHint(dto.getHint());
        if (dto.getStatus() != null) {
            problem.setStatus(dto.getStatus().getValue());
        }

        Problem saved = problemRepository.save(problem);
        log.info("End update problem: {}", slug);
        return problemMapper.toDto(saved);
    }

    /**
     * Soft delete: the problem disappears from lists and detail pages and takes no new submissions,
     * but its row, test cases, bundles and submissions are kept, and its slug is never reused.
     */
    @Transactional
    public void deleteProblem(String slug) {
        log.info("Start delete problem: {}", slug);
        Problem problem = problemRepository.findLiveBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Problem not found with slug: " + slug));

        problem.setStatus(ProblemStatus.DELETED.getValue());
        problemRepository.save(problem);
        log.info("End delete problem: {}", slug);
    }

    private static void rejectDeletedStatus(ProblemStatus status) {
        if (status == ProblemStatus.DELETED) {
            throw new IllegalArgumentException("A problem is deleted with DELETE /problems/{slug}, not by its status");
        }
    }
}
