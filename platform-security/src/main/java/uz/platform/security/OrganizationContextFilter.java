package uz.platform.security;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns the {@code X-Organization-TIN} header into verified request context.
 *
 * <h2>The header is a request, never an assertion</h2>
 *
 * <p>A client can put any nine digits in a header. On its own that value means
 * nothing, and trusting it would be the single largest hole an application like
 * this can have: any authenticated user could file documents, read records and
 * incur obligations on behalf of any company in the country.</p>
 *
 * <p>So every organization-scoped request is checked against
 * {@code user_organizations} through a {@link MembershipVerifier}, and the
 * claimed TIN only reaches {@link OrganizationContext} once an active membership
 * is confirmed. A forged value is refused with 403 and recorded, which turns an
 * attempt into a detection signal rather than a silent data leak.</p>
 *
 * <h2>Why the database rather than the token</h2>
 *
 * <p>The token carries {@code org_tins}, and checking against that claim would
 * be faster and entirely local. It is deliberately not what happens here. The
 * claim only <em>seeds</em> membership at login; the table decides. That
 * distinction is what lets an administrator grant a membership OneID knows
 * nothing about, and what lets one be revoked without waiting for a token to
 * expire — with a claim check, a revoked user keeps their access until their
 * token runs out.</p>
 *
 * <h2>Where this sits in the chain</h2>
 *
 * <p>After Spring Security's authorization filter, so only authenticated and
 * already-authorized requests reach it. An anonymous request still gets 401 from
 * the normal machinery rather than a confusing 403 about organizations.</p>
 */
public class OrganizationContextFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Organization-TIN";

    private static final Logger log = LoggerFactory.getLogger(OrganizationContextFilter.class);

    private final MembershipVerifier membershipVerifier;
    private final OrganizationContext organizationContext;

    public OrganizationContextFilter(MembershipVerifier membershipVerifier,
                                     OrganizationContext organizationContext) {
        this.membershipVerifier = membershipVerifier;
        this.organizationContext = organizationContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String claimedTin = request.getHeader(HEADER);
        if (claimedTin == null || claimedTin.isBlank()) {
            // No header. The context stays empty; endpoints that need one will
            // say so themselves with a 400 rather than guessing a default.
            chain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt jwt)) {
            // Not a verified token. Let the rest of the chain produce the right
            // answer; an organization claim from an unauthenticated caller is
            // not something to reason about.
            chain.doFilter(request, response);
            return;
        }

        UUID subject = PlatformClaims.subject(jwt);
        if (!membershipVerifier.isMember(subject, claimedTin)) {
            // Log the attempt. The subject and the claimed TIN are enough to
            // investigate; no personal data and no token value is written.
            log.warn("Refused organization context: subject={} claimed tin={} path={}",
                    subject, claimedTin, request.getRequestURI());
            refuse(response, claimedTin);
            return;
        }

        organizationContext.set(claimedTin);
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletResponse response, String claimedTin) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"error":"organization_membership_required",\
                "message":"You are not an active member of organization %s"}"""
                .formatted(claimedTin));
    }
}
