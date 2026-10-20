package vn.thanhtuanle.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** What the current caller holds, for endpoints that answer everyone but show editors more. */
public final class Authorities {

    private Authorities() {
    }

    /** True iff the current caller holds {@code authority}. A missing or unauthenticated authentication holds nothing. */
    public static boolean has(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (authority.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
