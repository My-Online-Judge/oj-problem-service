package vn.thanhtuanle.problem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Tag rules: lowercase kebab-case, at most 32 characters each, at most 10 per problem. */
public final class ProblemTags {

    public static final int MAX_TAGS = 10;
    public static final int MAX_LENGTH = 32;
    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private ProblemTags() {
    }

    /** Trimmed, lowercased, deduplicated in first-seen order; null (meaning "unchanged") stays null. */
    public static List<String> normalize(List<String> raw) {
        if (raw == null) {
            return null;
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : raw) {
            String t = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
            if (t.length() > MAX_LENGTH || !FORMAT.matcher(t).matches()) {
                throw new IllegalArgumentException("Invalid tag: " + tag);
            }
            tags.add(t);
        }
        if (tags.size() > MAX_TAGS) {
            throw new IllegalArgumentException("A problem has at most " + MAX_TAGS + " tags");
        }
        return new ArrayList<>(tags);
    }
}
