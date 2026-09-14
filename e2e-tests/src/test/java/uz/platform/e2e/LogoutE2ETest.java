package uz.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

import uz.platform.e2e.support.Api;
import uz.platform.e2e.support.Http.Response;
import uz.platform.e2e.support.Keycloak;
import uz.platform.e2e.support.Keycloak.Tokens;
import uz.platform.e2e.support.OneIdBrowser;
import uz.platform.e2e.support.Platform;
import uz.platform.e2e.support.RunningPlatform;

/**
 * Logout from our application, through OneID-brokered sessions. Scenario 25,
 * the frontend forgetting its tokens, is browser code and is tested in
 * frontend/src/keycloak.test.js.
 */
@ExtendWith(RunningPlatform.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Logout")
class LogoutE2ETest {

    @Test
    @DisplayName("23. User logs out from our application")
    void userLogsOut() {
        OneIdBrowser browser = new OneIdBrowser();
        Tokens tokens = browser.signIn("akarimov");

        Response logout = browser.endSession(tokens.idToken());

        assertThat(logout.status()).as("no confirmation page").isEqualTo(302);
        assertThat(logout.location()).isEqualTo(Platform.APP);
    }

    @Test
    @DisplayName("24. Keycloak session is terminated")
    void keycloakSessionIsTerminated() {
        OneIdBrowser browser = new OneIdBrowser();
        Tokens tokens = browser.signIn("akarimov");
        assertThat(Keycloak.sessionIds(tokens.subject())).contains(tokens.sessionId());

        browser.endSession(tokens.idToken());

        assertThat(Keycloak.sessionIds(tokens.subject())).as("gone on the server").doesNotContain(tokens.sessionId());
        Response refresh = Keycloak.refresh(tokens.refreshToken());
        assertThat(refresh.status()).isEqualTo(400);
        assertThat(refresh.json().path("error").asText()).isEqualTo("invalid_grant");
        Response signInAgain = browser.openSignIn();
        assertThat(signInAgain.status()).as("the same browser is asked to sign in again").isEqualTo(200);
        assertThat(signInAgain.body()).contains("broker/oneid/login");
    }

    @Test
    @DisplayName("26. Protected API access is no longer available through the logged-out session")
    void protectedApiNoLongerAvailableThroughTheSession() {
        OneIdBrowser browser = new OneIdBrowser();
        Tokens tokens = browser.signIn("akarimov");
        assertThat(Api.me(tokens.accessToken()).status()).isEqualTo(200);

        browser.endSession(tokens.idToken());

        assertThat(Keycloak.refresh(tokens.refreshToken()).status()).as("no new access tokens").isEqualTo(400);
        assertThat(Keycloak.isActive(tokens.accessToken())).as("Keycloak's own view of the old token").isFalse();
        assertThat(Keycloak.userinfo(tokens.accessToken()).status()).isEqualTo(401);
        assertThat(browser.openSignIn().status()).as("no silent way back in").isEqualTo(200);

        // What remains is a bearer token already issued, which services check offline.
        // It is bounded by its lifetime; the slow test below measures the exact end.
        assertThat(tokens.expiresAt() - Instant.now().getEpochSecond()).isLessThanOrEqualTo(300);
    }

    @Test
    @EnabledIfSystemProperty(named = "e2e.slow", matches = "true")
    @DisplayName("26. (slow, -De2e.slow=true) a copied access token is refused at expiry plus the 60s clock skew")
    void copiedAccessTokenIsRefusedAtExpiry() throws InterruptedException {
        Keycloak.setClientAttribute("platform-web", "access.token.lifespan", "60");
        try {
            OneIdBrowser browser = new OneIdBrowser();
            Tokens tokens = browser.signIn("akarimov");
            assertThat(tokens.expiresAt() - tokens.claims().path("iat").asLong()).isEqualTo(60);
            assertThat(browser.endSession(tokens.idToken()).status()).isEqualTo(302);

            long refusedAt = 0;
            while (Instant.now().getEpochSecond() < tokens.expiresAt() + 90) {
                int status = Api.me(tokens.accessToken()).status();
                if (status == 401) {
                    refusedAt = Instant.now().getEpochSecond();
                    break;
                }
                assertThat(status).as("until refused, the copied token is still accepted").isEqualTo(200);
                Thread.sleep(2_000);
            }

            assertThat(refusedAt).as("refused at all").isPositive();
            assertThat(refusedAt - tokens.expiresAt())
                    .as("seconds past exp when first refused: Spring's default clock skew is 60")
                    .isBetween(55L, 68L);
        } finally {
            Keycloak.setClientAttribute("platform-web", "access.token.lifespan", "");
        }
    }
}
