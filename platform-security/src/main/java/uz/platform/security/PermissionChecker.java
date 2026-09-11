package uz.platform.security;

import java.util.Arrays;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The bean behind {@code @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_CREATE')")}.
 *
 * <p>By the time this runs, {@link KeycloakAuthoritiesConverter} has already
 * expanded the caller's roles into permission authorities, so the check is a
 * scan of an in-memory list. It makes no database call and no network call.</p>
 *
 * <h2>This and hasAuthority are equivalent</h2>
 *
 * <p>{@code @permissionChecker.has(authentication, 'PROJECT_CREATE')} and
 * {@code hasAuthority('PROJECT_CREATE')} do the same work and both are correct.
 * The named bean is kept for two reasons: it says the word "permission" at the
 * call site, which distinguishes it at a glance from the role checks that also
 * appear in this codebase, and it is the seam where an organization-aware check
 * lands in phase 6 without touching every annotation.</p>
 *
 * <h2>Prefer permissions over roles on business endpoints</h2>
 *
 * <p>An endpoint annotated with a role hardcodes an organisational fact into
 * code: granting the same capability to a new role means editing and redeploying
 * every affected endpoint. An endpoint annotated with a permission states a
 * stable requirement, and granting it to another role becomes a row in a table.
 * Roles stay for coarse gates, such as an administrative area.</p>
 */
public class PermissionChecker {

    /** True when the caller holds this permission through any of their roles. */
    public boolean has(Authentication authentication, String permission) {
        if (authentication == null || !authentication.isAuthenticated() || permission == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (permission.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** True when the caller holds at least one of these permissions. */
    public boolean hasAny(Authentication authentication, String... permissions) {
        return Arrays.stream(permissions).anyMatch(permission -> has(authentication, permission));
    }

    /** True when the caller holds every one of these permissions. */
    public boolean hasAll(Authentication authentication, String... permissions) {
        return Arrays.stream(permissions).allMatch(permission -> has(authentication, permission));
    }
}
