package uz.platform.e2e.support;

import static uz.platform.e2e.support.Platform.GATEWAY;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import uz.platform.e2e.support.Http.Request;
import uz.platform.e2e.support.Http.Response;
import uz.platform.e2e.support.Keycloak.Tokens;

/** The public API as the Vue app calls it: always through the gateway. */
public final class Api {

    public static final String TIN_HEADER = "X-Organization-TIN";

    private static final Map<String, Tokens> SIGNED_IN = new ConcurrentHashMap<>();

    private Api() {
    }

    /**
     * A development user's access token, after the same bootstrap call the Vue
     * app makes first, which reconciles their organization memberships.
     */
    public static String signedIn(String username) {
        return SIGNED_IN.compute(username, (name, existing) ->
                existing != null && existing.expiresAt() - Instant.now().getEpochSecond() > 60
                        ? existing
                        : bootstrap(name)).accessToken();
    }

    public static String subjectOf(String username) {
        return Jwts.claims(signedIn(username)).path("sub").asText();
    }

    private static Tokens bootstrap(String username) {
        Tokens tokens = Keycloak.password(username);
        Keycloak.expect(me(tokens.accessToken()), 200, "session bootstrap GET /api/users/me for " + username);
        return tokens;
    }

    public static Response me(String token) {
        return Http.get(GATEWAY + "/api/users/me").bearer(token).send();
    }

    public static Response projects(String token, String tin) {
        return withTin(Http.get(GATEWAY + "/api/projects").bearer(token), tin).send();
    }

    public static Response createProject(String token, String tin) {
        return withTin(Http.post(GATEWAY + "/api/projects").bearer(token), tin)
                .json(Map.of("name", TestData.PROJECT_PREFIX + UUID.randomUUID(), "address", "Toshkent shahri"))
                .send();
    }

    public static Response updateProject(String token, String tin, String projectId) {
        return withTin(Http.put(GATEWAY + "/api/projects/" + projectId).bearer(token), tin)
                .json(Map.of("name", TestData.PROJECT_PREFIX + "renamed-" + UUID.randomUUID(), "address", "Samarqand"))
                .send();
    }

    public static Response payments(String token, String tin) {
        return withTin(Http.get(GATEWAY + "/api/payments").bearer(token), tin).send();
    }

    public static Response createPayment(String token, String tin, String projectId, String amount) {
        return withTin(Http.post(GATEWAY + "/api/payments").bearer(token), tin)
                .json(Map.of("projectId", projectId, "amount", new BigDecimal(amount)))
                .send();
    }

    public static Response currentOrganization(String token, String tin) {
        return withTin(Http.get(GATEWAY + "/api/organizations/current").bearer(token), tin).send();
    }

    public static Response get(String path, String token) {
        return Http.get(GATEWAY + path).bearer(token).send();
    }

    private static Request withTin(Request request, String tin) {
        return tin == null ? request : request.header(TIN_HEADER, tin);
    }
}
