package vn.thanhtuanle.testcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code stripped_output_md5} must equal what qduoj hashes for a correct answer: {@code md5(output.rstrip())}. */
class TestCaseBundleBuilderTest {

    private static String md5(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String strippedMd5(String expectedOutput) throws Exception {
        byte[] info = TestCaseBundleBuilder.info(List.of(new TestCaseBundleBuilder.SourcePair(
                "1.in", "x".getBytes(StandardCharsets.UTF_8), "1.out", expectedOutput.getBytes(StandardCharsets.UTF_8))));
        JsonNode node = new ObjectMapper().readTree(info);
        return node.get("test_cases").get("1").get("stripped_output_md5").asText();
    }

    @Test
    void crlfAndTrailingSpacesMatchAPlainOutput() throws Exception {
        assertThat(strippedMd5("1\r\n2  \r\n\r\n")).isEqualTo(md5("1\n2"));
    }

    @Test
    void onlyAsciiWhitespaceIsStrippedLikePythonBytesRstrip() throws Exception {
        assertThat(strippedMd5("5　\n")).isEqualTo(md5("5　"));
        assertThat(strippedMd5("5\u000b\u000c \t\n")).isEqualTo(md5("5"));
    }

    @Test
    void innerLineTrailingSpacesStillCount() throws Exception {
        assertThat(strippedMd5("1 \n2\n")).isEqualTo(md5("1 \n2"));
    }

    @Test
    void nonUtf8BytesHashExactly() throws Exception {
        byte[] raw = {(byte) 0xff, (byte) 0xfe, '1', '\n'};
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(new byte[]{(byte) 0xff, (byte) 0xfe, '1'})))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(TestCaseBundleBuilder.comparedOutput(raw))));
    }
}
