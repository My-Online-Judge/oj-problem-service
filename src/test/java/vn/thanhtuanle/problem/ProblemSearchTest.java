package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.payload.PageResponse;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;
import vn.thanhtuanle.support.PostgresTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The problem list's search matches the title, the description and the slug (PROBLEM_FILTER, on Postgres: ILIKE). */
@Transactional // each test rolls back: PostgresTest shares one database with ProblemSchemaTest, which expects no rows
class ProblemSearchTest extends PostgresTest {

    @Autowired ProblemRepository problems;
    @Autowired ProblemService problemService;

    private Problem save(String slug, String title, ProblemStatus status) {
        return problems.save(Problem.builder().title(title).problemSlug(slug).description("plain statement")
                .status(status.getValue()).timeLimit(1000).memoryLimit(256L).build());
    }

    private PageResponse<ProblemResponseDto> search(String q, int size, boolean includeInactive) {
        return problemService.getProblems(0, size, q, null, null, null, includeInactive);
    }

    @Test
    void aSlugFragmentFindsTheProblem() {
        save("sum-of-two", "Tổng hai số", ProblemStatus.ACTIVE);

        assertThat(search("of-two", 10, false).getData())
                .extracting(ProblemResponseDto::getProblemSlug)
                .contains("sum-of-two");
    }

    @Test
    void theTitleStillMatches() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        save("title-match-x", "Tổng hai số " + marker, ProblemStatus.ACTIVE);

        assertThat(search("hai số " + marker, 10, false).getData())
                .extracting(ProblemResponseDto::getProblemSlug)
                .containsExactly("title-match-x");
    }

    /** % and _ in the search are literal text, not LIKE wildcards: "sum_of" does not find "sum-of-two". */
    @Test
    void wildcardsInTheSearchAreLiteral() {
        String marker = "wild" + UUID.randomUUID().toString().substring(0, 8);
        save(marker + "-sum-of-two", "plain", ProblemStatus.ACTIVE);
        save(marker + "-sum_of_one", "plain", ProblemStatus.ACTIVE);
        save(marker + "-half", "100% sure", ProblemStatus.ACTIVE);
        save(marker + "-back", "a\\b", ProblemStatus.ACTIVE);

        assertThat(search("sum_of", 10, false).getData()).extracting(ProblemResponseDto::getProblemSlug)
                .containsExactly(marker + "-sum_of_one");
        assertThat(search(marker + "-sum_of", 10, false).getTotalElements()).isEqualTo(1);
        assertThat(search("%", 10, false).getData()).extracting(ProblemResponseDto::getProblemSlug)
                .containsExactly(marker + "-half");
        assertThat(search("_", 10, false).getData()).extracting(ProblemResponseDto::getProblemSlug)
                .containsExactly(marker + "-sum_of_one");
        assertThat(search("a\\b", 10, false).getData()).extracting(ProblemResponseDto::getProblemSlug)
                .containsExactly(marker + "-back");
    }

    @Test
    void theCountMatchesThePage() {
        String marker = "cnt" + UUID.randomUUID().toString().substring(0, 8);
        save(marker + "-a", "a", ProblemStatus.ACTIVE);
        save(marker + "-b", "b", ProblemStatus.ACTIVE);
        save(marker + "-c", "c", ProblemStatus.ACTIVE);

        PageResponse<ProblemResponseDto> page = search(marker, 2, false);

        assertThat(page.getData()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void inactiveStaysHiddenFromTheCallerWithoutIncludeInactive() {
        String marker = "hid" + UUID.randomUUID().toString().substring(0, 8);
        save(marker + "-live", "live", ProblemStatus.ACTIVE);
        save(marker + "-draft", "draft", ProblemStatus.INACTIVE);

        PageResponse<ProblemResponseDto> pub = search(marker, 10, false);
        assertThat(pub.getData()).extracting(ProblemResponseDto::getProblemSlug).containsExactly(marker + "-live");
        assertThat(pub.getTotalElements()).isEqualTo(1);

        assertThat(search(marker, 10, true).getData()).extracting(ProblemResponseDto::getProblemSlug)
                .containsExactlyInAnyOrder(marker + "-live", marker + "-draft");
    }
}
