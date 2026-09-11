package uz.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Switches on organization (TIN) context for a service.
 *
 * <p>Imported only by services that have organization-scoped endpoints. A
 * service that opts in must also declare one bean: a {@link MembershipVerifier}
 * saying how it answers "is this person a member of this organization".</p>
 *
 * <p>organization-service answers from its own tables. Every other service asks
 * organization-service over HTTP with its own service token, because no other
 * service has access to that schema — which is the intended shape rather than an
 * inconvenience.</p>
 */
@Configuration
public class OrganizationSecurity {

    /**
     * One instance per HTTP request.
     *
     * <p>The scope annotation belongs here, on the {@code @Bean} method. Putting
     * {@code @RequestScope} on the class instead compiles, reads correctly, and
     * is <b>silently ignored</b> — Spring honours class-level scope only for
     * component-scanned beans, not for ones a factory method creates.</p>
     *
     * <p>That mistake is worth naming because it fails quietly and dangerously.
     * The bean becomes a singleton, so the acting organization set by one
     * caller's request is still there for the next caller's, and a request that
     * sent no header inherits whichever organization was last used. It looks
     * like the feature works, right up until two people use the system at once.</p>
     *
     * <p>{@code @RequestScope} also implies a scoped proxy, which is what lets
     * this be injected into singleton controllers and the filter.</p>
     */
    @Bean
    @RequestScope
    public OrganizationContext organizationContext() {
        return new OrganizationContext();
    }

    @Bean
    public OrganizationContextFilter organizationContextFilter(MembershipVerifier membershipVerifier,
                                                               OrganizationContext organizationContext) {
        return new OrganizationContextFilter(membershipVerifier, organizationContext);
    }
}
