package uz.platform.security;

import java.util.UUID;

/**
 * Answers one question: may this person act on behalf of this organization?
 *
 * <p>An interface rather than a class, because the answer comes from different
 * places depending on who is asking. organization-service owns the membership
 * tables and reads them directly. Every other service has no access to that
 * schema and asks over HTTP with its own service token.</p>
 *
 * <p>Both implementations resolve to the same rows, which is the point:
 * {@code user_organizations} is the single source of truth for acting-on-behalf,
 * and no service is allowed to decide the question locally from a token claim.</p>
 */
@FunctionalInterface
public interface MembershipVerifier {

    /**
     * @param keycloakSub the {@code sub} claim of the verified token
     * @param tin         the taxpayer number the caller claims to be acting for
     * @return true only when an active membership exists
     */
    boolean isMember(UUID keycloakSub, String tin);
}
