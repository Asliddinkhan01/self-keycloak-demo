package uz.platform.e2e.support;

import static java.nio.charset.StandardCharsets.UTF_8;
import static uz.platform.e2e.support.Platform.APP;
import static uz.platform.e2e.support.Platform.DEV_USER_PASSWORD;
import static uz.platform.e2e.support.Platform.KEYCLOAK;
import static uz.platform.e2e.support.Platform.LOGIN_CALLBACK;
import static uz.platform.e2e.support.Platform.REALM;
import static uz.platform.e2e.support.Platform.secret;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import uz.platform.e2e.support.Http.Response;

/** Keycloak's public endpoints as a client uses them, and its admin API for inspection. */
public final class Keycloak {

    private static final String TOKEN = REALM + "/protocol/openid-connect/token";

    public record Tokens(String accessToken, String refreshToken, String idToken) {

        public JsonNode claims() {
            return Jwts.claims(accessToken);
        }

        public String subject() {
            return claims().path("sub").asText();
        }

        public String sessionId() {
            return claims().path("sid").asText();
        }

        public long expiresAt() {
            return claims().path("exp").asLong();
        }
    }

    private Keycloak() {
    }

    // ---- as platform-web ----------------------------------------------------

    /** A development user's tokens by password grant, which platform-web allows for scripted checks. */
    public static Tokens password(String username) {
        Response response = Http.post(TOKEN).form(Map.of(
                "grant_type", "password",
                "client_id", "platform-web",
                "scope", "openid",
                "username", username,
                "password", DEV_USER_PASSWORD)).send();
        return tokens(expect(response, 200, "password grant for " + username));
    }

    /** The authorization-code exchange the app performs after the popup, PKCE verifier included. */
    public static Tokens exchangeCode(String code, String codeVerifier) {
        Response response = Http.post(TOKEN).form(Map.of(
                "grant_type", "authorization_code",
                "client_id", "platform-web",
                "code", code,
                "redirect_uri", LOGIN_CALLBACK,
                "code_verifier", codeVerifier)).send();
        return tokens(expect(response, 200, "authorization code exchange"));
    }

    public static Response refresh(String refreshToken) {
        return Http.post(TOKEN).form(Map.of(
                "grant_type", "refresh_token",
                "client_id", "platform-web",
                "refresh_token", refreshToken)).send();
    }

    public static Response userinfo(String accessToken) {
        return Http.get(REALM + "/protocol/openid-connect/userinfo").bearer(accessToken).send();
    }

    /**
     * The URL the app's sign-in popup opens. {@code kc_idp_hint=oneid} makes Keycloak skip
     * its own sign-in page and go straight to OneID.
     */
    public static String authorizationUrl(String codeChallenge) {
        return REALM + "/protocol/openid-connect/auth?" + Http.formEncode(Map.of(
                "client_id", "platform-web",
                "redirect_uri", LOGIN_CALLBACK,
                "response_type", "code",
                "response_mode", "query",
                "scope", "openid",
                "state", UUID.randomUUID().toString(),
                "code_challenge", codeChallenge,
                "code_challenge_method", "S256",
                "kc_idp_hint", "oneid"));
    }

