package uz.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.platform.e2e.support.Platform.ORGANIZATION_SERVICE;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.JsonNode;

import uz.platform.e2e.support.Api;
import uz.platform.e2e.support.Http;
import uz.platform.e2e.support.Http.Response;
import uz.platform.e2e.support.Jwts;
import uz.platform.e2e.support.Keycloak;
import uz.platform.e2e.support.Platform;
import uz.platform.e2e.support.RunningPlatform;

/**
 * Services call organization-service directly, as the services themselves do;
 * /internal/** is deliberately not routed through the gateway.
 */
@ExtendWith(RunningPlatform.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Service-to-service")
class ServiceToServiceE2ETest {

    @Test
    @DisplayName("18. Cadastral Service obtains client_credentials token")
    void cadastralServiceObtainsClientCredentialsToken() {
        Response response = Keycloak.clientCredentials("cadastral-service", Platform.secret("CADASTRAL_SERVICE_CLIENT_SECRET"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().has("refresh_token")).as("nothing to refresh: a service simply asks again").isFalse();

        JsonNode claims = Jwts.claims(response.json().path("access_token").asText());
        assertThat(claims.path("azp").asText()).isEqualTo("cadastral-service");
        assertThat(claims.path("token_use").asText()).isEqualTo("service");
        assertThat(Http.texts(claims.path("aud"))).contains("organization-service").doesNotContain("platform-api");
        assertThat(Http.texts(claims.path("resource_access").path("organization-service").path("roles")))
                .containsExactly("ORG_READ");
        assertThat(claims.has("org_tins")).as("no person's data in a service token").isFalse();
    }

    @Test
    @DisplayName("19. Organization Service accepts it")
    void organizationServiceAcceptsIt() {
        String cadastral = Keycloak.serviceToken("cadastral-service");

        JsonNode ping = Http.get(ORGANIZATION_SERVICE + "/internal/ping").bearer(cadastral).send().json();
        assertThat(ping.path("callerType").asText()).isEqualTo("SERVICE");
        assertThat(ping.path("callingClient").asText()).isEqualTo("cadastral-service");

        Response memberships = Http.get(ORGANIZATION_SERVICE + "/internal/memberships/" + Api.subjectOf("ali"))
                .bearer(cadastral).send();
        assertThat(memberships.status()).isEqualTo(200);
        assertThat(Http.texts(memberships.json().path("activeTins"))).containsExactlyInAnyOrder("111111111", "222222222");
    }

    @Test
    @DisplayName("20. Unauthorized service is rejected")
    void unauthorizedServiceIsRejected() {
        String membershipsOfAli = ORGANIZATION_SERVICE + "/internal/memberships/" + Api.subjectOf("ali");

        assertThat(Keycloak.clientCredentials("cadastral-service", "not-the-secret").status())
                .as("a wrong secret gets no token at all").isEqualTo(401);
        assertThat(Http.get(membershipsOfAli).send().status()).as("no token").isEqualTo(401);
        assertThat(Http.get(membershipsOfAli).bearer(Api.signedIn("ali")).send().status())
                .as("a valid token of a person: people are not services").isEqualTo(403);
        assertThat(Http.get(membershipsOfAli).bearer(Api.signedIn("super_admin")).send().status())
                .as("not even a SUPER_ADMIN").isEqualTo(403);
        assertThat(Http.get(membershipsOfAli).bearer(Keycloak.masterRealmToken()).send().status())
                .as("a token from another issuer").isEqualTo(401);
    }

    @Test
    @DisplayName("21. Cadastral Service cannot perform unrelated privileged operations")
    void cadastralServiceCannotPerformUnrelatedPrivilegedOperations() {
        String aliSubject = Api.subjectOf("ali");
        String cadastral = Keycloak.serviceToken("cadastral-service");
        String sync = ORGANIZATION_SERVICE + "/internal/memberships/" + aliSubject + "/sync";

        assertThat(Http.post(sync).bearer(cadastral).json(Map.of("tins", new String[0])).send().status())
                .as("rewriting memberships needs ORG_MEMBERSHIP_SYNC, which cadastral-service lacks").isEqualTo(403);
        assertThat(Http.texts(Http.get(ORGANIZATION_SERVICE + "/internal/memberships/" + aliSubject)
                .bearer(cadastral).send().json().path("activeTins")))
                .as("and nothing changed").containsExactlyInAnyOrder("111111111", "222222222");

        assertThat(Http.post(sync).bearer(Keycloak.serviceToken("user-service"))
                .json(Map.of("tins", new String[] {"111111111", "222222222"})).send().status())
                .as("the same call succeeds for user-service, which holds the role").isEqualTo(200);

        assertThat(Http.get(Platform.USER_SERVICE + "/api/admin/users").bearer(cadastral).send().status())
                .as("its token is not addressed to user-service at all").isEqualTo(401);
        assertThat(Http.get(Platform.GATEWAY + "/api/projects").bearer(cadastral)
                .header(Api.TIN_HEADER, "111111111").send().status())
                .as("nor to the public API").isEqualTo(401);
    }

    @Test
    @DisplayName("22. Service account is NOT SUPER_ADMIN")
    void serviceAccountIsNotSuperAdmin() {
        JsonNode claims = Jwts.claims(Keycloak.serviceToken("cadastral-service"));
        assertThat(Http.texts(claims.path("realm_access").path("roles"))).doesNotContain("SUPER_ADMIN", "ADMIN");

        String clientId = Keycloak.client("cadastral-service").path("id").asText();
        String serviceAccountId = Keycloak.admin("/clients/" + clientId + "/service-account-user").path("id").asText();
        assertThat(Keycloak.effectiveRealmRoles(serviceAccountId))
                .as("effective realm roles, composites included")
                .doesNotContain("SUPER_ADMIN", "ADMIN", "QURUVCHI", "BANK");

        String organizationServiceId = Keycloak.client("organization-service").path("id").asText();
        JsonNode clientRoles = Keycloak.admin("/users/" + serviceAccountId
                + "/role-mappings/clients/" + organizationServiceId + "/composite");
        assertThat(clientRoles.findValuesAsText("name")).containsExactly("ORG_READ");
    }
}
