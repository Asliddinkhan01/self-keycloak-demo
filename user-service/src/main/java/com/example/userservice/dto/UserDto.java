package com.example.userservice.dto;

import com.example.userservice.domain.AppUser;

/**
 * One row of user_service.app_user, as returned by GET /api/users.
 *
 * <p>There is no roles field, and that is the point. Earlier versions of this
 * demo kept roles in the service; they belong in Keycloak. If you need to know
 * what a user may do, read it from their token, not from this table.</p>
 */
public record UserDto(Long id, String keycloakId, String username, String fullName, String email) {

    public static UserDto from(AppUser user) {
        return new UserDto(
                user.getId(),
                user.getKeycloakId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail());
    }
}
