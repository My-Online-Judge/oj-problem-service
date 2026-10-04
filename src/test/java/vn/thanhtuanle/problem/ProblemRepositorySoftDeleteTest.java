package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.problem.dto.ProblemStatisticProjection;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Deleted problems are hidden everywhere a user looks, yet their slug stays taken. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProblemRepositorySoftDeleteTest {

    @Autowired ProblemRepository problemRepository;

    private Problem save(String slug, ProblemStatus status) {
        return problemRepository.save(Problem.builder()
                .title("soft-delete " + slug).problemSlug(slug).status(status.getValue()).build());
    }

    @Test
    void aDeletedProblemIsNotFoundByItsSlug() {
        String slug = "sd-" + UUID.randomUUID();
        save(slug, ProblemStatus.DELETED);

        assertThat(problemRepository.findLiveBySlug(slug)).isEmpty();
        assertThat(problemRepository.findByProblemSlugWithStats(slug)).isEmpty();
    }

    @Test
    void anInactiveProblemIsStillFoundByItsSlug() {
        String slug = "sd-" + UUID.randomUUID();
        save(slug, ProblemStatus.INACTIVE);

        assertThat(problemRepository.findLiveBySlug(slug)).isPresent();
    }

    @Test
    void aDeletedProblemKeepsItsSlugTaken() {
        String slug = "sd-" + UUID.randomUUID();
        save(slug, ProblemStatus.DELETED);

        assertThat(problemRepository.existsByProblemSlug(slug)).isTrue();
    }

    @Test
    void aDeletedProblemIsLeftOutOfTheListWhateverTheFilters() {
        String marker = "sd-list-" + UUID.randomUUID();
        save(marker + "-live", ProblemStatus.ACTIVE);
        save(marker + "-gone", ProblemStatus.DELETED);

        // search matches the title of both; no status filter
        assertThat(problemRepository.findProblemsWithStats(marker, null, null, PageRequest.of(0, 10))
                .map(ProblemStatisticProjection::getProblemSlug))
                .containsExactly(marker + "-live");
        // asking for DELETED explicitly still returns nothing
        assertThat(problemRepository.findProblemsWithStats(marker, ProblemStatus.DELETED.getValue(), null,
                PageRequest.of(0, 10))).isEmpty();
    }
}
