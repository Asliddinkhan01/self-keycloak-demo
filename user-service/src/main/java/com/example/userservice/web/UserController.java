package com.example.userservice.web;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.userservice.domain.AppUser;
import com.example.userservice.dto.MeDto;
import com.example.userservice.dto.UserDto;
import com.example.userservice.service.UserProfileService;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserProfileService userProfileService;

    public UserController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /** ADMIN only. Reads user_service.app_user. Enforced by SecurityConfig. */
    @GetMapping
    public List<UserDto> listUsers() {
        return userProfileService.findAll().stream().map(UserDto::from).toList();
    }

    /**
     * USER only.
     *
     * <p>This is where the two halves of the system meet. Identity comes from the
     * JWT, which Spring already verified; the profile row comes from PostgreSQL,
     * created or linked on demand. Call it once as a freshly registered user and
     * a new row appears in user_service.app_user.</p>
     */
    @GetMapping("/me")
    public MeDto me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        AppUser profile = userProfileService.findOrCreateFrom(jwt);

        return new MeDto(
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                jwt.getSubject(),
                String.valueOf(jwt.getIssuer()),
                profile.getId(),
                realmRoles(jwt),
                authorities(authentication));
    }

    /**
     * USER only. Dumps every claim of the access token.
     * Handy while learning: call it and compare the output with jwt.io.
     */
    @GetMapping("/me/claims")
    public Map<String, Object> claims(@AuthenticationPrincipal Jwt jwt) {
        return jwt.getClaims();
    }

    private static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            return roles.stream().map(String::valueOf).sorted().toList();
        }
        return List.of();
    }

    private static List<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
    }
}
