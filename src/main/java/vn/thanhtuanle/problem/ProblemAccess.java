package vn.thanhtuanle.problem;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who may see what in the problems API: INACTIVE problems are visible only to callers who can edit problems. */
public final class ProblemAccess {

    public static final String EDIT_AUTHORITY = "problem:update";

    private ProblemAccess() {
    }

    /** True iff the current caller holds {@link #EDIT_AUTHORITY}. Anonymous and unauthenticated callers do not. */
    public static boolean callerMayEdit() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (EDIT_AUTHORITY.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
