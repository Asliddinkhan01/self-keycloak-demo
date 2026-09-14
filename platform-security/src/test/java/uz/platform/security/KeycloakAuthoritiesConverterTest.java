package uz.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.platform.security.TestJwts.ALI;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("KeycloakAuthoritiesConverter: what a token is allowed to mean")
class KeycloakAuthoritiesConverterTest {

    /** The cadastral-service seed data, in miniature. */
    private final PermissionCatalog catalog = PermissionCatalogTest.catalogOf(Map.of(
            "JISMONIY_SHAXS", Set.of("PROJECT_READ"),
            "QURUVCHI", Set.of("PROJECT_READ", "PROJECT_CREATE", "PROJECT_UPDATE"),
            "BANK", Set.of("PAYMENT_READ", "PAYMENT_CREATE")));

    private final KeycloakAuthoritiesConverter cadastralService =
            new KeycloakAuthoritiesConverter("cadastral-service", catalog);
    private final KeycloakAuthoritiesConverter organizationService =
            new KeycloakAuthoritiesConverter("organization-service", catalog);

    @Test
    @DisplayName("realm roles become ROLE_ authorities")
    void realmRolesBecomeRoleAuthorities() {
        assertThat(names(cadastralService.convert(TestJwts.user(ALI, "JISMONIY_SHAXS", "QURUVCHI"))))
                .contains("ROLE_JISMONIY_SHAXS", "ROLE_QURUVCHI");
    }

    @Test
    @DisplayName("9. multiple roles: effective permissions are the union, with no special case")
    void permissionsAreTheUnionOfAllRoles() {
        assertThat(names(cadastralService.convert(TestJwts.user(ALI, "QURUVCHI", "BANK"))))
                .contains("PROJECT_CREATE", "PAYMENT_CREATE");
        assertThat(names(cadastralService.convert(TestJwts.user(ALI, "BANK"))))
                .contains("PAYMENT_CREATE")
                .doesNotContain("PROJECT_CREATE");
    }

    @Test
    @DisplayName("a permission cannot be smuggled in through the token")
    void permissionsNeverComeFromTheToken() {
        Jwt forged = TestJwts.base(ALI)
                .claim("token_use", "user")
                .claim("realm_access", Map.of("roles", List.of("JISMONIY_SHAXS")))
                .claim("scope", "PROJECT_CREATE")
                .claim("permissions", List.of("PROJECT_CREATE"))
                .claim("authorities", List.of("PROJECT_CREATE", "ROLE_SUPER_ADMIN"))
                .build();

        Set<String> granted = names(cadastralService.convert(forged));

        assertThat(granted).doesNotContain("PROJECT_CREATE", "ROLE_SUPER_ADMIN");
        // A scope stays a scope: prefixed, and matched by nothing that checks permissions.
        assertThat(granted).contains("SCOPE_PROJECT_CREATE");
    }

    @Test
    @DisplayName("client roles count only in the service they were issued for")
    void clientRolesAreScopedToTheirService() {
        Jwt cadastralToken = TestJwts.service("cadastral-service", "ORG_READ");

        assertThat(names(organizationService.convert(cadastralToken))).contains("ORG_READ");
        assertThat(names(cadastralService.convert(cadastralToken))).doesNotContain("ORG_READ");
    }

    @ParameterizedTest(name = "token_use={0} -> {1}")
    @CsvSource(nullValues = "NULL", value = {
            "user,    TOKEN_USE_USER",
            "service, TOKEN_USE_SERVICE",
            "SERVICE, TOKEN_USE_UNKNOWN",
            "admin,   TOKEN_USE_UNKNOWN",
            "NULL,    TOKEN_USE_UNKNOWN"})
    @DisplayName("the token_use claim decides the caller type, and anything unexpected is UNKNOWN")
    void tokenUseDecidesCallerType(String tokenUse, String expected) {
        Jwt.Builder token = TestJwts.base(ALI);
        if (tokenUse != null) {
            token.claim("token_use", tokenUse);
        }

        Set<String> granted = names(organizationService.convert(token.build()));

        assertThat(granted).contains(expected);
        assertThat(granted).filteredOn(authority -> authority.startsWith("TOKEN_USE_")).hasSize(1);
    }

    private static Set<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
