package vn.thanhtuanle.problem;

import vn.thanhtuanle.common.security.Authorities;

/** Who may see what in the problems API: INACTIVE problems are visible only to callers who can edit problems. */
public final class ProblemAccess {

    public static final String EDIT_AUTHORITY = "problem:update";

    private ProblemAccess() {
    }

    /** True iff the current caller holds {@link #EDIT_AUTHORITY}. Anonymous and unauthenticated callers do not. */
    public static boolean callerMayEdit() {
        return Authorities.has(EDIT_AUTHORITY);
    }
}
