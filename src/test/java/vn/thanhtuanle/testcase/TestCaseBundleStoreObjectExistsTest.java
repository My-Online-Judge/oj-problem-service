package vn.thanhtuanle.testcase;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestCaseBundleStoreObjectExistsTest {

    private MinioProperties props() {
        MinioProperties p = new MinioProperties();
        p.setBucket("test-cases");
        return p;
    }

    @Test
    void hasBundle_returnsFalse_onNoSuchKey() throws Exception {
        MinioClient client = mock(MinioClient.class);
        ErrorResponse er = mock(ErrorResponse.class);
        when(er.code()).thenReturn("NoSuchKey");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(er);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(ex);

        TestCaseBundleStore store = new TestCaseBundleStore(client, props());
        assertThat(store.hasBundle("some-slug")).isFalse();
    }

    @Test
    void hasBundle_rethrows_onAccessDenied() throws Exception {
        MinioClient client = mock(MinioClient.class);
        ErrorResponse er = mock(ErrorResponse.class);
        when(er.code()).thenReturn("AccessDenied");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(er);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(ex);

        TestCaseBundleStore store = new TestCaseBundleStore(client, props());
        assertThatThrownBy(() -> store.hasBundle("some-slug"))
                .isInstanceOf(TestCaseBundleException.class);
    }
}
