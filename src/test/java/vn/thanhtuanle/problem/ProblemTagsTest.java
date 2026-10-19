package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemTagsTest {

    @Test
    void normalizesCaseSpaceAndDuplicates() {
        assertThat(ProblemTags.normalize(List.of(" DP ", "graph", "dp", "Two-Pointers")))
                .containsExactly("dp", "graph", "two-pointers");
    }

    @Test
    void rejectsBadCharacters() {
        for (String bad : List.of("a b", "a_b", "-a", "a-", "a--b", "", "  ", "dấu", "a/b")) {
            assertThatThrownBy(() -> ProblemTags.normalize(List.of(bad)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid tag");
        }
    }

    @Test
    void rejectsOver32() {
        assertThat(ProblemTags.normalize(List.of("a".repeat(32)))).hasSize(1);
        assertThatThrownBy(() -> ProblemTags.normalize(List.of("a".repeat(33))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid tag");
    }

    @Test
    void rejectsMoreThan10() {
        List<String> eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "t" + i).toList();
        assertThat(ProblemTags.normalize(eleven.subList(0, 10))).hasSize(10);
        assertThatThrownBy(() -> ProblemTags.normalize(eleven)).isInstanceOf(IllegalArgumentException.class);
        // duplicates collapse first, so 11 entries with one repeat is fine
        assertThat(ProblemTags.normalize(java.util.stream.Stream.concat(eleven.subList(0, 10).stream(), java.util.stream.Stream.of("T0")).toList())).hasSize(10);
    }

    @Test
    void nullMeansUnchanged() {
        assertThat(ProblemTags.normalize(null)).isNull();
        assertThat(ProblemTags.normalize(List.of())).isEmpty();
    }
}
