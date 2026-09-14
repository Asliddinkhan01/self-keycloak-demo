package uz.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PermissionCatalog: the cached role -> permission mapping")
class PermissionCatalogTest {

    static PermissionCatalog catalogOf(Map<String, Set<String>> mapping) {
        PermissionCatalog catalog = new PermissionCatalog(() -> mapping);
        catalog.afterPropertiesSet();
        return catalog;
    }

    @Test
    @DisplayName("12. a change in the table is invisible until the next refresh, then reflected")
    void changesAreReflectedAfterRefresh() {
        AtomicReference<Map<String, Set<String>>> table =
                new AtomicReference<>(Map.of("QURUVCHI", Set.of("PROJECT_READ", "PROJECT_CREATE")));
        PermissionCatalog catalog = new PermissionCatalog(table::get);
        catalog.afterPropertiesSet();
        assertThat(catalog.permissionsFor(List.of("QURUVCHI"))).contains("PROJECT_CREATE");

        // An administrator revokes PROJECT_CREATE in the database.
        table.set(Map.of("QURUVCHI", Set.of("PROJECT_READ")));
        assertThat(catalog.permissionsFor(List.of("QURUVCHI")))
                .as("still the cached mapping: no database read per request")
                .contains("PROJECT_CREATE");

        catalog.refresh();
        assertThat(catalog.permissionsFor(List.of("QURUVCHI"))).containsExactly("PROJECT_READ");
    }

    @Test
    @DisplayName("a failed refresh keeps the last good mapping rather than locking everyone out")
    void failedRefreshKeepsLastGoodMapping() {
        AtomicBoolean databaseDown = new AtomicBoolean(false);
        PermissionCatalog catalog = new PermissionCatalog(() -> {
            if (databaseDown.get()) {
                throw new IllegalStateException("database unavailable (expected by this test)");
            }
            return Map.of("BANK", Set.of("PAYMENT_READ"));
        });
        catalog.afterPropertiesSet();

        databaseDown.set(true);
        catalog.refresh();

        assertThat(catalog.permissionsFor(List.of("BANK"))).containsExactly("PAYMENT_READ");
    }

    @Test
    @DisplayName("roles the service has no rows for grant nothing")
    void unknownRolesGrantNothing() {
        PermissionCatalog catalog = catalogOf(Map.of("BANK", Set.of("PAYMENT_READ")));

        assertThat(catalog.permissionsFor(List.of("SUPER_ADMIN", "offline_access", "default-roles-platform")))
                .isEmpty();
    }
}
