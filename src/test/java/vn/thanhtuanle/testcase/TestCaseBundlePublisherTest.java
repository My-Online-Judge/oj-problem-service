package vn.thanhtuanle.testcase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TestCaseBundlePublisherTest {

    @Mock TestCaseBundleStore bundles;
    private final InMemoryTestCaseSourceStore sources = new InMemoryTestCaseSourceStore();
    private TestCaseBundlePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TestCaseBundlePublisher(sources, bundles);
    }

    private Problem problemWith(String slug, int... indices) {
        Problem problem = Problem.builder().problemSlug(slug).testCases(new ArrayList<>()).build();
        for (int i : indices) {
            problem.getTestCases().add(TestCase.builder()
                    .input(slug + "/" + i + ".in").output(slug + "/" + i + ".out").problem(problem).build());
        }
        return problem;
    }

    private void store(String path, String content) {
        sources.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBundleHoldsExactlyTheRowsFilesPlusInfo() {
        Problem problem = problemWith("a-plus-b", 1, 2);
        store("a-plus-b/1.in", "1 2\n");
        store("a-plus-b/1.out", "3\n");
        store("a-plus-b/2.in", "2 2\n");
        store("a-plus-b/2.out", "4\n");
        store("a-plus-b/3.in", "a file without a row\n");

        publisher.publish(problem);

        ArgumentCaptor<List<BundleFile>> files = ArgumentCaptor.forClass(List.class);
        verify(bundles).publish(eq("a-plus-b"), files.capture());
        assertThat(files.getValue()).extracting(BundleFile::name)
                .containsExactlyInAnyOrder("1.in", "1.out", "2.in", "2.out", "info");
    }

    @Test
    void aRowWhoseFileIsMissingFailsThePublishInsteadOfBeingLeftOut() {
        Problem problem = problemWith("a-plus-b", 1, 2);
        store("a-plus-b/1.in", "1 2\n");
        store("a-plus-b/1.out", "3\n");
        store("a-plus-b/2.in", "2 2\n");   // 2.out is missing

        assertThatThrownBy(() -> publisher.publish(problem))
                .isInstanceOf(TestCaseBundleException.class)
                .hasMessageContaining("a-plus-b/2.out");
        verify(bundles, never()).publish(anyString(), any());
    }

    @Test
    void aProblemWithoutTestCasesCannotBePublished() {
        assertThatThrownBy(() -> publisher.publish(problemWith("empty")))
                .isInstanceOf(TestCaseBundleException.class);
        verify(bundles, never()).publish(anyString(), any());
    }

    @Test
    void theHashIsComputedWithoutUploading() {
        Problem problem = problemWith("a-plus-b", 1);
        store("a-plus-b/1.in", "1 2\n");
        store("a-plus-b/1.out", "3\n");

        assertThat(publisher.bundleHash(problem)).matches("[0-9a-f]{12}");
        verify(bundles, never()).publish(anyString(), any());
    }
}
