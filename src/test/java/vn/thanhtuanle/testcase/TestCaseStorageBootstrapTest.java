package vn.thanhtuanle.testcase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** A fresh MinIO volume gets its bucket on startup; a MinIO that is not there yet does not stop the service. */
class TestCaseStorageBootstrapTest {

    private final TestCaseBundleStore bundleStore = mock(TestCaseBundleStore.class);

    @Test
    void theBucketIsCreatedOnStartup() {
        new TestCaseStorageBootstrap(bundleStore).run(new DefaultApplicationArguments());

        verify(bundleStore).ensureBucket();
    }

    @Test
    void anUnreachableMinioIsLoggedNotFatal() {
        doThrow(new TestCaseBundleException("connection refused")).when(bundleStore).ensureBucket();

        assertThatCode(() -> new TestCaseStorageBootstrap(bundleStore).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }
}
