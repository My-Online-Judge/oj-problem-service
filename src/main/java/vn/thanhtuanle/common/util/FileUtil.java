package vn.thanhtuanle.common.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public class FileUtil {

    public static final int MAX_ENTRIES = 1000;
    public static final long MAX_ENTRY_BYTES = 50L << 20;
    public static final long MAX_TOTAL_BYTES = 100L << 20;

    public static Map<String, byte[]> extractZip(MultipartFile zipFile) throws IOException {
        try (InputStream in = zipFile.getInputStream()) {
            return extractZip(in);
        }
    }

    public static Map<String, byte[]> extractZip(InputStream in) throws IOException {
        return extractZip(in, n -> { });
    }

    /**
     * Reads the zip with hard bounds on entry count, per-entry and total decompressed size, counting bytes as they
     * are inflated so a zip bomb is rejected without being read in full. {@code onRead} sees each chunk read
     * (a seam for tests). A zip that cannot be read (cut off, an encrypted entry, a malformed header) is the
     * uploader's fault, so it is an {@link IllegalArgumentException} (400), not a server error.
     */
    static Map<String, byte[]> extractZip(InputStream in, LongConsumer onRead) throws IOException {
        try {
            return readZip(in, onRead);
        } catch (ZipException | EOFException e) {
            throw new IllegalArgumentException("The upload is not a valid zip file", e);
        }
    }

    private static Map<String, byte[]> readZip(InputStream in, LongConsumer onRead) throws IOException {
        Map<String, byte[]> extracted = new HashMap<>();
        long total = 0;
        int entries = 0;
        ZipInputStream zis = new ZipInputStream(in);
        byte[] buffer = new byte[8192];
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) {
                continue;
            }
            if (++entries > MAX_ENTRIES) {
                throw new IllegalArgumentException("Zip has more than " + MAX_ENTRIES + " entries");
            }
            String raw = entry.getName();
            if (raw.startsWith("/") || raw.startsWith("\\") || hasParentSegment(raw)) {
                throw new IllegalArgumentException("Unsafe zip entry name: " + raw);
            }
            String name = new File(raw).getName();
            if (extracted.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate zip entry name: " + name);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            long entryBytes = 0;
            int len;
            while ((len = zis.read(buffer)) > 0) {
                onRead.accept(len);
                entryBytes += len;
                total += len;
                if (entryBytes > MAX_ENTRY_BYTES) {
                    throw new IllegalArgumentException("Zip entry " + name + " exceeds " + (MAX_ENTRY_BYTES >> 20) + " MiB");
                }
                if (total > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException("Zip exceeds " + (MAX_TOTAL_BYTES >> 20) + " MiB uncompressed");
                }
                bos.write(buffer, 0, len);
            }
            extracted.put(name, bos.toByteArray());
        }
        return extracted;
    }

    private static boolean hasParentSegment(String name) {
        for (String segment : name.split("[/\\\\]")) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
