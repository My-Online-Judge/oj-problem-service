package vn.thanhtuanle.testcase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a problem's test cases into the files of its judge bundle: every input/output pair plus the
 * {@code info} file judge-server reads. For the same files the result is byte-identical to what the old
 * directory-based code produced (pinned by {@code TestCaseBundleBuilderGoldenTest}), so moving the files
 * to MinIO changed no bundle hash.
 */
final class TestCaseBundleBuilder {

    /** One test case: file names as they appear in the bundle ({@code 1.in}, {@code 1.out}) and contents. */
    record SourcePair(String inputName, byte[] input, String outputName, byte[] output) {
    }

    /** Version of the bundle format; bump it when the bundle's content rules change so bundles are published again. */
    static final String FORMAT_VERSION = "2";

    private static final ObjectMapper JSON = new ObjectMapper();

    private TestCaseBundleBuilder() {
    }

    static List<BundleFile> build(List<SourcePair> pairs) {
        List<BundleFile> files = new ArrayList<>();
        for (SourcePair pair : pairs) {
            files.add(new BundleFile(pair.inputName(), pair.input()));
            files.add(new BundleFile(pair.outputName(), pair.output()));
        }
        files.add(new BundleFile("info", info(pairs)));
        return files;
    }

    static byte[] info(List<SourcePair> pairs) {
        Map<String, Map<String, Object>> testCases = new TreeMap<>(); // keyed by name, in string order
        for (SourcePair pair : pairs) {
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("input_name", pair.inputName());
            tc.put("input_size", pair.input().length);
            tc.put("output_name", pair.outputName());
            tc.put("output_size", pair.output().length);
            tc.put("output_md5", md5Hex(pair.output()));
            tc.put("stripped_output_md5", md5Hex(comparedOutput(pair.output())));
            testCases.put(pair.inputName().replace(".in", ""), tc);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("test_case_number", testCases.size());
        info.put("spj", false);
        info.put("test_cases", testCases);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(info).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the info file", e);
        }
    }

    /**
     * The bytes judge-server compares a user's output with: {@code \r\n} becomes {@code \n}, then trailing
     * {@code 0x20 0x09 0x0A 0x0D 0x0B 0x0C} go. That is Python's {@code bytes.rstrip()}, which qduoj applies to
     * the user's output; Java's {@code strip()} would also remove U+3000 and the like. Works on bytes, never on a
     * decoded String, so a non-UTF-8 output hashes exactly.
     */
    static byte[] comparedOutput(byte[] output) {
        byte[] out = new byte[output.length];
        int n = 0;
        for (int i = 0; i < output.length; i++) {
            if (output[i] == '\r' && i + 1 < output.length && output[i + 1] == '\n') {
                continue; // drop the CR of a CRLF
            }
            out[n++] = output[i];
        }
        while (n > 0 && isAsciiSpace(out[n - 1])) {
            n--;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static boolean isAsciiSpace(byte b) {
        return b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0B || b == 0x0C;
    }

    private static String md5Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
