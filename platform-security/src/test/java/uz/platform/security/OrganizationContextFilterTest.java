package uz.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.platform.security.TestJwts.ALI;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@DisplayName("OrganizationContextFilter: X-Organization-TIN is a claim to verify, never a fact")
class OrganizationContextFilterTest {

    private static final Set<String> ALI_MEMBERSHIPS = Set.of("111111111", "222222222");

    private final List<String> askedAbout = new ArrayList<>();
    private final MembershipVerifier verifier = (subject, tin) -> {
        askedAbout.add(tin);
        return ALI.equals(subject) && ALI_MEMBERSHIPS.contains(tin);
    };
    private final OrganizationContext context = new OrganizationContext();
    private final OrganizationContextFilter filter = new OrganizationContextFilter(verifier, context);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("no header: the request continues with no organization context")
    void noHeader() throws Exception {
        signInAsAli();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(null), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(context.isPresent()).isFalse();
        assertThat(askedAbout).isEmpty();
    }

    @ParameterizedTest(name = "TIN {0}")
    @ValueSource(strings = {"111111111", "222222222"})
    @DisplayName("14, 15. a TIN the caller belongs to becomes this request's organization")
    void memberTinBecomesTheContext(String tin) throws Exception {
        signInAsAli();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(tin), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(context.requireTin()).isEqualTo(tin);
    }

    @ParameterizedTest(name = "TIN \"{0}\"")
    @ValueSource(strings = {"333333333", "999999999", "not-a-tin", "111111111 OR 1=1"})
    @DisplayName("16, 17. any other TIN, real or invented, is refused with 403 before the controller")
    void otherTinsAreRefused(String tin) throws Exception {
        signInAsAli();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(tin), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("organization_membership_required");
        assertThat(chain.getRequest()).as("the controller never runs").isNull();
        assertThat(context.isPresent()).isFalse();
        assertThat(askedAbout).as("decided by the membership check, with the exact header value")
                .containsExactly(tin);
    }

    @Test
    @DisplayName("an anonymous request is not judged on membership here; authorization refuses it")
    void anonymousRequestIsLeftToAuthorization() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("111111111"), new MockHttpServletResponse(), chain);

        assertThat(askedAbout).isEmpty();
        assertThat(context.isPresent()).isFalse();
    }

    private static void signInAsAli() {
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(TestJwts.user(ALI, "QURUVCHI"), List.of()));
    }

    private static MockHttpServletRequest request(String tin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        if (tin != null) {
            request.addHeader(OrganizationContextFilter.HEADER, tin);
        }
        return request;
    }
}
