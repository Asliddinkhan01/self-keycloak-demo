package uz.platform.keycloak.oneid;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

@DisplayName("OneIdAttributeRoleMapper: what OneID may decide about a Keycloak user")
class OneIdAttributeRoleMapperTest {

    private static final String PIN = "30101199012345";

    private final OneIdAttributeRoleMapper mapper = new OneIdAttributeRoleMapper();
    private final RealmModel realm = mock(RealmModel.class);
    private final RoleModel physicalPerson = mock(RoleModel.class);
    private final RoleModel legalEntity = mock(RoleModel.class);
    private final UserModel user = mock(UserModel.class);

    @BeforeEach
    void realmWithBaselineRoles() {
        when(realm.getName()).thenReturn("platform");
        when(realm.getRole("JISMONIY_SHAXS")).thenReturn(physicalPerson);
        when(realm.getRole("YURIDIK_SHAXS")).thenReturn(legalEntity);
        when(user.getUsername()).thenReturn("akarimov");
    }

    @Test
    @DisplayName("2. every OneID account gets JISMONIY_SHAXS, and no more without a legal entity")
    void physicalPersonOnly() {
        mapper.importNewUser(null, realm, user, null, identity("I"));

        verify(user).grantRole(physicalPerson);
        verify(user, never()).grantRole(legalEntity);
    }

    @Test
    @DisplayName("legal entities reported by OneID add YURIDIK_SHAXS and are stored as org_tins")
    void legalEntitiesAddYuridikShaxs() {
        mapper.importNewUser(null, realm, user, null, identity("I", "111111111", "222222222"));

        verify(user).grantRole(physicalPerson);
        verify(user).grantRole(legalEntity);
        verify(user).setAttribute("org_tins", List.of("111111111", "222222222"));
    }

    @Test
    @DisplayName("signing in as a legal entity adds YURIDIK_SHAXS even without legal_info")
    void legalEntityUserType() {
        mapper.importNewUser(null, realm, user, null, identity("L"));

        verify(user).grantRole(legalEntity);
    }

    @Test
    @DisplayName("business roles are never granted, and no role is ever revoked")
    void neverGrantsBusinessRolesNorRevokes() {
        mapper.updateBrokeredUser(null, realm, user, null, identity("I"));

        for (String businessRole : List.of("QURUVCHI", "BANK", "ADMIN", "SUPER_ADMIN")) {
            verify(realm, never()).getRole(businessRole);
        }
        verify(user, never()).deleteRoleMapping(any());
    }

    @Test
    @DisplayName("a role the user already holds is not granted again")
    void existingRoleIsNotGrantedAgain() {
        when(user.hasRole(physicalPerson)).thenReturn(true);

        mapper.updateBrokeredUser(null, realm, user, null, identity("I"));

        verify(user, never()).grantRole(physicalPerson);
    }

    @Test
    @DisplayName("OneID is authoritative: what it stopped reporting is removed, what it reports is stored")
    void refreshesAttributesOnEveryLogin() {
        mapper.updateBrokeredUser(null, realm, user, null, identity("I"));

        verify(user).removeAttribute("org_tins");
        verify(user).removeAttribute("pkcs_legal_tin");
        verify(user).setAttribute("oneid_pin", List.of(PIN));
        verify(user).setAttribute("identity_verified", List.of("true"));
        verify(user, never()).setAttribute(anyStringOtherThanCopied(), any());
    }

    @Test
    @DisplayName("a realm without the role is a configuration error, not a failed login")
    void missingRoleDoesNotFailLogin() {
        when(realm.getRole("JISMONIY_SHAXS")).thenReturn(null);

        assertThatCode(() -> mapper.importNewUser(null, realm, user, null, identity("I")))
                .doesNotThrowAnyException();
        verify(user, never()).grantRole(physicalPerson);
    }

    private static BrokeredIdentityContext identity(String userType, String... orgTins) {
        BrokeredIdentityContext context = new BrokeredIdentityContext(PIN, new OneIdIdentityProviderConfig());
        context.setUserAttribute(OneIdIdentityProvider.ATTR_ONEID_USER_ID, "akarimov");
        context.setUserAttribute(OneIdIdentityProvider.ATTR_USER_TYPE, userType);
        context.setUserAttribute(OneIdIdentityProvider.ATTR_AUTH_METHOD, "PKCSMETHOD");
        context.setUserAttribute(OneIdIdentityProvider.ATTR_IDENTITY_VERIFIED, "true");
        context.setUserAttribute(OneIdIdentityProvider.ATTR_PIN, PIN);
        if (orgTins.length > 0) {
            context.setUserAttribute(OneIdIdentityProvider.ATTR_ORG_TINS, List.of(orgTins));
        }
        return context;
    }

    /** Matches any attribute name the mapper is not meant to write. */
    private static String anyStringOtherThanCopied() {
        return org.mockito.ArgumentMatchers.argThat(
                name -> name != null && !OneIdAttributeRoleMapper.COPIED_ATTRIBUTES.contains(name));
    }

    @SuppressWarnings("unused")
    private static String unused() {
        return anyString();
    }
}
