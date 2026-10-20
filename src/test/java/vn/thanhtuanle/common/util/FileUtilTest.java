package vn.thanhtuanle.common.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUtilTest {

    private static final int MIB = 1 << 20;
    private static final long SLACK = 64 * 1024;

    private static byte[] zip(String[] names, int[] sizes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            byte[] chunk = new byte[MIB];
            for (int i = 0; i < names.length; i++) {
                zos.putNextEntry(new ZipEntry(names[i]));
                int left = sizes[i];
                while (left > 0) {
                    int n = Math.min(left, chunk.length);
                    zos.write(chunk, 0, n);
                    left -= n;
                }
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    void rejectsMoreThan1000Entries() throws IOException {
        String[] names = new String[1001];
        int[] sizes = new int[1001];
        for (int i = 0; i < names.length; i++) {
            names[i] = i + ".in";
            sizes[i] = 1;
        }
        byte[] z = zip(names, sizes);
        assertThatThrownBy(() -> FileUtil.extractZip(new ByteArrayInputStream(z)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");
    }

    @Test
    void rejectsEntryOver50MiB() throws IOException {
        byte[] z = zip(new String[] {"big.in"}, new int[] {51 * MIB});
        long read = readAndExpectRejection(z, "50");
        assertThat(read).isLessThan(FileUtil.MAX_ENTRY_BYTES + SLACK);
    }

    @Test
    void rejectsTotalOver100MiB() throws IOException {
        String[] names = new String[3];
        int[] sizes = new int[3];
        for (int i = 0; i < 3; i++) {
            names[i] = i + ".in";
            sizes[i] = 41 * MIB;
        }
        byte[] z = zip(names, sizes);
        long read = readAndExpectRejection(z, "100");
        assertThat(read).isLessThan(FileUtil.MAX_TOTAL_BYTES + SLACK);
    }

    /** Returns the decompressed bytes the extractor had read when it gave up (reported through its counter seam). */
    private static long readAndExpectRejection(byte[] z, String limitText) {
        long[] counted = new long[1];
        assertThatThrownBy(() -> FileUtil.extractZip(new ByteArrayInputStream(z), n -> counted[0] += n))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(limitText);
        return counted[0];
    }

    @Test
    void rejectsDuplicateFlattenedNames() throws IOException {
        byte[] z = zip(new String[] {"a/1.in", "b/1.in"}, new int[] {1, 1});
        assertThatThrownBy(() -> FileUtil.extractZip(new ByteArrayInputStream(z)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1.in");
    }

    /** Some Windows tools (PowerShell 5.1's Compress-Archive) write backslashes: they are folders too. */
    @Test
    void flattensBackslashFolders() throws IOException {
        byte[] z = zip(new String[] {"pkg\\problem.json", "pkg\\testcases\\1.in"}, new int[] {1, 1});

        assertThat(FileUtil.extractZip(new ByteArrayInputStream(z))).containsOnlyKeys("problem.json", "1.in");

        byte[] mixed = zip(new String[] {"a\\1.in", "b/1.in"}, new int[] {1, 1});
        assertThatThrownBy(() -> FileUtil.extractZip(new ByteArrayInputStream(mixed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate zip entry name: 1.in");
    }

    /** PowerShell 5.1's Compress-Archive writes an empty folder as an entry whose name ends in a backslash. */
    @Test
    void skipsFolderEntriesEndingInABackslash() throws IOException {
        byte[] z = zip(new String[] {"pkg\\empty\\", "pkg\\other\\", "pkg\\testcases\\", "pkg\\testcases\\1.in"},
                new int[] {0, 0, 0, 1});

        assertThat(FileUtil.extractZip(new ByteArrayInputStream(z))).containsOnlyKeys("1.in");
    }

    @Test
    void rejectsParentTraversal() throws IOException {
        byte[] z = zip(new String[] {"../1.in"}, new int[] {1});
        assertThatThrownBy(() -> FileUtil.extractZip(new ByteArrayInputStream(z)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsoluteName() throws IOException {
        byte[] z = zip(new String[] {"/1.in"}, new int[] {1});
        assertThatThrownBy(() -> FileUtil.extractZip(new ByteArrayInputStream(z)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A cut-off upload ends inside an entry's compressed data: a 400, not the catch-all's 500. */
    @Test
    void rejectsATruncatedZip() throws IOException {
        byte[] data = new byte[64 * 1024];
        new java.util.Random(7).nextBytes(data); // incompressible, so the cut lands in the entry's data
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("1.in"));
            zos.write(data);
            zos.closeEntry();
        }
        byte[] truncated = java.util.Arrays.copyOf(bos.toByteArray(), bos.size() / 2);

        assertThatThrownBy(() -> FileUtil.extractZip(new ByteArrayInputStream(truncated)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The upload is not a valid zip file");
    }

    @Test
    void extractsNormalZip() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("1.in"));
            zos.write("1 2\n".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("1.out"));
            zos.write("3\n".getBytes());
            zos.closeEntry();
        }
        Map<String, byte[]> out = FileUtil.extractZip(new ByteArrayInputStream(bos.toByteArray()));
        assertThat(out).containsOnlyKeys("1.in", "1.out");
        assertThat(out.get("1.in")).isEqualTo("1 2\n".getBytes());
        assertThat(out.get("1.out")).isEqualTo("3\n".getBytes());
    }
}
