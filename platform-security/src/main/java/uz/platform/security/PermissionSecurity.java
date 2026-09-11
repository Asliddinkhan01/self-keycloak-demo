package uz.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on database-backed permissions for a service.
 *
 * <p>Imported separately from {@link ResourceServerSecurity} on purpose. Every
 * service validates tokens, but only services with their own
 * {@code role_permissions} table resolve permissions — the API gateway, for
 * instance, validates tokens at the edge and makes no business authorization
 * decision, so it imports the first and not this.</p>
 *
 * <p>A service opts in by importing this configuration and declaring one bean:
 * a {@link RolePermissionSource} over its own table.</p>
 */
@Configuration
@EnableScheduling
public class PermissionSecurity {

    /**
     * Reads the service's own {@code role_permissions} table.
     *
     * <p>Conditional so a service can supply its own implementation — a
     * different table layout, or a source that is not a database — without
     * editing the shared library.</p>
     */
    @Bean
    @ConditionalOnMissingBean(RolePermissionSource.class)
    public RolePermissionSource rolePermissionSource(JdbcTemplate jdbcTemplate) {
        return new JdbcRolePermissionSource(jdbcTemplate);
    }

    @Bean
    public PermissionCatalog permissionCatalog(RolePermissionSource source) {
        return new PermissionCatalog(source);
    }

    /**
     * Named {@code permissionChecker} deliberately: that is the name the
     * {@code @PreAuthorize} expressions reference, so renaming the bean would
     * break every annotation at runtime rather than at compile time.
     */
    @Bean("permissionChecker")
    public PermissionChecker permissionChecker() {
        return new PermissionChecker();
    }
}
