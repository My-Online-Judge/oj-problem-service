package vn.thanhtuanle.problem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import vn.thanhtuanle.common.util.AfterCommit;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.problem.dto.CheckerResponse;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseSourceStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A problem's checker (special judge): a C++ program that qduoj runs as {@code spj <input_file> <user_output_file>},
 * exit code 0 for Accepted and 1 for Wrong Answer. Its source is stored next to the test cases, at
 * {@code <slug>/spj.cpp} (MinIO {@code sources/<slug>/spj.cpp}); the problem row says that it has one ({@code spj}) and
 * which ({@code spj_version}, the sha256 hex of the source). Like a test-case change, every change republishes the
 * bundle under the problem's row lock, and the version in the bundle's {@code info} gives it a new hash.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckerService {

    public static final String CHECKER_FILE = "spj.cpp";
    public static final int MAX_CHECKER_BYTES = 65_536;

    private final ProblemRepository problemRepository;
    private final TestCaseSourceStore sources;
    private final TestCaseBundlePublisher publisher;

    /** Where the problem's checker source is stored: {@code <slug>/spj.cpp}. */
    public static String checkerPath(String slug) {
        return slug + "/" + CHECKER_FILE;
    }

    /**
     * The rules of a checker source, for an upload and for a package's {@code spj.cpp} alike: not empty, at most
     * {@value #MAX_CHECKER_BYTES} bytes, and UTF-8 text.
     *
     * @throws IllegalArgumentException (400) otherwise
     */
    public static void requireValidSource(byte[] source) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("Checker file cannot be empty");
        }
        if (source.length > MAX_CHECKER_BYTES) {
            throw tooLarge();
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("A checker must be UTF-8 text");
        }
    }

    /** A checker's version: the sha256 hex of its source (64 characters). */
    public static String versionOf(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Not transactional: the row is read on its own, then the source, so a slow MinIO never holds a database
     * connection. A checker whose source is missing from storage reads with a null source.
     */
    public CheckerResponse get(String slug) {
        Problem problem = problemRepository.findLiveBySlug(slug).orElseThrow(() -> notFound(slug));
        if (!problem.isSpj()) {
            return new CheckerResponse(false, null, null);
        }
        String source = sources.get(checkerPath(slug))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElseGet(() -> {
                    log.warn("Problem {} has a checker, but its source {} is not stored", slug, checkerPath(slug));
                    return null;
                });
        return new CheckerResponse(true, problem.getSpjVersion(), source);
    }

    /**
     * Stores the source as the problem's checker, replacing any earlier one, and republishes the bundle while the
     * problem row is locked, so two changes of one problem cannot interleave (as test-case changes do).
     */
    @Transactional
    public CheckerResponse upload(String slug, MultipartFile file) {
        if (file != null && file.getSize() > MAX_CHECKER_BYTES) {
            throw tooLarge(); // refused before it is read
        }
        byte[] source = bytesOf(file);
        requireValidSource(source);

        Problem problem = problemRepository.findBySlugForUpdate(slug).orElseThrow(() -> notFound(slug));
        String version = versionOf(source);
        sources.put(checkerPath(slug), source);
        problem.setSpj(true);
        problem.setSpjVersion(version);
        publisher.publish(problem);
        log.info("Problem {} is judged by checker {}", slug, version);
        return new CheckerResponse(true, version, null);
    }

    /**
     * Back to comparing outputs: clears the flags and republishes under the row lock; the source is deleted only once
     * that has committed, since a rollback would leave the row still naming it.
     */
    @Transactional
    public void remove(String slug) {
        Problem problem = problemRepository.findBySlugForUpdate(slug).orElseThrow(() -> notFound(slug));
        if (!problem.isSpj()) {
            throw new ResourceNotFoundException("Problem " + slug + " has no checker");
        }
        problem.setSpj(false);
        problem.setSpjVersion(null);
        publisher.publish(problem);
        AfterCommit.run(() -> sources.deleteQuietly(checkerPath(slug)));
        log.info("Problem {} no longer has a checker", slug);
    }

    private static byte[] bytesOf(MultipartFile file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the uploaded checker", e);
        }
    }

    private static IllegalArgumentException tooLarge() {
        return new IllegalArgumentException("A checker is at most 64 KiB");
    }

    private static ResourceNotFoundException notFound(String slug) {
        return new ResourceNotFoundException("Problem not found with slug: " + slug);
    }
}