    /** The end-session URL exactly as keycloak-js builds it for logout(). */
    public static String endSessionUrl(String idToken) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", "platform-web");
        query.put("post_logout_redirect_uri", APP);
        query.put("id_token_hint", idToken);
        return REALM + "/protocol/openid-connect/logout?" + Http.formEncode(query);
    }

    public static JsonNode jwks() {
        return expect(Http.get(REALM + "/protocol/openid-connect/certs").send(), 200, "JWKS");
    }

    // ---- as a confidential service client -----------------------------------

    public static Response clientCredentials(String clientId, String clientSecret) {
        return Http.post(TOKEN).form(Map.of(
                "grant_type", "client_credentials",
                "client_id", clientId,
                "client_secret", clientSecret)).send();
    }

    /** A service's access token, with its secret from USER_SERVICE_CLIENT_SECRET and the like. */
    public static String serviceToken(String clientId) {
        String secret = secret(clientId.toUpperCase(Locale.ROOT).replace('-', '_') + "_CLIENT_SECRET");
        return expect(clientCredentials(clientId, secret), 200, "client_credentials for " + clientId)
                .path("access_token").asText();
    }

    /** Keycloak's own view of a token, asked as user-service, which is a confidential client. */
    public static boolean isActive(String token) {
        String credentials = Base64.getEncoder()
                .encodeToString(("user-service:" + secret("USER_SERVICE_CLIENT_SECRET")).getBytes(UTF_8));
        Response response = Http.post(TOKEN + "/introspect")
                .header("Authorization", "Basic " + credentials)
                .form(Map.of("token", token))
                .send();
        return expect(response, 200, "token introspection").path("active").asBoolean();
    }

    // ---- admin API, for inspecting what Keycloak stored ----------------------

    /** A real Keycloak token, from the master realm: a different issuer than every service trusts. */
    public static String masterRealmToken() {
        Response response = Http.post(KEYCLOAK + "/realms/master/protocol/openid-connect/token").form(Map.of(
                "grant_type", "password",
                "client_id", "admin-cli",
                "username", secret("KEYCLOAK_ADMIN_USER"),
                "password", secret("KEYCLOAK_ADMIN_PASSWORD"))).send();
        return expect(response, 200, "master realm admin token").path("access_token").asText();
    }

    public static JsonNode admin(String path) {
        return expect(Http.get(adminUrl(path)).bearer(masterRealmToken()).send(), 200, "GET admin" + path);
    }

    public static JsonNode user(String username) {
        JsonNode found = admin("/users?exact=true&username=" + URLEncoder.encode(username, UTF_8));
        if (found.size() != 1) {
            throw new AssertionError("expected exactly one Keycloak user " + username + ", found " + found.size());
        }
        return found.get(0);
    }

    public static List<String> sessionIds(String userId) {
        List<String> ids = new ArrayList<>();
        admin("/users/" + userId + "/sessions").forEach(session -> ids.add(session.path("id").asText()));
        return ids;
    }

    /** Effective realm roles, composites expanded: what the user actually holds. */
    public static List<String> effectiveRealmRoles(String userId) {
        List<String> names = new ArrayList<>();
        admin("/users/" + userId + "/role-mappings/realm/composite").forEach(role -> names.add(role.path("name").asText()));
        return names;
    }

    public static JsonNode client(String clientId) {
        return admin("/clients?clientId=" + URLEncoder.encode(clientId, UTF_8)).get(0);
    }

    public static void setClientAttribute(String clientId, String attribute, String value) {
        ObjectNode client = (ObjectNode) client(clientId);
        JsonNode attributes = client.path("attributes");
        ObjectNode editable = attributes.isObject() ? (ObjectNode) attributes : client.putObject("attributes");
        editable.put(attribute, value);
        Response response = Http.put(adminUrl("/clients/" + client.path("id").asText()))
                .bearer(masterRealmToken())
                .json(client)
                .send();
        if (response.status() != 204) {
            throw new AssertionError("updating client " + clientId + ": " + response);
        }
    }

    // ----------------------------------------------------------------------------

    public static JsonNode expect(Response response, int status, String what) {
        if (response.status() != status) {
            throw new AssertionError(what + ": expected " + status + ", got " + response);
        }
        return response.body() == null || response.body().isBlank() ? Http.JSON.nullNode() : response.json();
    }

    private static Tokens tokens(JsonNode json) {
        return new Tokens(
                json.path("access_token").asText(null),
                json.path("refresh_token").asText(null),
                json.path("id_token").asText(null));
    }

    private static String adminUrl(String path) {
        return KEYCLOAK + "/admin/realms/" + Platform.setting("KEYCLOAK_REALM", "platform") + path;
    }
}
