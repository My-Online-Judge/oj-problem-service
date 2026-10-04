package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestCaseServiceTest {

    @Test
    void nextIndex_existingIndices_returnsMaxPlusOne() {
        assertEquals(6, TestCaseService.nextIndex(List.of(1, 2, 5)));
    }

    @Test
    void nextIndex_empty_returnsOne() {
        assertEquals(1, TestCaseService.nextIndex(List.of()));
    }

    @Test
    void nextIndex_unorderedSingle_returnsNext() {
        assertEquals(4, TestCaseService.nextIndex(List.of(3, 1, 2)));
    }

    @Test
    void baseIndexOf_numericPath_returnsIndex() {
        assertEquals(1, TestCaseService.baseIndexOf("a-plus-b/1.in"));
        assertEquals(12, TestCaseService.baseIndexOf("a-plus-b/12.out"));
        assertEquals(7, TestCaseService.baseIndexOf("7.in"));
    }

    @Test
    void baseIndexOf_nonNumericOrNull_returnsNull() {
        assertNull(TestCaseService.baseIndexOf("a-plus-b/sample.in"));
        assertNull(TestCaseService.baseIndexOf(null));
        assertNull(TestCaseService.baseIndexOf("a-plus-b/"));
    }

    @Test
    void baseNameOf_stripsDirectoryAndExtension() {
        assertEquals("1", TestCaseService.baseNameOf("a-plus-b/1.in"));
        assertEquals("3", TestCaseService.baseNameOf("3.out"));
    }

    @Test
    void nextIndex_fromPaths_ignoresNonNumeric() {
        // {1,2,5} + a non-numeric name -> non-numeric ignored -> next is 6.
        List<Integer> indices = List.of("s/1.in", "s/2.in", "s/5.in", "s/sample.in").stream()
                .map(TestCaseService::baseIndexOf)
                .filter(Objects::nonNull)
                .toList();
        assertEquals(6, TestCaseService.nextIndex(indices));
    }
}
