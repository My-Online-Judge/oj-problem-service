package vn.thanhtuanle.contest.enrollment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.contest.Contest;
import vn.thanhtuanle.contest.ContestEnrollment;
import vn.thanhtuanle.contest.ContestPhase;
import vn.thanhtuanle.contest.ContestRepository;
import vn.thanhtuanle.contest.ContestStatus;
import vn.thanhtuanle.contest.dto.EnrollmentRow;
import vn.thanhtuanle.contest.dto.EnrollmentState;
import vn.thanhtuanle.contest.dto.ScoreboardUser;
import vn.thanhtuanle.oj.common.identity.PublicUser;
import vn.thanhtuanle.oj.common.identity.PublicUserClient;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.oj.common.web.payload.PageResponse;
import vn.thanhtuanle.oj.common.web.payload.PageSizes;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Registration for contests that require it (contest enrollment design §4.2–§4.3). A user registers from publish
 * until the contest ends and may withdraw before the start; an admin removes anyone at any time. Whether a viewer may
 * see a contest's problems ({@link #maySeeProblems}) is decided here, and the gRPC judge-spec check asks
 * {@link #isEnrolled} before letting a submission into a contest.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnrollmentService {

    private final ContestRepository contests;
    private final EnrollmentRepository enrollments;
    private final PublicUserClient publicUsers;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    /** Registers the caller: idempotent. 404 for an unknown contest (a DRAFT, for a non-editor). */
    @Transactional
    public EnrollmentState enroll(String slug, UUID userId, boolean editor) {
        Contest contest = visible(slug, editor);
        requireRequired(contest);
        if (contest.getStatus() != ContestStatus.PUBLISHED || phase(contest) == ContestPhase.ENDED) {
            throw new AppException(ErrorCode.ENROLLMENT_CLOSED);
        }
        if (enrollments.insertIfAbsent(contest.getId(), userId, clock.instant()) > 0) {
            log.info("User {} registered for contest {}", userId, slug);
        }
        return new EnrollmentState(true, count(contest));
    }

    /** Withdraws the caller: idempotent, and only before the start (the clock is running for everyone after it). */
    @Transactional
    public EnrollmentState withdraw(String slug, UUID userId, boolean editor) {
        Contest contest = visible(slug, editor);
        requireRequired(contest);
        if (contest.getStatus() != ContestStatus.PUBLISHED || phase(contest) != ContestPhase.UPCOMING) {
            throw new AppException(ErrorCode.ENROLLMENT_CLOSED);
        }
        if (enrollments.deleteByContestAndUser(contest.getId(), userId) > 0) {
            log.info("User {} withdrew from contest {}", userId, slug);
        }
        return new EnrollmentState(false, count(contest));
    }

    /** An admin removes a participant, at any phase: their score rows stay, they cannot submit any more. Idempotent. */
    @Transactional
    public void remove(String slug, UUID userId) {
        Contest contest = contests.findBySlug(slug).orElseThrow(() -> notFound(slug));
        if (enrollments.deleteByContestAndUser(contest.getId(), userId) > 0) {
            log.info("User {} removed from contest {} by an admin", userId, slug);
        }
    }

    /**
     * The registered users of a contest, newest first, a page at a time (at most {@code PageSizes} a page); with
     * {@code q}, the ones whose username or name contains it, ignoring case. Not transactional as a whole: the rows
     * are read in one short read-only transaction and identity is asked for the usernames after it
     * ({@link PublicUserClient} caches them and answers what it can when identity is down: such a user is listed with
     * a null username).
     */
    public PageResponse<EnrollmentRow> participants(String slug, int page, int size, String q) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        int capped = PageSizes.cap(size);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            Page<Enrollment> found = readOnly.execute(tx -> {
                Contest contest = contests.findBySlug(slug).orElseThrow(() -> notFound(slug));
                return enrollments.findByKeyContestIdOrderByEnrolledAtDesc(contest.getId(), PageRequest.of(page, capped));
            });
            Map<UUID, PublicUser> users = resolve(found.getContent());
            return PageResponse.of(found.map(e -> row(e, users)));
        }
        // A search spans the whole list: resolve every row, keep the matches, then page them.
        List<Enrollment> all = readOnly.execute(tx -> {
            Contest contest = contests.findBySlug(slug).orElseThrow(() -> notFound(slug));
            return enrollments.findByKeyContestIdOrderByEnrolledAtDesc(contest.getId());
        });
        Map<UUID, PublicUser> users = resolve(all);
        List<EnrollmentRow> matches = all.stream().map(e -> row(e, users)).filter(r -> matches(r, needle)).toList();
        int from = Math.min(page * capped, matches.size());
        int to = Math.min(from + capped, matches.size());
        int totalPages = (int) Math.ceil(matches.size() / (double) capped);
        return PageResponse.<EnrollmentRow>builder()
                .data(matches.subList(from, to))
                .page(page).size(capped).totalElements(matches.size()).totalPages(totalPages)
                .hasPrev(page > 0).hasNext(to < matches.size())
                .build();
    }

    @Transactional(readOnly = true)
    public boolean isEnrolled(UUID contestId, UUID userId) {
        return enrollments.existsByKeyContestIdAndKeyUserId(contestId, userId);
    }

    /**
     * Whether a viewer may see (and open) a contest's problems once it has started: an editor always; everyone once
     * it has ended; everyone signed in while an OPEN one runs; registered users while a REQUIRED one runs.
     */
    @Transactional(readOnly = true)
    public boolean maySeeProblems(Contest contest, Optional<UUID> viewer, boolean editor) {
        if (editor || phase(contest) == ContestPhase.ENDED || contest.getEnrollment() == ContestEnrollment.OPEN) {
            return true;
        }
        return viewer.map(u -> isEnrolled(contest.getId(), u)).orElse(false);
    }

    /** The registered users of a contest (the scoreboard lists all of them for a REQUIRED contest). */
    @Transactional(readOnly = true)
    public Set<UUID> registeredUsers(UUID contestId) {
        return enrollments.userIdsOf(contestId);
    }

    private Contest visible(String slug, boolean editor) {
        return contests.findBySlug(slug)
                .filter(c -> editor || c.getStatus() != ContestStatus.DRAFT)
                .orElseThrow(() -> notFound(slug));
    }

    private static void requireRequired(Contest contest) {
        if (contest.getEnrollment() != ContestEnrollment.REQUIRED) {
            throw new AppException(ErrorCode.ENROLLMENT_NOT_REQUIRED);
        }
    }

    private int count(Contest contest) {
        return (int) enrollments.countByKeyContestId(contest.getId());
    }

    private ContestPhase phase(Contest contest) {
        return ContestPhase.of(contest.getStartsAt(), contest.getEndsAt(), clock.instant());
    }

    private Map<UUID, PublicUser> resolve(List<Enrollment> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        return publicUsers.byIds(rows.stream().map(e -> e.getKey().getUserId()).collect(Collectors.toSet()));
    }

    private static EnrollmentRow row(Enrollment e, Map<UUID, PublicUser> users) {
        UUID id = e.getKey().getUserId();
        PublicUser user = users.get(id);
        return new EnrollmentRow(new ScoreboardUser(id, user == null ? null : user.username(),
                user == null ? null : user.name()), e.getEnrolledAt());
    }

    private static boolean matches(EnrollmentRow row, String needle) {
        return contains(row.user().username(), needle) || contains(row.user().name(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static ResourceNotFoundException notFound(String slug) {
        return new ResourceNotFoundException("Contest not found: " + slug);
    }

    /** Package-private for ContestService: the instant the rules are judged at. */
    Instant now() {
        return clock.instant();
    }
}
