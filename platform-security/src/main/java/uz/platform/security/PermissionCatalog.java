package uz.platform.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The role-to-permission mapping, held in memory.
 *
 * <h2>Why this is a cache and not a query</h2>
 *
 * <p>Permissions here depend on <b>roles</b>, never on individual users. The
 * entire table is therefore a few hundred rows — a handful of kilobytes — and
 * identical for every request. Querying it per request would be pure waste, and
 * a per-user cache would be worse still: more memory, a cold start on every new
 * user, and an invalidation problem that does not need to exist.</p>
 *
 * <p>So the whole mapping is loaded at startup and refreshed on a schedule. A
 * permission check is then a set lookup inside the process: no database call and
 * no network call on the request path.</p>
 *
 * <h2>Why permissions are not in the token</h2>
 *
 * <p>Because they are resolved here, granting a permission takes effect on the
 * caller's <em>next request</em>, not on their next login. If permissions were
 * claims, a change would sit invisible until the token was refreshed, and the
 * token itself would grow without bound as the platform gained resources.</p>
 */
public class PermissionCatalog implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PermissionCatalog.class);

    private final RolePermissionSource source;

    /**
     * Volatile, and replaced wholesale rather than mutated. A reader either sees
     * the previous map or the new one, never a half-updated one, with no lock on
     * the request path.
     */
    private volatile Map<String, Set<String>> permissionsByRole = Map.of();

    public PermissionCatalog(RolePermissionSource source) {
        this.source = source;
    }

    @Override
    public void afterPropertiesSet() {
        refresh();
    }

    /**
     * Reloads the mapping.
     *
     * <p>On failure the previous map is kept rather than cleared. Dropping to an
     * empty map would revoke every permission in the platform because one query
     * failed, turning a transient database blip into a total outage.</p>
     */
    @Scheduled(
            initialDelayString = "${platform.security.permission-refresh-ms:300000}",
            fixedDelayString = "${platform.security.permission-refresh-ms:300000}")
    public void refresh() {
        try {
            Map<String, Set<String>> loaded = source.load();
            this.permissionsByRole = loaded;
            log.info("Permission catalog loaded: {} role(s), {} grant(s)",
                    loaded.size(), loaded.values().stream().mapToInt(Set::size).sum());
        } catch (RuntimeException ex) {
            log.error("Permission catalog refresh failed; keeping the previous mapping", ex);
        }
    }

    /**
     * The effective permissions of a caller holding these roles.
     *
     * <p>The union across every role. A person who is both {@code QURUVCHI} and
     * {@code BANK} can do everything either role allows, and that is the whole
     * rule — there is no precedence, no ordering, and no role that overrides
     * another.</p>
     */
    public Set<String> permissionsFor(Collection<String> roleCodes) {
        Map<String, Set<String>> snapshot = this.permissionsByRole;
        Set<String> effective = new LinkedHashSet<>();
        for (String role : roleCodes) {
            Set<String> granted = snapshot.get(role);
            if (granted != null) {
                effective.addAll(granted);
            }
        }
        return effective;
    }

    /** The mapping as currently held, for diagnostics and the admin view. */
    public Map<String, Set<String>> asMap() {
        return this.permissionsByRole;
    }
}
