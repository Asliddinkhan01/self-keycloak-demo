package uz.platform.keycloak.oneid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserSessionModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * What the provider leaves on a Keycloak session, and what logout does with it.
 *
 * <p>Regression tests for two defects found in phase 11 by reading stored
 * sessions: the OneID access token was kept on every session, and
 * {@code oneid_sess_id} was never kept at all.</p>
 */
@DisplayName("OneIdIdentityProvider: session notes and logout")
class OneIdIdentityProviderTest {

    /** Keycloak's own note name for a broker's access token. */
    private static final String FEDERATED_ACCESS_TOKEN = "FEDERATED_ACCESS_TOKEN";
    private static final String SESS_ID = "5f0c1f7e-8d2a-4b1c-9f00-000000000001";

    private final KeycloakSession session = mock(KeycloakSession.class);

    @Test
    @DisplayName("by default the OneID access token is not kept on the Keycloak session")
    void tokenNotKeptByDefault() {
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);

        provider(false).authenticationFinished(authSession, finishedLogin());

        verify(authSession, never()).setUserSessionNote(eq(FEDERATED_ACCESS_TOKEN), any());
    }

    @Test
    @DisplayName("with OneID logout on, it is kept, because one_log_out needs it")
    void tokenKeptForOneIdLogout() {
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);

        provider(true).authenticationFinished(authSession, finishedLogin());

        verify(authSession).setUserSessionNote(FEDERATED_ACCESS_TOKEN, "b3BhcXVlLW9uZWlkLXRva2Vu");
    }

    @Test
    @DisplayName("oneid_sess_id is written onto the session either way")
    void sessIdAlwaysKept() {
        for (boolean callOneIdLogout : new boolean[] {false, true}) {
            AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);

            provider(callOneIdLogout).authenticationFinished(authSession, finishedLogin());

            verify(authSession).setUserSessionNote(OneIdIdentityProvider.NOTE_ONEID_SESS_ID, SESS_ID);
        }
    }

    @Test
    @DisplayName("logout with the setting off neither reads the session nor calls OneID")
    void logoutWithSettingOff() {
        UserSessionModel userSession = mock(UserSessionModel.class);

        assertThat(provider(false).keycloakInitiatedBrowserLogout(session, userSession, null, null))
                .as("Keycloak carries on with its own logout").isNull();
        provider(false).backchannelLogout(session, userSession, null, null);

        verifyNoInteractions(userSession);
    }

    @Test
    @DisplayName("logout with the setting on, for a session that holds no token, sends nothing")
    void logoutWithoutHeldToken() {
        UserSessionModel userSession = mock(UserSessionModel.class);
        when(userSession.getNote(FEDERATED_ACCESS_TOKEN)).thenReturn(null);

        provider(true).backchannelLogout(session, userSession, null, null);

        verify(userSession, never()).removeNote(any());
    }

    private OneIdIdentityProvider provider(boolean callOneIdLogout) {
        OneIdIdentityProviderConfig config = new OneIdIdentityProviderConfig();
        config.setAlias("oneid");
        config.getConfig().put(OneIdIdentityProviderConfig.CALL_ONEID_LOGOUT, Boolean.toString(callOneIdLogout));
        return new OneIdIdentityProvider(session, config);
    }

    /** What the inherited token handling and the identify call leave on the brokered identity. */
    private static BrokeredIdentityContext finishedLogin() {
        BrokeredIdentityContext context = new BrokeredIdentityContext("30101199012345", new OneIdIdentityProviderConfig());
        context.getContextData().put(FEDERATED_ACCESS_TOKEN, "b3BhcXVlLW9uZWlkLXRva2Vu");
        context.getContextData().put(OneIdIdentityProvider.CONTEXT_ONEID_SESS_ID, SESS_ID);
        return context;
    }
}
