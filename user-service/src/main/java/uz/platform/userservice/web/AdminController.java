package uz.platform.userservice.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import uz.platform.security.PermissionCatalog;
import uz.platform.userservice.domain.AppUser;
import uz.platform.userservice.repo.AppUserRepository;

/**
 * Administrative endpoints, gated by <b>permissions</b> rather than roles.
 *
 * <p>Phase 4 protected these with {@code hasRole('ADMIN')}. They now require
 * {@code USER_READ}, {@code USER_UPDATE} and {@code USER_DELETE}, and the
 * difference is not cosmetic. A role check hardcodes an organisational fact into
 * code, so letting another role manage users would mean editing and redeploying
 * every affected endpoint. A permission check states a stable requirement, and
 * granting it to another role is one row in {@code role_permissions}.</p>
 *
 * <p>The {@code @permissionChecker.has(...)} form and {@code hasAuthority(...)}
 * are equivalent — permissions are already granted authorities by the time these
 * run. Both appear below on purpose so the two spellings are visible side by
 * side.</p>
 */
@RestController
public class AdminController {

    private final AppUserRepository users;
    private final PermissionCatalog permissionCatalog;

    public AdminController(AppUserRepository users, PermissionCatalog permissionCatalog) {
        this.users = users;
        this.permissionCatalog = permissionCatalog;
    }

    /** ADMIN and SUPER_ADMIN both hold USER_READ. */
    @PreAuthorize("@permissionChecker.has(authentication, 'USER_READ')")
    @GetMapping("/api/admin/users")
    public List<Map<String, Object>> listUsers() {
        return users.findAllByOrderByOneidUserIdAsc().stream().map(AdminController::toView).toList();
    }

    /** ADMIN and SUPER_ADMIN both hold USER_UPDATE. */
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    @PutMapping("/api/admin/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable UUID id,
                                                          @RequestBody Map<String, String> body) {
        return users.findById(id)
                .map(user -> {
                    user.rename(body.get("fullName"));
                    return ResponseEntity.ok(toView(users.save(user)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * ADMIN holds USER_DELETE. SUPER_ADMIN does <b>not</b>.
     *
     * <p>That looks surprising until you read it as intended: the platform
     * administrator is not defined as "everything an administrator can do, plus
     * more". Roles here are sets of capabilities, not levels, and nothing in the
     * design makes one contain another.</p>
     */
    @PreAuthorize("hasAuthority('USER_DELETE')")
    @DeleteMapping("/api/admin/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        if (!users.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        users.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * SUPER_ADMIN only, through PLATFORM_ADMIN, which no other role holds.
     *
     * <p>Returns the live role-to-permission mapping this service enforces, which
     * makes the cached catalog inspectable without a database client.</p>
     */
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
    @GetMapping("/api/platform/audit")
    public Map<String, Object> audit(Authentication authentication) {
        return Map.of(
                "message", "Platform-level endpoint. ADMIN cannot reach this; SUPER_ADMIN can.",
                "caller", authentication.getName(),
                "profileCount", users.count(),
                "rolePermissionsEnforcedHere", permissionCatalog.asMap());
    }

    private static Map<String, Object> toView(AppUser user) {
        return Map.of(
                "id", user.getId(),
                "keycloakSub", user.getKeycloakSub(),
                "oneidUserId", String.valueOf(user.getOneidUserId()),
                "fullName", String.valueOf(user.getFullName()),
                "identityVerified", user.isIdentityVerified());
    }
}
