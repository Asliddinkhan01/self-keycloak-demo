package uz.platform.security;

import java.util.Map;
import java.util.Set;

/**
 * Supplies the role-to-permission mapping this service enforces.
 *
 * <p>Each service implements this over its own {@code role_permissions} table.
 * The shared library owns the caching and the checking; the service owns the
 * data, because <b>a permission is only meaningful where it is enforced</b>.
 * cadastral-service decides what {@code PROJECT_CREATE} means and which roles
 * confer it; nothing else in the platform has an opinion about it.</p>
 *
 * <h2>Why not one central catalog</h2>
 *
 * <p>The obvious alternative is a single authorization service that every other
 * service queries. It reads well on a diagram and it has one serious flaw: it
 * puts a network dependency on the critical path of <em>every</em> authorization
 * decision in <em>every</em> service. When that service is unavailable, each
 * caller must choose between failing closed — a platform-wide outage triggered
 * by one component — and serving stale data it cannot verify.</p>
 *
 * <p>Owning the rows locally removes that failure mode entirely.
 * cadastral-service can authorize requests whether or not user-service is
 * running, and the mapping is versioned in the same migration history as the
 * endpoints it protects, so the two can never drift.</p>
 *
 * <p>The cost is real and worth stating: answering "what can QURUVCHI do across
 * the whole platform?" means asking every service rather than one. That is an
 * administrative question, not a request-path one, and phase 7's
 * service-to-service machinery is the natural way to aggregate it later.</p>
 */
@FunctionalInterface
public interface RolePermissionSource {

    /**
     * Loads the whole mapping.
     *
     * <p>Called at startup and then on a schedule, never per request. The data is
     * a few hundred rows at most, because permissions depend on roles and not on
     * individual users, so loading all of it is cheaper than any per-user cache
     * would be — and there is nothing to invalidate.</p>
     *
     * @return role code to the set of permission codes it grants
     */
    Map<String, Set<String>> load();
}
