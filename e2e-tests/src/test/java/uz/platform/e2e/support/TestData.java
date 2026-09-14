package uz.platform.e2e.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Removes the projects and payments the tests create, so repeated runs do not
 * fill the development database the Vue app shows.
 *
 * <p>Connects as cadastral_service, the role that owns that schema, and touches
 * nothing but rows named with the test prefix. The tests never use the database
 * to arrange a result: everything they assert, they caused through the API.</p>
 */
public final class TestData {

    public static final String PROJECT_PREFIX = "e2e-";

    private TestData() {
    }

    public static void deleteCreatedRows() {
        String user = Platform.setting("CADASTRAL_DB_USER", "cadastral_service");
        // The local role's password, set in postgres/init/01-schemas.sql.
        String password = Platform.setting("CADASTRAL_DB_PASSWORD", "cadastral_service");
        try (Connection connection = DriverManager.getConnection(Platform.DATABASE_URL, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    DELETE FROM cadastral_service.payments
                    WHERE project_id IN (SELECT id FROM cadastral_service.projects WHERE name LIKE 'e2e-%')""");
            statement.executeUpdate("DELETE FROM cadastral_service.projects WHERE name LIKE 'e2e-%'");
        } catch (SQLException e) {
            throw new IllegalStateException("could not remove e2e test rows", e);
        }
    }
}
