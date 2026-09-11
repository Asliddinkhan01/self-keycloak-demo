package uz.platform.security;

/**
 * The organization the caller is acting on behalf of, for this request only.
 *
 * <h2>Context, not identity</h2>
 *
 * <p>A person may be attached to several legal entities. OneID says so: a single
 * physical person can carry a {@code legal_info} array with many entries. The
 * mistake to avoid is creating one account per organization — the human being is
 * the same person in all of them, with the same PIN and the same login.</p>
 *
 * <p>So the organization is <b>request context</b>. Identity comes from the
 * token and never changes within a session; the acting organization arrives per
 * request and can change between one call and the next, which is what lets a
 * user switch companies in the UI without logging in again.</p>
 *
 * <p>Request-scoped rather than a ThreadLocal: Spring discards the instance at
 * the end of the request whatever happens, including on an exception, so nothing
 * leaks into the next request that reuses the thread.</p>
 *
 * <p>The scope is declared on the @Bean method in OrganizationSecurity, NOT here.
 * A class-level @RequestScope is silently ignored when the bean is created by an
 * @Bean method, which would leave this a singleton — and a singleton here means
 * one caller's acting organization bleeding into the next caller's request.</p>
 *
 * <p>The value here has <b>already been verified</b> against the caller's
 * memberships by {@link OrganizationContextFilter}. A controller reading it can
 * trust it; nothing sets it from a header without checking first.</p>
 */
public class OrganizationContext {

    private String tin;

    /** Set only by the filter, and only after membership has been confirmed. */
    void set(String tin) {
        this.tin = tin;
    }

    public boolean isPresent() {
        return tin != null;
    }

    /** The acting TIN, or null when the caller sent no organization header. */
    public String tinOrNull() {
        return tin;
    }

    /**
     * The acting TIN, or a failure.
     *
     * <p>Called by endpoints that only make sense on behalf of an organization.
     * Refusing is the right answer when the header is missing: filing a
     * construction project against a silently chosen default organization is a
     * worse outcome than an error the caller can read.</p>
     */
    public String requireTin() {
        if (tin == null) {
            throw new OrganizationContextRequiredException();
        }
        return tin;
    }
}
