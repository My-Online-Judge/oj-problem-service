package vn.thanhtuanle.testcase;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Publishes a problem's test cases to MinIO as an immutable, content-addressed bundle and resolves
 * the "current" bundle for judging. Bundle object: {@code <slug>/<hash>.zip}; pointer object:
 * {@code <slug>/CURRENT} (plain-text hash). Because a (slug, hash) bundle never changes, downstream
 * caches never go stale.
 */
@Component
@Slf4j
public class TestCaseBundleStore {

    private final MinioClient minio;
    private final MinioProperties props;

    public TestCaseBundleStore(MinioClient minio, MinioProperties props) {
        this.minio = minio;
        this.props = props;
    }

    /** Uploads the bundle made of {@code files} (unless it already exists) and points CURRENT at it. */
    public String publish(String slug, List<BundleFile> files) {
        if (files.isEmpty()) {
            throw new TestCaseBundleException("No test-case files to publish for problem: " + slug);
        }
        try {
            List<BundleFile> sorted = BundleFile.sortedByName(files);
            String hash = contentHash(sorted);
            String key = bundleKey(slug, hash);
            if (!objectExists(key)) {
                putBytes(key, zip(sorted), "application/zip");
            }
            putBytes(currentKey(slug), hash.getBytes(StandardCharsets.UTF_8), "text/plain");
            pruneOldBundles(slug, hash);
            log.info("Published test-case bundle for {} -> {}", slug, hash);
            return hash;
        } catch (TestCaseBundleException e) {
            throw e;
        } catch (Exception e) {
            throw new TestCaseBundleException("Failed to publish bundle for problem: " + slug, e);
        }
    }

    public String currentVersion(String slug) {
        try (InputStream is = minio.getObject(GetObjectArgs.builder()
                .bucket(props.getBucket()).object(currentKey(slug)).build())) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new TestCaseBundleException("No published test-case bundle for problem: " + slug, e);
        }
    }

    /**
     * The files of the bundle CURRENT points at, by name; empty when the problem has no bundle. Only the
     * one-time 2a backfill needs this, to recover test-case files that exist nowhere else.
     */
    public Map<String, byte[]> currentBundleFiles(String slug) {
        if (!hasBundle(slug)) {
            return Map.of();
        }
        String key = bundleKey(slug, currentVersion(slug));
        try (InputStream is = minio.getObject(GetObjectArgs.builder().bucket(props.getBucket()).object(key).build());
             ZipInputStream zis = new ZipInputStream(is)) {
            Map<String, byte[]> files = new HashMap<>();
            for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                if (!entry.isDirectory()) {
                    files.put(entry.getName(), zis.readAllBytes());
                }
            }
            return files;
        } catch (Exception e) {
            throw new TestCaseBundleException("Failed to read bundle " + key, e);
        }
    }

    public boolean hasBundle(String slug) {
        return objectExists(currentKey(slug));
    }

    public void ensureBucket() {
        try {
            boolean exists = minio.bucketExists(
                    BucketExistsArgs.builder().bucket(props.getBucket()).build());
            if (!exists) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
                log.info("Created MinIO bucket {}", props.getBucket());
            }
        } catch (Exception e) {
            throw new TestCaseBundleException("Failed to ensure bucket " + props.getBucket(), e);
        }
    }

    /**
     * Keep the current bundle plus the (retention-1) most-recent other versions for this slug;
     * remove the rest. The current hash is retained unconditionally (a revert can make it old by
     * time). Never throws — GC must not fail a publish.
     */
    void pruneOldBundles(String slug, String currentHash) {
        try {
            int keep = Math.max(1, props.getBundleRetention());
            List<Item> zips = new ArrayList<>();
            for (Result<Item> r : minio.listObjects(ListObjectsArgs.builder()
                    .bucket(props.getBucket()).prefix(slug + "/").build())) {
                Item item = r.get();
                if (item.objectName().endsWith(".zip")) {
                    zips.add(item);
                }
            }
            if (zips.size() <= keep) {
                return;
            }
            zips.sort(Comparator.comparing(Item::lastModified, Comparator.nullsLast(Comparator.naturalOrder())).reversed());  // newest first
            Set<String> retain = new LinkedHashSet<>();
            retain.add(bundleKey(slug, currentHash));   // always keep current
            for (Item i : zips) {
                if (retain.size() >= keep) break;
                retain.add(i.objectName());              // fill with most-recent (current re-add is a no-op)
            }
            for (Item i : zips) {
                if (!retain.contains(i.objectName())) {
                    minio.removeObject(RemoveObjectArgs.builder()
                            .bucket(props.getBucket()).object(i.objectName()).build());
                    log.info("GC: removed old test-case bundle {}", i.objectName());
                }
            }
        } catch (Exception e) {
            log.warn("GC of old bundles for {} failed (non-fatal): {}", slug, e.getMessage());
        }
    }

    private boolean objectExists(String key) {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(props.getBucket()).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if (isMissing(e)) {
                return false;  // genuinely absent
            }
            // 403 AccessDenied, throttling, etc. must NOT read as "absent".
            throw new TestCaseBundleException("Failed to stat object " + key
                    + " (code=" + (e.errorResponse() != null ? e.errorResponse().code() : null) + ")", e);
        } catch (Exception e) {
            throw new TestCaseBundleException("Failed to stat object " + key, e);
        }
    }

    private void putBytes(String key, byte[] data, String contentType) throws Exception {
        try (InputStream is = new ByteArrayInputStream(data)) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket()).object(key)
                    .stream(is, data.length, -1)
                    .contentType(contentType)
                    .build());
        }
    }

    /** True only when MinIO says the object does not exist; any other error is a real failure. */
    static boolean isMissing(ErrorResponseException e) {
        String code = e.errorResponse() != null ? e.errorResponse().code() : null;
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code);
    }

    static String contentHash(List<BundleFile> sortedFiles) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (BundleFile f : sortedFiles) {
                md.update(f.name().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(f.content());
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static byte[] zip(List<BundleFile> sortedFiles) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (BundleFile f : sortedFiles) {
                ZipEntry entry = new ZipEntry(f.name());
                entry.setTime(0L); // deterministic archive
                zos.putNextEntry(entry);
                zos.write(f.content());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static String bundleKey(String slug, String hash) {
        return slug + "/" + hash + ".zip";
    }

    static String currentKey(String slug) {
        return slug + "/CURRENT";
    }
}
