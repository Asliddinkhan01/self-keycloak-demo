package uz.platform.organizationservice.web;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uz.platform.security.CallerType;
import uz.platform.security.PlatformClaims;

/**
 * Endpoints other services call. Never a browser.
 *
 * <p>Access is enforced by the {@code /internal/**} rule in the shared
 * {@code ResourceServerSecurity}: a caller must present a token whose
 * {@code token_use} is {@code service}. A perfectly valid human token gets 403
 * here — authenticated, and still the wrong kind of caller.</p>
 *
 * <p>Phase 6 adds the real membership endpoint behind this same rule, and phase 7
 * has cadastral-service call it with a client-credentials token.</p>
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    /**
     * Reports what the service token actually carries.
     *
     * <p>Useful as a diagnostic: it shows the audience the token was minted for
     * and the client roles granted against <em>this</em> service, which is how
     * least privilege is verified rather than assumed.</p>
     */
    @GetMapping("/ping")
    public Map<String, Object> ping(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        return Map.of(
                "service", "organization-service",
                "callerType", CallerType.of(jwt).name(),
                "callingClient", String.valueOf(jwt.getClaimAsString(PlatformClaims.AUTHORIZED_PARTY)),
                "principal", authentication.getName(),
                "audience", jwt.getAudience(),
                "rolesGrantedHere", PlatformClaims.clientRoles(jwt, "organization-service"));
    }
}
