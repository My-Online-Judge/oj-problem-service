package vn.thanhtuanle.contest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.contest.dto.ContestDetail;
import vn.thanhtuanle.contest.dto.ContestListResponse;
import vn.thanhtuanle.contest.dto.ContestProblemView;
import vn.thanhtuanle.contest.dto.ContestRequest;
import vn.thanhtuanle.contest.dto.ContestSummary;
import vn.thanhtuanle.contest.dto.ProblemProgress;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.problem.ProblemService;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Contests: the public list and detail, admin changes, and problems read through a contest. An "editor" is a caller
 * with {@code contest:update}; DRAFT contests exist for editors only. A "problem editor" holds {@code problem:update}:
 * only a caller who is both sees a contest's problems early (a DRAFT, or before the start), and only a problem editor
 * attaches a problem that is not ACTIVE, because SP12 keeps INACTIVE problems for problem editors. Every DTO is built
 * inside the transaction (open-in-view is off).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContestService {

    static final int MAX_PROBLEMS = 26;
    static final int PAST_LIMIT = 50;
    private static final String SLUG_INDEX = "ux_contests_slug";

    private final ContestRepository contests;
    private final ContestProblemRepository contestProblems;
    private final ContestScoreRepository scores;
    private final ProblemRepository problems;
    private final ProblemService problemService;
    private final Clock clock;

    /** PUBLISHED contests (and DRAFT ones for editors) by phase; past ones newest first, at most 50. */
    @Transactional(readOnly = true)
    public ContestListResponse list(boolean editor) {
        Instant now = clock.instant();
        List<ContestStatus> statuses = editor
                ? List.of(ContestStatus.DRAFT, ContestStatus.PUBLISHED)
                : List.of(ContestStatus.PUBLISHED);
        List<Contest> notEnded = contests.findNotEndedAt(statuses, now);
        List<Contest> past = contests.findEndedAt(statuses, now, PageRequest.of(0, PAST_LIMIT));
        Map<UUID, Integer> counts = problemCounts(Stream.concat(notEnded.stream(), past.stream()).toList());

        List<ContestSummary> running = notEnded.stream()
                .filter(c -> phase(c, now) == ContestPhase.RUNNING)
                .sorted(Comparator.comparing(Contest::getEndsAt).thenComparing(Contest::getSlug))
                .map(c -> summary(c, now, counts)).toList();
        List<ContestSummary> upcoming = notEnded.stream()
                .filter(c -> phase(c, now) == ContestPhase.UPCOMING)
                .sorted(Comparator.comparing(Contest::getStartsAt).thenComparing(Contest::getSlug))
                .map(c -> summary(c, now, counts)).toList();
        return new ContestListResponse(running, upcoming, past.stream().map(c -> summary(c, now, counts)).toList(), now);
    }

    /**
     * DRAFT is a 404 for non-editors. The problems stay hidden while the contest is a DRAFT or has not started,
     * unless the caller is both an editor and a problem editor. Problems deleted since they were attached are left
     * out for non-editors; editors keep them, so an edit can send the list back unchanged.
     */
    @Transactional(readOnly = true)
    public ContestDetail detail(String slug, boolean editor, boolean problemEditor, Optional<UUID> viewer) {
        Contest contest = visible(slug, editor);
        return toDetail(contest, editor, editor && problemEditor, viewer);
    }

    /** Times are stored at whole seconds (see {@link #seconds}). */
    @Transactional
    public ContestDetail create(ContestRequest r, boolean problemEditor) {
        log.info("Start create contest: {}", r.slug());
        Instant startsAt = seconds(r.startsAt());
        Instant endsAt = seconds(r.endsAt());
        requireWindow(startsAt, endsAt);
        List<Problem> chosen = resolveProblems(r.problemSlugs(), problemEditor);
        if (contests.existsBySlug(r.slug())) {
            throw slugTaken(r.slug());
        }
        Contest contest = Contest.builder()
                .slug(r.slug())
                .title(r.title())
                .description(r.description())
                .startsAt(startsAt)
                .endsAt(endsAt)
                .status(r.status())
                .build();
        setProblems(contest, chosen);
        claimSlug(contest);
        return toDetail(contest, true, problemEditor, Optional.empty());
    }

    /**
     * Once a PUBLISHED contest has started, its start time and problems are locked (409); its title, description,
     * end and status may still change. The start is compared at whole seconds, as it is stored.
     */
    @Transactional
    public ContestDetail update(String slug, ContestRequest r, boolean problemEditor) {
        log.info("Start update contest: {}", slug);
        Contest contest = find(slug);
        Instant startsAt = seconds(r.startsAt());
        Instant endsAt = seconds(r.endsAt());
        boolean problemsChange = !slugsOf(contest).equals(r.problemSlugs());
        boolean startChanges = !seconds(contest.getStartsAt()).equals(startsAt);
        if (started(contest) && (problemsChange || startChanges)) {
            throw new AppException(ErrorCode.CONTEST_STARTED);
        }
        requireWindow(startsAt, endsAt);
        // An unchanged list is not checked again: a problem deleted since must not block the other changes.
        List<Problem> chosen = problemsChange ? resolveProblems(r.problemSlugs(), problemEditor) : null;

        contest.setTitle(r.title());
        contest.setDescription(r.description());
        contest.setStartsAt(startsAt);
        contest.setEndsAt(endsAt);
        contest.setStatus(r.status());
        if (chosen != null) {
            contest.getProblems().clear();
            contests.flush(); // the old rows go first: (contest, label) and (contest, problem) are unique
            setProblems(contest, chosen);
        }
        return toDetail(contest, true, problemEditor, Optional.empty());
    }

    /** Refused (409) for a PUBLISHED contest that has started; otherwise its problems and scores go with it. */
    @Transactional
    public void delete(String slug) {
        log.info("Start delete contest: {}", slug);
        Contest contest = find(slug);
        if (started(contest)) {
            throw new AppException(ErrorCode.CONTEST_STARTED);
        }
        contests.delete(contest); // t_contest_scores goes by ON DELETE CASCADE
    }

    /**
     * A contest's problem by label, whatever the problem's status except DELETED: for everyone once a PUBLISHED
     * contest is running or has ended, at any time for an {@code editor}, which here means a caller who is both an
     * editor and a problem editor. Otherwise a 404.
     */
    @Transactional(readOnly = true)
    public ProblemResponseDto problem(String slug, String label, boolean editor) {
        Contest contest = visible(slug, editor);
        if (!editor && phase(contest, clock.instant()) == ContestPhase.UPCOMING) {
            throw notFound(slug);
        }
        ContestProblem cp = contest.getProblems().stream()
                .filter(p -> label.length() == 1 && p.getLabel() == label.charAt(0))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Contest " + slug + " has no problem " + label));
        return problemService.getProblemBySlug(cp.getProblem().getProblemSlug(), true);
    }

    /**
     * The id of the contest a submission names, when the judge may take it: the contest is PUBLISHED, running now
     * ([startsAt, endsAt)) and contains the problem. Empty otherwise, an unknown contest included.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> runningContestId(String contestSlug, String problemSlug) {
        Instant now = clock.instant();
        return contests.findBySlugContaining(contestSlug, problemSlug)
                .filter(c -> c.getStatus() == ContestStatus.PUBLISHED && phase(c, now) == ContestPhase.RUNNING)
                .map(Contest::getId);
    }

    /**
     * Inserts the new contest now: a concurrent create of the same slug passed existsBySlug too, and only the unique
     * index tells the loser, who is refused like a taken slug instead of failing at commit.
     */
    private void claimSlug(Contest contest) {
        try {
            contests.saveAndFlush(contest);
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && SLUG_INDEX.equals(violation.getConstraintName())) {
                throw slugTaken(contest.getSlug());
            }
            throw e;
        }
    }

    private static ResourceAlreadyExistException slugTaken(String slug) {
        return new ResourceAlreadyExistException("Contest slug already exists: " + slug);
    }

    private Contest find(String slug) {
        return contests.findBySlug(slug).orElseThrow(() -> notFound(slug));
    }

    private Contest visible(String slug, boolean editor) {
        Contest contest = find(slug);
        if (contest.getStatus() == ContestStatus.DRAFT && !editor) {
            throw notFound(slug);
        }
        return contest;
    }

    private static ResourceNotFoundException notFound(String slug) {
        return new ResourceNotFoundException("Contest not found: " + slug);
    }

    /** A PUBLISHED contest whose start has come: its start and problems are locked and it cannot be deleted. */
    private boolean started(Contest contest) {
        return contest.getStatus() == ContestStatus.PUBLISHED && !contest.getStartsAt().isAfter(clock.instant());
    }

    private static ContestPhase phase(Contest contest, Instant now) {
        return ContestPhase.of(contest.getStartsAt(), contest.getEndsAt(), now);
    }

    /**
     * A contest time as stored: whole seconds. Clients round differently (Postgres keeps µs, a JS Date ms, a
     * datetime-local input minutes), and an edit that sends back the start it was given must not read as a change.
     */
    private static Instant seconds(Instant time) {
        return time.truncatedTo(ChronoUnit.SECONDS);
    }

    private static void requireWindow(Instant startsAt, Instant endsAt) {
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("A contest must end after it starts");
        }
    }

    /**
     * The live problems behind these slugs, in order: 1 to 26 of them, each once. A problem that is not ACTIVE counts
     * as unknown unless the caller is a problem editor.
     */
    private List<Problem> resolveProblems(List<String> slugs, boolean problemEditor) {
        if (slugs == null || slugs.isEmpty() || slugs.size() > MAX_PROBLEMS) {
            throw new IllegalArgumentException("A contest has 1 to " + MAX_PROBLEMS + " problems");
        }
        Set<String> seen = new HashSet<>();
        for (String slug : slugs) {
            if (!seen.add(slug)) {
                throw new IllegalArgumentException("Duplicate problem: " + slug);
            }
        }
        List<Problem> chosen = new ArrayList<>();
        for (String slug : slugs) {
            chosen.add(problems.findLiveBySlug(slug)
                    .filter(p -> problemEditor || p.getStatus() == ProblemStatus.ACTIVE.getValue())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown problem: " + slug)));
        }
        return chosen;
    }

    /** Labels follow the order: A at position 0, B at 1, and so on. */
    private static void setProblems(Contest contest, List<Problem> chosen) {
        for (int i = 0; i < chosen.size(); i++) {
            contest.getProblems().add(new ContestProblem(contest, chosen.get(i), (char) ('A' + i), i));
        }
    }

    private static List<String> slugsOf(Contest contest) {
        return ordered(contest).stream().map(cp -> cp.getProblem().getProblemSlug()).toList();
    }

    private static List<ContestProblem> ordered(Contest contest) {
        return contest.getProblems().stream().sorted(Comparator.comparingInt(ContestProblem::getPosition)).toList();
    }

    private Map<UUID, Integer> problemCounts(List<Contest> listed) {
        if (listed.isEmpty()) {
            return Map.of();
        }
        return contestProblems.countByContestIds(listed.stream().map(Contest::getId).toList()).stream()
                .collect(Collectors.toMap(ContestProblemRepository.ProblemCount::getContestId,
                        c -> (int) c.getProblems()));
    }

    private static ContestSummary summary(Contest c, Instant now, Map<UUID, Integer> counts) {
        return new ContestSummary(c.getSlug(), c.getTitle(), c.getStartsAt(), c.getEndsAt(), c.getStatus(),
                phase(c, now), counts.getOrDefault(c.getId(), 0));
    }

    /**
     * {@code early}: the caller may see the problems of a DRAFT, or before the start. {@code editor}: the caller also
     * sees the problems deleted since they were attached.
     */
    private ContestDetail toDetail(Contest contest, boolean editor, boolean early, Optional<UUID> viewer) {
        Instant now = clock.instant();
        ContestPhase phase = phase(contest, now);
        boolean open = contest.getStatus() == ContestStatus.PUBLISHED && phase != ContestPhase.UPCOMING;
        List<ContestProblem> shown = (early || open ? ordered(contest) : List.<ContestProblem>of()).stream()
                .filter(cp -> editor || cp.getProblem().getStatus() != ProblemStatus.DELETED.getValue())
                .toList();
        List<ContestProblemView> views = shown.stream()
                .map(cp -> new ContestProblemView(String.valueOf(cp.getLabel()), cp.getProblem().getProblemSlug(),
                        cp.getProblem().getTitle()))
                .toList();
        Map<String, ProblemProgress> progress = viewer
                .map(userId -> progress(contest, shown, userId))
                .orElse(Map.of());
        return new ContestDetail(contest.getSlug(), contest.getTitle(), contest.getDescription(), contest.getStartsAt(),
                contest.getEndsAt(), contest.getStatus(), phase, views, progress, now);
    }

    /** The viewer's score rows by label, for the problems shown. */
    private Map<String, ProblemProgress> progress(Contest contest, List<ContestProblem> shown, UUID userId) {
        if (shown.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> labelByProblem = new HashMap<>();
        shown.forEach(cp -> labelByProblem.put(cp.getProblem().getId(), String.valueOf(cp.getLabel())));
        Map<String, ProblemProgress> progress = new TreeMap<>();
        for (ScoreRow row : scores.findByContestAndUser(contest.getId(), userId)) {
            String label = labelByProblem.get(row.problemId());
            if (label != null) {
                boolean solved = row.acceptedAt() != null;
                progress.put(label, new ProblemProgress(solved, row.attemptsBeforeAc() + (solved ? 1 : 0)));
            }
        }
        return progress;
    }
}
