package uz.platform.security;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads {@code role_permissions} from the service's own schema.
 *
 * <p>Plain JDBC rather than JPA, on purpose. This is small, read-only reference
 * data loaded in one query a few times an hour; an entity, a repository and a
 * composite key would be three files of ceremony around a single {@code SELECT}.
 * No table name is qualified with a schema, because each service connects as its
 * own PostgreSQL role whose {@code search_path} already points at its own
 * schema — so this one query is correct in all three services without
 * configuration.</p>
 */
public class JdbcRolePermissionSource implements RolePermissionSource {

    private static final String QUERY = """
            SELECT role_code, permission_code
            FROM role_permissions
            ORDER BY role_code, permission_code
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRolePermissionSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Set<String>> load() {
        Map<String, Set<String>> byRole = new HashMap<>();
        jdbcTemplate.query(QUERY, rs -> {
            byRole.computeIfAbsent(rs.getString("role_code"), key -> new LinkedHashSet<>())
                  .add(rs.getString("permission_code"));
        });
        // Map.copyOf would reject nothing here, but the defensive copy makes the
        // published map immutable, which is what lets PermissionCatalog swap it
        // in without a lock.
        return Map.copyOf(byRole);
    }
}
