package uz.platform.organizationservice.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.platform.organizationservice.domain.Organization;
import uz.platform.organizationservice.domain.UserOrganization;
import uz.platform.organizationservice.repo.OrganizationRepository;
import uz.platform.organizationservice.repo.UserOrganizationRepository;

/**
 * Owns the answer to "may this person act for this organization".
 *
 * <p>Two responsibilities, and the split between them is the whole design:</p>
 *
 * <ul>
 *   <li><b>Reconciliation</b> writes memberships from what OneID reported,
 *       through the {@code org_tins} claim. This runs at session start.</li>
 *   <li><b>Verification</b> reads {@code user_organizations}. This runs on every
 *       organization-scoped request, in every service.</li>
 * </ul>
 *
 * <p>Because those are separate, the table can hold memberships OneID never
 * mentioned — granted by an administrator — and can drop one immediately without
 * waiting for a token to expire. If verification read the claim instead, neither
 * would be possible.</p>
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

    private static final String SOURCE_ONEID = "ONEID";

    private final OrganizationRepository organizations;
    private final UserOrganizationRepository memberships;

    public MembershipService(OrganizationRepository organizations, UserOrganizationRepository memberships) {
        this.organizations = organizations;
        this.memberships = memberships;
    }

    /**
     * Brings this person's OneID-sourced memberships in line with the claim.
     *
     * <p>Three rules, in order:</p>
     * <ol>
     *   <li>A TIN in the claim with no membership row gets one.</li>
     *   <li>A TIN in the claim whose row was deactivated is reactivated.</li>
     *   <li>A OneID-sourced row whose TIN is no longer in the claim is
     *       <b>deactivated, not deleted</b>, so that documents already filed for
     *       that organization still resolve it. Rows granted manually are left
     *       alone: OneID has no opinion about them.</li>
     * </ol>
     *
     * <p>An unknown TIN is skipped rather than auto-registered. Creating a legal
     * entity record from an unverified header value would let any caller
     * populate the organization table.</p>
     */
    @Transactional
    public List<String> reconcile(UUID keycloakSub, List<String> tinsFromToken) {
        List<UserOrganization> existing = memberships.findByKeycloakSub(keycloakSub);

        for (String tin : tinsFromToken) {
            Organization organization = organizations.findByTin(tin).orElse(null);
            if (organization == null) {
                log.warn("OneID reported TIN {} which is not a known organization; skipping", tin);
                continue;
            }
            existing.stream()
                    .filter(m -> m.getOrganization().getTin().equals(tin))
                    .findFirst()
                    .ifPresentOrElse(
                            UserOrganization::activate,
                            () -> memberships.save(new UserOrganization(
                                    keycloakSub, organization, false, SOURCE_ONEID)));
        }

        for (UserOrganization membership : existing) {
            boolean stillReported = tinsFromToken.contains(membership.getOrganization().getTin());
            if (!stillReported && SOURCE_ONEID.equals(membership.getSource())) {
                membership.deactivate();
            }
        }

        return activeTins(keycloakSub);
    }

    @Transactional(readOnly = true)
    public List<String> activeTins(UUID keycloakSub) {
        return memberships.findByKeycloakSubAndActiveTrue(keycloakSub).stream()
                .map(membership -> membership.getOrganization().getTin())
                .sorted()
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserOrganization> activeMemberships(UUID keycloakSub) {
        return memberships.findByKeycloakSubAndActiveTrue(keycloakSub);
    }

    @Transactional(readOnly = true)
    public boolean isMember(UUID keycloakSub, String tin) {
        return memberships.existsByKeycloakSubAndOrganization_TinAndActiveTrue(keycloakSub, tin);
    }
}
