package uz.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.JsonNode;

import uz.platform.e2e.support.Http;
import uz.platform.e2e.support.Http.Response;
import uz.platform.e2e.support.Jwts;
import uz.platform.e2e.support.Keycloak;
import uz.platform.e2e.support.Keycloak.Tokens;
import uz.platform.e2e.support.OneIdBrowser;
import uz.platform.e2e.support.Platform;
import uz.platform.e2e.support.RunningPlatform;

@ExtendWith(RunningPlatform.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Authentication")
class AuthenticationE2ETest {

    private static OneIdBrowser browser;
    private static Tokens tokens;

    @BeforeAll
    static void signInThroughOneId() {
        browser = new OneIdBrowser();
        tokens = browser.signIn("akarimov");
    }

    @Test
    @DisplayName("1. User logs in with OneID")
    void userLogsInWithOneId() {
        assertThat(browser.visited())
                .as("the redirect chain a browser follows")
                .anySatisfy(hop -> assertThat(hop).contains("/broker/oneid/login"))
                .anySatisfy(hop -> assertThat(hop).startsWith("200 GET " + Platform.MOCK_ONEID).contains("response_type=one_code"))
                .anySatisfy(hop -> assertThat(hop).contains("POST " + Platform.MOCK_ONEID + "/sso/oauth/select"))
                .anySatisfy(hop -> assertThat(hop).contains("/broker/oneid/endpoint"));
        assertThat(tokens.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("2. Keycloak creates or fetches the user")
    void keycloakCreatesOrFetchesTheUser() {
        JsonNode user = Keycloak.user("akarimov");
        String userId = user.path("id").asText();
        assertThat(tokens.subject()).as("the token is about the stored user").isEqualTo(userId);

        assertThat(Keycloak.admin("/users/" + userId + "/federated-identity")).anySatisfy(link -> {
            assertThat(link.path("identityProvider").asText()).isEqualTo("oneid");
            assertThat(link.path("userName").asText()).isEqualTo("akarimov");
        });

        JsonNode attributes = user.path("attributes");
        assertThat(Http.texts(attributes.path("org_tins"))).containsExactlyInAnyOrder("111111111", "222222222");
        assertThat(Http.texts(attributes.path("identity_verified"))).containsExactly("true");
        assertThat(attributes.path("oneid_pin").path(0).asText()).as("the PIN, stored inside Keycloak").hasSize(14);
        assertThat(Keycloak.effectiveRealmRoles(userId)).contains("JISMONIY_SHAXS", "YURIDIK_SHAXS");

        Tokens again = new OneIdBrowser().signIn("akarimov");
        assertThat(again.subject())
                .as("a second login fetches the same user instead of creating another")
                .isEqualTo(tokens.subject());
    }

    @Test
    @DisplayName("3. Keycloak issues JWT")
    void keycloakIssuesJwt() {
        JsonNode claims = tokens.claims();

        assertThat(Jwts.header(tokens.accessToken()).path("alg").asText()).isEqualTo("RS256");
        assertThat(Jwts.signedBy(tokens.accessToken(), Keycloak.jwks()))
                .as("signature verifies against the realm's published keys").isTrue();
        assertThat(claims.path("iss").asText()).isEqualTo(Platform.REALM);
        assertThat(Http.texts(claims.path("aud"))).contains("platform-api");
        assertThat(claims.path("azp").asText()).isEqualTo("platform-web");
        assertThat(claims.path("token_use").asText()).isEqualTo("user");
        assertThat(claims.path("preferred_username").asText()).isEqualTo("akarimov");
        assertThat(Http.texts(claims.path("org_tins"))).containsExactlyInAnyOrder("111111111", "222222222");
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).as("short-lived").isLessThanOrEqualTo(300);

        String pin = Keycloak.user("akarimov").path("attributes").path("oneid_pin").path(0).asText();
        assertThat(pin).isNotBlank();
        assertThat(claims.toString()).as("the PIN never enters an access token").doesNotContain(pin).doesNotContain("oneid_pin");
        assertThat(Jwts.claims(tokens.idToken()).toString()).as("nor an ID token").doesNotContain(pin);
    }

    @Test
    @DisplayName("4. Vue calls backend")
    void vueCallsBackend() {
        Response preflight = Http.options(Platform.GATEWAY + "/api/projects")
                .header("Origin", Platform.APP_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,x-organization-tin")
                .send();
        assertThat(preflight.status()).as("CORS preflight from the Vue origin").isEqualTo(200);
        assertThat(preflight.header("Access-Control-Allow-Origin")).isEqualTo(Platform.APP_ORIGIN);
        assertThat(preflight.header("Access-Control-Allow-Headers"))
                .containsIgnoringCase("authorization")
                .containsIgnoringCase("x-organization-tin");

        Response me = Http.get(Platform.GATEWAY + "/api/users/me")
                .header("Origin", Platform.APP_ORIGIN)
                .bearer(tokens.accessToken())
                .send();
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.header("Access-Control-Allow-Origin")).isEqualTo(Platform.APP_ORIGIN);
        assertThat(me.json().path("username").asText()).isEqualTo("akarimov");

        Response foreignOrigin = Http.options(Platform.GATEWAY + "/api/projects")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .send();
        assertThat(foreignOrigin.status()).as("any other origin").isEqualTo(403);
        assertThat(foreignOrigin.header("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("5. Backend accepts JWT")
    void backendAcceptsJwt() {
        String token = tokens.accessToken();
        assertThat(status(Platform.GATEWAY, token)).as("through the gateway").isEqualTo(200);
        assertThat(status(Platform.USER_SERVICE, token)).as("straight to the service, which validates on its own").isEqualTo(200);

        String escalated = Jwts.withClaims(token, claims -> {
            claims.put("preferred_username", "super_admin");
            claims.putObject("realm_access").putArray("roles").add("SUPER_ADMIN");
        });
        assertThat(status(Platform.GATEWAY, escalated)).as("edited claims under the original signature").isEqualTo(401);
        assertThat(status(Platform.USER_SERVICE, escalated)).as("refused by the service too").isEqualTo(401);
        assertThat(status(Platform.GATEWAY, Jwts.unsigned(token))).as("alg none").isEqualTo(401);
        assertThat(status(Platform.GATEWAY, Keycloak.masterRealmToken())).as("a genuine token from another issuer").isEqualTo(401);
        assertThat(Http.get(Platform.GATEWAY + "/api/users/me").send().status()).as("no token").isEqualTo(401);
    }

    private static int status(String baseUrl, String token) {
        return Http.get(baseUrl + "/api/users/me").bearer(token).send().status();
    }
}
