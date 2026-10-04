package vn.thanhtuanle.testcase;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCaseBundleStorePruneTest {

    private MinioProperties props(int retention) {
        MinioProperties p = new MinioProperties();
        p.setBucket("test-cases");
        p.setBundleRetention(retention);
        return p;
    }

    /** Build a mock Item; higher `order` == more recent lastModified. */
    private static Item itemAt(String name, int order) {
        Item i = mock(Item.class);
        when(i.objectName()).thenReturn(name);
        when(i.lastModified()).thenReturn(ZonedDateTime.of(2026, 1, 1, 0, 0, order, 0, ZoneOffset.UTC));
        return i;
    }

    @Test
    void keepsCurrentPlusMostRecent_deletesRest() throws Exception {
        MinioClient client = mock(MinioClient.class);
        Item cur = itemAt("simple/cur.zip", 3);
        Item recent = itemAt("simple/recent.zip", 2);
        Item old = itemAt("simple/old.zip", 1);
        when(client.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(new Result<>(recent), new Result<>(cur), new Result<>(old)));

        TestCaseBundleStore store = new TestCaseBundleStore(client, props(2));
        store.pruneOldBundles("simple", "cur");

        verify(client).removeObject(argThat(a -> a.object().equals("simple/old.zip")));
        verify(client, never()).removeObject(argThat(a -> a.object().equals("simple/cur.zip")));
        verify(client, never()).removeObject(argThat(a -> a.object().equals("simple/recent.zip")));
    }

    @Test
    void alwaysKeepsCurrent_evenWhenOldestByTime() throws Exception {
        MinioClient client = mock(MinioClient.class);
        Item cur = itemAt("simple/cur.zip", 1);        // reverted-to: oldest by time
        Item mid = itemAt("simple/mid.zip", 2);
        Item newest = itemAt("simple/newest.zip", 3);
        when(client.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(new Result<>(newest), new Result<>(mid), new Result<>(cur)));

        TestCaseBundleStore store = new TestCaseBundleStore(client, props(2));
        store.pruneOldBundles("simple", "cur");

        verify(client).removeObject(argThat(a -> a.object().equals("simple/mid.zip")));
        verify(client, never()).removeObject(argThat(a -> a.object().equals("simple/cur.zip")));
    }

    @Test
    void doesNotDelete_whenAtOrUnderRetention() throws Exception {
        MinioClient client = mock(MinioClient.class);
        Item cur = itemAt("simple/cur.zip", 2);
        Item one = itemAt("simple/one.zip", 1);
        when(client.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(new Result<>(cur), new Result<>(one)));

        TestCaseBundleStore store = new TestCaseBundleStore(client, props(3));
        store.pruneOldBundles("simple", "cur");

        verify(client, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void swallowsErrors_neverThrows() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.listObjects(any(ListObjectsArgs.class))).thenThrow(new RuntimeException("boom"));
        TestCaseBundleStore store = new TestCaseBundleStore(client, props(3));
        store.pruneOldBundles("simple", "cur");  // must not throw
    }
}
