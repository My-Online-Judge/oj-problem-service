package vn.thanhtuanle.contest;

import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/** Filters of the contest managers' list. */
final class ContestSpecifications {

    private static final char ESCAPE = '\\';

    private ContestSpecifications() {
    }

    /**
     * Contests whose slug or title contains {@code q}, ignoring case. A blank {@code q} matches every contest. The
     * text is literal: {@code %}, {@code _} and {@code \} in it match themselves.
     */
    static Specification<Contest> matching(String q) {
        if (q == null || q.isBlank()) {
            return (root, query, cb) -> null;
        }
        String pattern = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("slug")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("title")), pattern, ESCAPE));
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
