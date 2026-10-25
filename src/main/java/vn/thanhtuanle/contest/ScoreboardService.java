package vn.thanhtuanle.contest;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.contest.dto.ScoreboardResponse;
import vn.thanhtuanle.oj.common.identity.PublicUser;
import vn.thanhtuanle.oj.common.identity.PublicUserClient;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import vn.thanhtuanle.contest.enrollment.EnrollmentRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A contest's ICPC scoreboard ({@link Scoreboard}) over its score rows (Task 12), with usernames from identity.
 * "Editor" means {@code contest:update}, as for the detail: a DRAFT is a 404 for everyone else, and a contest that has
 * not started shows them its labels but no rows.
 */
@Service
@RequiredArgsConstructor
public class ScoreboardService {

    private final ContestRepository contests;
    private final ContestScoreRepository scores;
    private final PublicUserClient publicUsers;
    private final EnrollmentRepository enrollments;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    /** What the database holds for one board: the contest's labels and the score rows of its current problems. */
    private record Board(Instant startsAt, List<String> labels, Map<UUID, String> labelByProblem, List<ScoreRow> rows,
            Set<UUID> enrolled) {
    }

    /**
     * Not transactional: the contest and its score rows are read in one short read-only transaction, and identity is
     * asked for the usernames after it (PublicUserClient caches them for 5 minutes and answers what it can when
     * identity fails), so a slow identity never holds a database connection.
     */
    public ScoreboardResponse scoreboard(String slug, boolean editor) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        Board board = readOnly.execute(tx -> read(slug, editor));
        // Only the contestants the board lists (Task 13 review M1): one with only compile or system errors is not.
        Set<UUID> userIds = board.rows().stream().filter(Scoreboard::counts).map(ScoreRow::userId)
                .collect(Collectors.toCollection(HashSet::new));
        userIds.addAll(board.enrolled());   // a REQUIRED contest's registered users, with or without a row
        if (userIds.isEmpty()) {
            return new ScoreboardResponse(board.labels(), List.of());
        }
        Map<UUID, PublicUser> users = publicUsers.byIds(userIds);
        return new ScoreboardResponse(board.labels(),
                Scoreboard.rank(board.startsAt(), board.labelByProblem(), board.rows(), users, board.enrolled()));
    }

    /**
     * Labels and problems come from the contest's current problems: score rows of a problem removed while the contest
     * was a DRAFT stay in the table and are dropped here. So are a problem soft-deleted after it was attached and its
     * rows, for everyone, editors included, as the public detail and the list count leave it out: one board, the same
     * ranking for every viewer.
     */
    private Board read(String slug, boolean editor) {
        Contest contest = contests.findBySlug(slug)
                .filter(c -> editor || c.getStatus() != ContestStatus.DRAFT)
                .orElseThrow(() -> new ResourceNotFoundException("Contest not found: " + slug));
        List<String> labels = new ArrayList<>();
        Map<UUID, String> labelByProblem = new HashMap<>();
        contest.getProblems().stream()
                .filter(cp -> cp.getProblem().getStatus() != ProblemStatus.DELETED.getValue())
                .sorted(Comparator.comparingInt(ContestProblem::getPosition))
                .forEach(cp -> {
                    labels.add(String.valueOf(cp.getLabel()));
                    labelByProblem.put(cp.getProblem().getId(), String.valueOf(cp.getLabel()));
                });
        boolean upcoming = ContestPhase.of(contest.getStartsAt(), contest.getEndsAt(), clock.instant())
                == ContestPhase.UPCOMING;
        List<ScoreRow> rows = upcoming && !editor ? List.of()
                : scores.findByContestId(contest.getId()).stream()
                        .filter(r -> labelByProblem.containsKey(r.problemId()))
                        .toList();
        // Registered users show from the start too (editors see them before it), zero rows included (design §4.2).
        Set<UUID> enrolled = contest.getEnrollment() == ContestEnrollment.REQUIRED && !(upcoming && !editor)
                ? enrollments.userIdsOf(contest.getId()) : Set.of();
        return new Board(contest.getStartsAt(), labels, labelByProblem, rows, enrolled);
    }
}
