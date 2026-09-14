package uz.platform.keycloak.oneid;

import java.util.List;

import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

/**
 * Registers the OneID provider with Keycloak.
 *
 * <p>Discovery is by the file
 * {@code META-INF/services/org.keycloak.broker.social.SocialIdentityProviderFactory}
 * inside the jar. Keycloak reads it at build time, which is why the server has to
 * be rebuilt (`kc.sh build`) whenever this jar changes — and why the Keycloak
 * image in this project is a build artefact of this repository rather than the
 * stock one.</p>
 *
 * <p>Registered as a <em>social</em> provider so it appears as a login button on
 * the Keycloak sign-in page, which is the behaviour wanted here: the person picks
 * "OneID" and is sent to sso.egov.uz. The alternative, a plain identity provider,
 * is the same mechanism without the button.</p>
 */
public class OneIdIdentityProviderFactory
        extends AbstractIdentityProviderFactory<OneIdIdentityProvider>
        implements SocialIdentityProviderFactory<OneIdIdentityProvider> {

    public static final String PROVIDER_ID = "oneid";

    @Override
    public String getName() {
        return "OneID";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public OneIdIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        return new OneIdIdentityProvider(session, new OneIdIdentityProviderConfig(model));
    }

    @Override
    public OneIdIdentityProviderConfig createConfig() {
        return new OneIdIdentityProviderConfig();
    }

    /**
     * What the admin console shows for this provider.
     *
     * <p>All three URLs default to the same value, because OneID uses one
     * endpoint for authorization, token exchange, identification and logout,
     * distinguishing them by {@code response_type} or {@code grant_type}.</p>
     */
    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name("authorizationUrl")
                .label("Authorization URL")
                .helpText("OneID endpoint. All four operations use the same URL, "
                        + "for example https://sso.egov.uz/sso/oauth/Authorization.do")
                .type(ProviderConfigProperty.STRING_TYPE)
                .add()

                .property()
                .name("tokenUrl")
                .label("Token URL")
                .helpText("Normally identical to the authorization URL.")
                .type(ProviderConfigProperty.STRING_TYPE)
                .add()

                .property()
                .name("userInfoUrl")
                .label("Identify URL")
                .helpText("Where grant_type=one_access_token_identify is posted. "
                        + "Normally identical to the authorization URL.")
                .type(ProviderConfigProperty.STRING_TYPE)
                .add()

                .property()
                .name("defaultScope")
                .label("Scope")
                .helpText("Issued by the OneID administrator. This is a client identifier "
                        + "such as \"myportal\", not a space-separated permission list.")
                .type(ProviderConfigProperty.STRING_TYPE)
                .add()

                .property()
                .name(OneIdIdentityProviderConfig.CALL_ONEID_LOGOUT)
                .label("Also end the OneID session on logout")
                .helpText("Off by default. With it off, logging out of this platform leaves the "
                        + "person signed in at sso.egov.uz, so the next login returns them without "
                        + "a password prompt. That looks like a broken logout and is not. With it on, "
                        + "Keycloak keeps the OneID access token on each session until logout, "
                        + "because one_log_out needs it.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("false")
                .add()

                .build();
    }

    @Override
    public String getHelpText() {
        return "Uzbekistan OneID (Yagona identifikatsiya tizimi). OAuth2-shaped but not "
                + "OpenID Connect: it renames the protocol constants and returns no id_token, "
                + "which is why it needs this provider rather than the built-in OIDC one.";
    }
}
