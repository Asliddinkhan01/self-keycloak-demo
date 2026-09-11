package uz.platform.organizationservice.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import uz.platform.security.MembershipVerifier;

/**
 * organization-service answers the membership question from its own tables.
 *
 * <p>It is the only service that can. Every other service reaches the same rows
 * over HTTP, because no other PostgreSQL role has USAGE on this schema.</p>
 */
@Component
public class LocalMembershipVerifier implements MembershipVerifier {

    private final MembershipService memberships;

    public LocalMembershipVerifier(MembershipService memberships) {
        this.memberships = memberships;
    }

    @Override
    public boolean isMember(UUID keycloakSub, String tin) {
        return memberships.isMember(keycloakSub, tin);
    }
}
