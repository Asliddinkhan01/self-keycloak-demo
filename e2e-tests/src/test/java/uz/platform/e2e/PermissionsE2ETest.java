package uz.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
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
import uz.platform.e2e.support.RunningPlatform;
import uz.platform.e2e.support.TestData;

@ExtendWith(RunningPlatform.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Permissions")
class PermissionsE2ETest {

    private static final String IT_GROUP = "111111111";

    @AfterAll
    static void removeCreatedRows() {
        TestData.deleteCreatedRows();
    }

    @Test
    @DisplayName("10. Role with PROJECT_CREATE can create")
    void roleWithProjectCreateCanCreate() {
        String ali = Api.signedIn("ali");

        Response created = Api.createProject(ali, IT_GROUP);

        assertThat(created.status()).isEqualTo(201);

        List<String> ids = new ArrayList<>();
        Api.projects(ali, IT_GROUP).json().forEach(project -> ids.add(project.path("id").asText()));
        assertThat(ids).as("and reads it back in the same organization").contains(created.json().path("id").asText());
    }

    @Test
    @DisplayName("11. Role without PROJECT_CREATE receives 403")
    void roleWithoutProjectCreateReceives403() {
        for (String username : new String[] {"malika", "admin_user", "super_admin"}) {
            Response attempt = Api.createProject(Api.signedIn(username), null);
            assertThat(attempt.status()).as(username).isEqualTo(403);
        }
        Response bank = Api.createProject(Api.signedIn("bank_user"), "333333333");
        assertThat(bank.status()).as("bank_user, in its own organization").isEqualTo(403);
        assertThat(bank.body()).doesNotContain("organization_membership_required");
    }

    @Test
    @DisplayName("12. (live half) permissions come from each service's database, never from the token; "
            + "the refresh after a change is proven by cadastral-service PermissionRefreshTest")
    void permissionsComeFromTheServiceDatabase() {
        String adminToken = Api.signedIn("admin_user");
        String superAdminToken = Api.signedIn("super_admin");

        assertThat(Jwts.claims(adminToken).toString())
                .as("the token carries roles only")
                .doesNotContain("USER_READ").doesNotContain("PROJECT_").doesNotContain("PAYMENT_");

        JsonNode admin = Api.me(adminToken).json();
        assertThat(Http.texts(admin.path("effectivePermissionsHere")))
                .containsExactlyInAnyOrder("USER_READ", "USER_UPDATE", "USER_DELETE");

        JsonNode superAdmin = Api.me(superAdminToken).json();
        assertThat(Http.texts(superAdmin.path("effectivePermissionsHere")))
                .contains("PLATFORM_ADMIN")
                .doesNotContain("USER_DELETE");
    }
}
