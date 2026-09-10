package com.example.userservice.dto;

import java.util.List;

/**
 * What GET /api/users/me returns.
 *
 * <p>It deliberately shows three layers at once so you can compare them:</p>
 * <ul>
 *   <li>{@code realmRoles} is the raw claim as Keycloak wrote it;</li>
 *   <li>{@code authorities} is what Spring Security built from that claim and
 *       actually evaluates inside hasRole(...);</li>
 *   <li>{@code localProfileId} is the primary key of this caller's row in
 *       user_service.app_user, proving the token was joined to local data.</li>
 * </ul>
 */
public record MeDto(
        String username,
        String email,
        String fullName,
        String subject,
        String issuer,
        Long localProfileId,
        List<String> realmRoles,
        List<String> authorities) {
}
