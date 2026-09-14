package uz.platform.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uz.platform.security.TestJwts.ALI;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The whole filter chain every service shares, driven through HTTP.
 *
 * <p>Only the JwtDecoder is replaced: it maps a test token name to the claims
 * Keycloak would have signed. Everything after it is the production wiring —
 * the authorities converter, the /internal rule, the organization filter and
 * its position after authorization, and method security.</p>
 */
@SpringBootTest(classes = ResourceServerSecurityTest.TestApplication.class,
        properties = "spring.application.name=cadastral-service")
@AutoConfigureMockMvc
@DisplayName("ResourceServerSecurity: the filter chain every service shares")
class ResourceServerSecurityTest {

    private static final String COMPANY_A = "111111111";
    private static final String COMPANY_B = "222222222";

    private static final Map<String, Jwt> TOKENS = Map.of(
            "quruvchi", TestJwts.user(ALI, "JISMONIY_SHAXS", "QURUVCHI"),
            "bank", TestJwts.user(ALI, "JISMONIY_SHAXS", "BANK"),
            "cadastral-service", TestJwts.service("cadastral-service", "ORG_READ"));

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("public paths need no token")
    void publicPathsNeedNoToken() throws Exception {
        mvc.perform(get("/api/public/hello")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a protected path without a token is 401")
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/projects").header("X-Organization-TIN", COMPANY_A))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token the decoder rejects is 401")
    void rejectedTokenIs401() throws Exception {
        mvc.perform(get("/api/projects").header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("20. /internal/** refuses a person's token and accepts a service's")
    void internalPathsAreForServicesOnly() throws Exception {
        mvc.perform(get("/internal/ping").header("Authorization", "Bearer quruvchi"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/ping").header("Authorization", "Bearer cadastral-service"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("10, 14. PROJECT_CREATE creates, inside the organization named by the header")
    void createsInTheOrganizationFromTheHeader() throws Exception {
        mvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer quruvchi")
                        .header("X-Organization-TIN", COMPANY_A))
                .andExpect(status().isCreated())
                .andExpect(content().string(COMPANY_A));
    }

    @Test
    @DisplayName("15. the same token acts for another of the caller's organizations")
    void sameTokenOtherOrganization() throws Exception {
        mvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer quruvchi")
                        .header("X-Organization-TIN", COMPANY_B))
                .andExpect(status().isCreated())
                .andExpect(content().string(COMPANY_B));
    }

    @Test
    @DisplayName("7, 11. without PROJECT_CREATE the permission check answers 403")
    void withoutPermissionIs403() throws Exception {
        mvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer bank")
                        .header("X-Organization-TIN", COMPANY_A))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("organization_membership_required"))));
    }

    @Test
    @DisplayName("16, 17. an organization the caller does not belong to is 403 before any controller")
    void foreignOrFakeTinIs403() throws Exception {
        for (String tin : new String[] {"333333333", "999999999"}) {
            mvc.perform(post("/api/projects")
                            .header("Authorization", "Bearer quruvchi")
                            .header("X-Organization-TIN", tin))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(containsString("organization_membership_required")));
        }
    }

    @Test
    @DisplayName("an organization-scoped endpoint without the header is 400")
    void missingOrganizationIs400() throws Exception {
        mvc.perform(post("/api/projects").header("Authorization", "Bearer quruvchi"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an anonymous request carrying a TIN is 401, never judged on membership")
    void anonymousWithTinIs401() throws Exception {
        mvc.perform(post("/api/projects").header("X-Organization-TIN", "333333333"))
                .andExpect(status().isUnauthorized());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({ResourceServerSecurity.class, OrganizationSecurity.class, ProbeController.class})
    static class TestApplication {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                Jwt jwt = TOKENS.get(token);
                if (jwt == null) {
                    throw new BadJwtException("not a token this test issued");
                }
                return jwt;
            };
        }

        @Bean
        PermissionCatalog permissionCatalog() {
            return PermissionCatalogTest.catalogOf(Map.of(
                    "QURUVCHI", Set.of("PROJECT_READ", "PROJECT_CREATE"),
                    "BANK", Set.of("PAYMENT_READ", "PAYMENT_CREATE")));
        }

        @Bean("permissionChecker")
        PermissionChecker permissionChecker() {
            return new PermissionChecker();
        }

        @Bean
        MembershipVerifier membershipVerifier() {
            return (subject, tin) -> ALI.equals(subject) && Set.of(COMPANY_A, COMPANY_B).contains(tin);
        }
    }

    /** Endpoints shaped like the real ones, returning the organization they acted for. */
    @RestController
    public static class ProbeController {

        private final OrganizationContext organization;

        public ProbeController(OrganizationContext organization) {
            this.organization = organization;
        }

        @GetMapping("/api/public/hello")
        public String hello() {
            return "hello";
        }

        @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_READ')")
        @GetMapping("/api/projects")
        public String list() {
            return organization.requireTin();
        }

        @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_CREATE')")
        @PostMapping("/api/projects")
        public ResponseEntity<String> create() {
            return ResponseEntity.status(201).body(organization.requireTin());
        }

        @GetMapping("/internal/ping")
        public String ping() {
            return "pong";
        }
    }
}
