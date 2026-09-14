package uz.platform.e2e.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Where the running platform is, and the few secrets the tests need to act as
 * its clients.
 *
 * <p>Settings come from environment variables first and from the repository's
 * {@code .env} second, the same file docker compose reads. Secrets have no
 * defaults here: a missing one fails with a message saying which.</p>
 */
public final class Platform {

    private static final Map<String, String> DOT_ENV = readDotEnv();

    public static final String KEYCLOAK = "http://localhost:" + setting("KEYCLOAK_PORT", "8190");
    public static final String REALM = KEYCLOAK + "/realms/" + setting("KEYCLOAK_REALM", "platform");
    public static final String GATEWAY = "http://localhost:" + setting("GATEWAY_PORT", "8090");
    public static final String USER_SERVICE = "http://localhost:" + setting("USER_SERVICE_PORT", "8091");
    public static final String ORGANIZATION_SERVICE = "http://localhost:" + setting("ORGANIZATION_SERVICE_PORT", "8092");
    public static final String CADASTRAL_SERVICE = "http://localhost:" + setting("CADASTRAL_SERVICE_PORT", "8093");
    public static final String MOCK_ONEID = "http://localhost:" + setting("MOCK_ONEID_PORT", "8191");

    /** The Vue app's origin, for CORS. */
    public static final String APP_ORIGIN = "http://localhost:" + setting("FRONTEND_PORT", "5174");
    /** Where Keycloak sends the browser after logout. */
    public static final String APP = APP_ORIGIN + "/";
    /** Where Keycloak sends the sign-in popup with the authorization code. */
    public static final String LOGIN_CALLBACK = APP_ORIGIN + "/auth-callback.html";

    public static final String DATABASE_URL =
            "jdbc:postgresql://localhost:" + setting("POSTGRES_PORT", "5452") + "/" + setting("POSTGRES_DB", "appdb");

    /**
     * The password of every development user in realm-export.json. Local fixture
     * data committed with the realm, not a secret of any real system.
     */
    public static final String DEV_USER_PASSWORD = "password";

    private Platform() {
    }

    public static String secret(String name) {
        String value = setting(name, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set. Export it, or copy .env.example to .env "
                    + "at the repository root.");
        }
        return value;
    }

    public static String setting(String name, String fallback) {
        String fromEnvironment = System.getenv(name);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        return DOT_ENV.getOrDefault(name, fallback);
    }

    /** The repository root is the nearest directory upwards holding docker-compose.yml. */
    private static Map<String, String> readDotEnv() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("docker-compose.yml"))) {
            dir = dir.getParent();
        }
        if (dir == null || !Files.exists(dir.resolve(".env"))) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(dir.resolve(".env"))) {
                String trimmed = line.trim();
                int equals = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 1) {
                    continue;
                }
                values.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return values;
    }
}
