package vn.thanhtuanle.testcase;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestCaseBundleStoreTest {

    @Mock
    MinioClient minio;

    private MinioProperties props() {
        MinioProperties p = new MinioProperties();
        p.setBucket("test-cases");
        return p;
    }

    private static List<BundleFile> files() {
        return List.of(
                new BundleFile("1.in", "1 2\n".getBytes(StandardCharsets.UTF_8)),
                new BundleFile("1.out", "3\n".getBytes(StandardCharsets.UTF_8)),
                new BundleFile("info", "{\"test_case_number\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void contentHash_isStableTwelveHexAndIndependentOfInputOrder() {
        List<BundleFile> reversed = new ArrayList<>(files());
        Collections.reverse(reversed);

        String h1 = TestCaseBundleStore.contentHash(BundleFile.sortedByName(files()));
        String h2 = TestCaseBundleStore.contentHash(BundleFile.sortedByName(reversed));

        assertThat(h1).isEqualTo(h2).hasSize(12).matches("[0-9a-f]{12}");
    }

    @Test
    void publish_uploadsBundleAndPointsCurrentAtTheHash() throws Exception {
        // statObject throws NoSuchKey -> object absent -> bundle gets uploaded.
        when(minio.statObject(any(StatObjectArgs.class)))
                .thenThrow(noSuchKey());
        TestCaseBundleStore store = new TestCaseBundleStore(minio, props());

        String hash = store.publish("p", files());

        ArgumentCaptor<PutObjectArgs> puts = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio, org.mockito.Mockito.times(2)).putObject(puts.capture());
        List<String> objects = puts.getAllValues().stream().map(PutObjectArgs::object).toList();
        assertThat(objects).containsExactlyInAnyOrder("p/" + hash + ".zip", "p/CURRENT");
    }

    @Test
    void publish_skipsUploadWhenBundleAlreadyExists() throws Exception {
        when(minio.statObject(any(StatObjectArgs.class))).thenReturn(null); // present
        TestCaseBundleStore store = new TestCaseBundleStore(minio, props());

        store.publish("p", files());

        // Only CURRENT is (re)written; the immutable bundle upload is skipped.
        ArgumentCaptor<PutObjectArgs> puts = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(puts.capture());
        assertThat(puts.getValue().object()).isEqualTo("p/CURRENT");
    }

    @Test
    void publish_throwsWhenNoTestCaseFiles() throws Exception {
        TestCaseBundleStore store = new TestCaseBundleStore(minio, props());
        assertThatThrownBy(() -> store.publish("empty", List.of()))
                .isInstanceOf(TestCaseBundleException.class);
        verify(minio, never()).putObject(any());
    }

    @Test
    void findCurrentVersion_readsThePointerObject() throws Exception {
        GetObjectResponse resp = new GetObjectResponse(
                Headers.of(), "test-cases", null, "p/CURRENT",
                new ByteArrayInputStream("deadbeef1234".getBytes(StandardCharsets.UTF_8)));
        when(minio.getObject(any(GetObjectArgs.class))).thenReturn(resp);
        TestCaseBundleStore store = new TestCaseBundleStore(minio, props());

        assertThat(store.findCurrentVersion("p")).contains("deadbeef1234");
    }

    @Test
    void findCurrentVersion_isEmptyWhenNothingWasPublished() throws Exception {
        when(minio.getObject(any(GetObjectArgs.class))).thenThrow(noSuchKey());
        TestCaseBundleStore store = new TestCaseBundleStore(minio, props());

        assertThat(store.findCurrentVersion("p")).isEmpty();
    }

    @Test
    void findCurrentVersion_throwsWhenMinioCannotBeRead() throws Exception {
        when(minio.getObject(any(GetObjectArgs.class))).thenThrow(new java.io.IOException("connection refused"));
        TestCaseBundleStore store = new TestCaseBundleStore(minio, props());

        assertThatThrownBy(() -> store.findCurrentVersion("p")).isInstanceOf(TestCaseBundleException.class);
    }

    private static ErrorResponseException noSuchKey() throws Exception {
        return new ErrorResponseException(
                new io.minio.messages.ErrorResponse(
                        "NoSuchKey", "not found", "test-cases", "k", "/k", "req", "host"),
                new okhttp3.Response.Builder()
                        .request(new okhttp3.Request.Builder().url("http://localhost:9000/k").build())
                        .protocol(okhttp3.Protocol.HTTP_1_1).code(404).message("Not Found").build(),
                "trace");
    }
}
