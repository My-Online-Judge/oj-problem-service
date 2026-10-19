package vn.thanhtuanle.problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.thanhtuanle.oj.common.text.TextCaps;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TestCaseServiceContentCappedTest {

    private static final int MAX = TextCaps.SAMPLE_MAX_CHARS;

    @Mock ProblemRepository problemRepository;
    @Mock TestCaseRepository testCaseRepository;
    @Mock TestCaseBundlePublisher publisher;
    private final InMemoryTestCaseSourceStore sources = new InMemoryTestCaseSourceStore();
    private TestCaseService service;

    @BeforeEach
    void setUp() {
        service = new TestCaseService(problemRepository, testCaseRepository, sources, publisher);
    }

    private void store(String path, String text) {
        sources.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aShortFileIsReturnedWhole() {
        store("p/1.in", "1 2\n");

        assertThat(service.contentCapped("p/1.in", MAX)).isEqualTo("1 2\n");
    }

    @Test
    void a70000CharacterFileIsCappedAndMarked() {
        store("p/1.in", "a".repeat(70_000));

        assertThat(service.contentCapped("p/1.in", MAX))
                .isEqualTo("a".repeat(MAX) + TextCaps.TRUNCATION_MARK);
    }

    @Test
    void exactly65536CharactersIsNotMarked() {
        store("p/1.in", "a".repeat(MAX));

        assertThat(service.contentCapped("p/1.in", MAX)).isEqualTo("a".repeat(MAX));
    }

    @Test
    void aFileOfThreeByteCharactersLongerThanTheReadIsMarkedWithoutAReplacementChar() {
        store("p/1.in", "ệ".repeat(100_000)); // 300,000 bytes, more than the 4 * 65536 read

        String capped = service.contentCapped("p/1.in", MAX);

        assertThat(capped).doesNotContain("�").endsWith(TextCaps.TRUNCATION_MARK);
        assertThat(capped).isEqualTo("ệ".repeat(MAX) + TextCaps.TRUNCATION_MARK);
    }

    @Test
    void aMissingFileIsEmpty() {
        assertThat(service.contentCapped("p/nope.in", MAX)).isEmpty();
    }
}
