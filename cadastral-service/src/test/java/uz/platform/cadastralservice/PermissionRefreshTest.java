package uz.platform.cadastralservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import uz.platform.security.JdbcRolePermissionSource;
import uz.platform.security.KeycloakAuthoritiesConverter;
import uz.platform.security.PermissionCatalog;

/**
 * Scenarios 9–12 against this service's real schema and seed data.
 *
 * <p>A throwaway PostgreSQL receives exactly the Flyway migrations the service
 * runs at startup, and the production classes read it. The running service
 * refreshes its catalog every five minutes ({@code
 * platform.security.permission-refresh-ms}); the test calls {@code refresh()}
 * where the scheduler would, instead of waiting five minutes.</p>
 *
 * <p>Needs Docker. Without it the class is skipped, not failed, so a plain
 * build still passes on a machine without Docker.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("cadastral-service permissions, read from its real migrations")
class PermissionRefreshTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    private static JdbcTemplate database;
    private static PermissionCatalog catalog;
    private static KeycloakAuthoritiesConverter converter;

    @BeforeAll
    static void migrateTheRealSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("cadastral_service")
                .defaultSchema("cadastral_service")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setSchema("cadastral_service");
        database = new JdbcTemplate(dataSource);

        catalog = new PermissionCatalog(new JdbcRolePermissionSource(database));
        catalog.afterPropertiesSet();
        converter = new KeycloakAuthoritiesConverter("cadastral-service", catalog);
    }

    @Test
    @DisplayName("9, 10, 11. the seed data grants PROJECT_CREATE to QURUVCHI only, and unions roles")
    void seedDataGrantsWhatTheDesignSays() {
        assertThat(permissionsOf("QURUVCHI")).contains("PROJECT_CREATE");
        assertThat(permissionsOf("BANK")).contains("PAYMENT_CREATE").doesNotContain("PROJECT_CREATE");
        assertThat(permissionsOf("JISMONIY_SHAXS")).containsExactly("PROJECT_READ");
        assertThat(permissionsOf("QURUVCHI", "BANK")).contains("PROJECT_CREATE", "PAYMENT_CREATE");
        assertThat(permissionsOf("SUPER_ADMIN")).as("no wildcard role").isEmpty();
    }

    @Test
    @DisplayName("12. Permission changes are reflected after the refresh, and not before")
    void revocationTakesEffectAtRefresh() {
        try {
            database.update("DELETE FROM role_permissions WHERE role_code = 'QURUVCHI' AND permission_code = 'PROJECT_CREATE'");

            assertThat(permissionsOf("QURUVCHI"))
                    .as("before refresh: the cached mapping, so no database read per request")
                    .contains("PROJECT_CREATE");

            catalog.refresh();
            assertThat(permissionsOf("QURUVCHI")).as("after refresh").doesNotContain("PROJECT_CREATE");

            database.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('BANK', 'PROJECT_READ')");
            catalog.refresh();
            assertThat(permissionsOf("BANK")).as("a grant takes effect the same way").contains("PROJECT_READ");
        } finally {
            database.update("DELETE FROM role_permissions WHERE role_code = 'BANK' AND permission_code = 'PROJECT_READ'");
            database.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('QURUVCHI', 'PROJECT_CREATE') ON CONFLICT DO NOTHING");
            catalog.refresh();
        }
    }

    /** The authorities a token with these realm roles receives in this service, filtered to permissions. */
    private static Set<String> permissionsOf(String... realmRoles) {
        Instant now = Instant.now();
        Jwt token = Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("token_use", "user")
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
        return converter.convert(token).stream()
                .map(GrantedAuthority::getAuthority)
                .filter(name -> name.startsWith("PROJECT_") || name.startsWith("PAYMENT_"))
                .collect(Collectors.toSet());
    }
}
