package uz.platform.keycloak.oneid;

import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.models.IdentityProviderModel;

/**
 * Configuration of the OneID provider, as stored in the realm.
 *
 * <p>{@code authorizationUrl}, {@code tokenUrl}, {@code clientId},
 * {@code clientSecret} and {@code defaultScope} are inherited. One OneID quirk
 * shows up immediately: <b>all four operations use the same URL</b>, so the
 * authorization and token URLs are set to the same value and the operation is
 * chosen by {@code response_type} or {@code grant_type} instead of by path.</p>
 *
 * <p>The only addition is a flag for logout, which is off by default and
 * explained on its accessor.</p>
 */
public class OneIdIdentityProviderConfig extends OAuth2IdentityProviderConfig {

    public static final String CALL_ONEID_LOGOUT = "callOneIdLogout";

    public OneIdIdentityProviderConfig(IdentityProviderModel model) {
        super(model);
    }

    public OneIdIdentityProviderConfig() {
    }

    /**
     * Whether ending a Keycloak session should also call {@code one_log_out}.
     *
     * <p>Off by default, which is a deliberate scope decision rather than an
     * omission. The consequence is worth knowing before testing: the person stays
     * signed in at sso.egov.uz, so clicking Login again returns them without a
     * credential prompt. That looks like a broken logout and is not.</p>
     */
    public boolean isCallOneIdLogout() {
        return Boolean.parseBoolean(getConfig().getOrDefault(CALL_ONEID_LOGOUT, "false"));
    }
}
