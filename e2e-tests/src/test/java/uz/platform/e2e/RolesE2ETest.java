package uz.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import uz.platform.e2e.support.Api;
import uz.platform.e2e.support.Http.Response;
import uz.platform.e2e.support.RunningPlatform;
import uz.platform.e2e.support.TestData;

/**
 * Realm roles decide what a person may do, through the permissions each service
 * maps them to. Development users from realm-export.json:
 * ali (QURUVCHI, 111111111 and 222222222), bank_user (BANK, 333333333),
 * dual (QURUVCHI and BANK, 111111111), admin_user (ADMIN), malika (no business role).
 */
@ExtendWith(RunningPlatform.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Roles")
class RolesE2ETest {

    private static final String IT_GROUP = "111111111";
    private static final String MILLIY_BANK = "333333333";

    @AfterAll
    static void removeCreatedRows() {
        TestData.deleteCreatedRows();
    }

    @Test
    @DisplayName("6. QURUVCHI can access project endpoints")
    void quruvchiCanAccessProjectEndpoints() {
        String ali = Api.signedIn("ali");

        assertThat(Api.projects(ali, IT_GROUP).status()).isEqualTo(200);
        Response created = Api.createProject(ali, IT_GROUP);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("organizationTin").asText()).isEqualTo(IT_GROUP);
        assertThat(Api.updateProject(ali, IT_GROUP, created.json().path("id").asText()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("7. BANK cannot access QURUVCHI-only endpoint")
    void bankCannotAccessQuruvchiOnlyEndpoint() {
        String bank = Api.signedIn("bank_user");

        Response attempt = Api.createProject(bank, MILLIY_BANK);
        assertThat(attempt.status()).isEqualTo(403);
        assertThat(attempt.body())
                .as("refused for the missing permission, not for the organization")
                .doesNotContain("organization_membership_required");
        assertThat(Api.payments(bank, MILLIY_BANK).status()).as("while its own endpoints work").isEqualTo(200);
    }

    @Test
    @DisplayName("8. ADMIN can access admin endpoints")
    void adminCanAccessAdminEndpoints() {
        Response users = Api.get("/api/admin/users", Api.signedIn("admin_user"));
        assertThat(users.status()).isEqualTo(200);
        assertThat(users.json().isArray()).isTrue();

        assertThat(Api.get("/api/admin/users", Api.signedIn("malika")).status())
                .as("a user without ADMIN").isEqualTo(403);
        assertThat(Api.get("/api/platform/audit", Api.signedIn("admin_user")).status())
                .as("ADMIN does not include platform administration").isEqualTo(403);
    }

    @Test
    @DisplayName("9. Multiple roles work together")
    void multipleRolesWorkTogether() {
        String dual = Api.signedIn("dual");

        Response project = Api.createProject(dual, IT_GROUP);
        assertThat(project.status()).as("acting as QURUVCHI").isEqualTo(201);
        String projectId = project.json().path("id").asText();

        assertThat(Api.createPayment(dual, IT_GROUP, projectId, "1500.00").status())
                .as("acting as BANK, same token, same organization").isEqualTo(201);
        assertThat(Api.createPayment(Api.signedIn("ali"), IT_GROUP, projectId, "10.00").status())
                .as("QURUVCHI alone cannot record a payment").isEqualTo(403);
    }
}
