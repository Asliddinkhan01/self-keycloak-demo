package uz.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.JsonNode;

import uz.platform.e2e.support.Api;
import uz.platform.e2e.support.Http;
import uz.platform.e2e.support.Http.Response;
import uz.platform.e2e.support.RunningPlatform;
import uz.platform.e2e.support.TestData;

/**
 * The acting organization is request context, sent as X-Organization-TIN and
 * checked against the membership table on every request. ali belongs to
 * 111111111 (Company A) and 222222222 (Company B); 333333333 (Company C)
 * exists but is not ali's.
 */
@ExtendWith(RunningPlatform.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Organization and TIN context")
class OrganizationContextE2ETest {

    private static final String COMPANY_A = "111111111";
    private static final String COMPANY_B = "222222222";
    private static final String COMPANY_C = "333333333";

    private static String ali;

    @BeforeAll
    static void signIn() {
        ali = Api.signedIn("ali");
    }

    @AfterAll
    static void removeCreatedRows() {
        TestData.deleteCreatedRows();
    }

    @Test
    @DisplayName("13. User belongs to Company A and Company B")
    void userBelongsToCompanyAAndB() {
        assertThat(Http.texts(Api.me(ali).json().path("organizationTinsActive")))
                .containsExactlyInAnyOrder(COMPANY_A, COMPANY_B);

        JsonNode mine = Api.get("/api/organizations/mine", ali).json();
        assertThat(mine.path("activeMemberships").findValuesAsText("tin"))
                .containsExactlyInAnyOrder(COMPANY_A, COMPANY_B);
    }

    @Test
    @DisplayName("14. User can operate using Company A")
    void userCanOperateUsingCompanyA() {
        Response created = Api.createProject(ali, COMPANY_A);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("organizationTin").asText()).isEqualTo(COMPANY_A);

        assertThat(projectIds(COMPANY_A)).contains(created.json().path("id").asText());
        assertThat(Api.currentOrganization(ali, COMPANY_A).json().path("tin").asText()).isEqualTo(COMPANY_A);
    }

    @Test
    @DisplayName("15. User can switch to Company B")
    void userCanSwitchToCompanyB() {
        String projectOfA = Api.createProject(ali, COMPANY_A).json().path("id").asText();

        Response createdInB = Api.createProject(ali, COMPANY_B);
        assertThat(createdInB.status()).as("same token, other organization").isEqualTo(201);
        assertThat(createdInB.json().path("organizationTin").asText()).isEqualTo(COMPANY_B);
        assertThat(Api.currentOrganization(ali, COMPANY_B).json().path("tin").asText()).isEqualTo(COMPANY_B);

        assertThat(projectIds(COMPANY_B)).as("Company A's data is not visible from B")
                .contains(createdInB.json().path("id").asText())
                .doesNotContain(projectOfA);
        assertThat(Api.updateProject(ali, COMPANY_B, projectOfA).status())
                .as("nor changeable from B").isEqualTo(404);
    }

    @Test
    @DisplayName("16. User cannot use Company C")
    void userCannotUseCompanyC() {
        for (Response attempt : List.of(
                Api.projects(ali, COMPANY_C),
                Api.createProject(ali, COMPANY_C),
                Api.currentOrganization(ali, COMPANY_C))) {
            assertThat(attempt.status()).isEqualTo(403);
            assertThat(attempt.body()).contains("organization_membership_required");
        }
    }

    @Test
    @DisplayName("16. (by id) another organization's project cannot be paid against, even for your own organization")
    void anotherOrganizationsProjectIsNotReachableById() {
        String projectOfA = Api.createProject(ali, COMPANY_A).json().path("id").asText();
        String bank = Api.signedIn("bank_user");

        Response attempt = Api.createPayment(bank, COMPANY_C, projectOfA, "1.00");

        assertThat(attempt.status())
                .as("bank_user is a genuine member of %s and holds PAYMENT_CREATE; the project is not theirs", COMPANY_C)
                .isEqualTo(404);
        assertThat(Api.payments(bank, COMPANY_C).json().findValuesAsText("projectId"))
                .as("and nothing was recorded").doesNotContain(projectOfA);
    }

    @Test
    @DisplayName("17. Fake X-Organization-TIN returns 403")
    void fakeTinReturns403() {
        for (String fake : List.of("999999999", "11111111", "111111111 OR 1=1", "../111111111")) {
            Response attempt = Api.projects(ali, fake);
            assertThat(attempt.status()).as("TIN \"%s\"", fake).isEqualTo(403);
            assertThat(attempt.body()).contains("organization_membership_required");
        }
        assertThat(Api.projects(ali, null).status())
                .as("no header at all is a different mistake, and a different answer").isEqualTo(400);
    }

    private static List<String> projectIds(String tin) {
        Response list = Api.projects(ali, tin);
        assertThat(list.status()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        list.json().forEach(project -> ids.add(project.path("id").asText()));
        return ids;
    }
}
