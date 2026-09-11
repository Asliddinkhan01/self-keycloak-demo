package uz.platform.keycloak.oneid;

import java.io.IOException;

import org.jboss.logging.Logger;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.Urls;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.ws.rs.core.UriBuilder;

/**
 * Brokers OneID (sso.egov.uz) into Keycloak.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>OneID borrows OAuth2's vocabulary and its authorization-code shape, then
 * renames the protocol constants and collapses four endpoints into one URL. The
 * differences are in values Keycloak does not expose as settings, which is why
 * no amount of admin-console configuration reaches this protocol:</p>
 *
 * <pre>
 * OneID                              standard OAuth2 / OIDC
 * ---------------------------------  --------------------------------
 * response_type=one_code             response_type=code
 * grant_type=one_authorization_code  grant_type=authorization_code
 * grant_type=one_access_token_identify   GET /userinfo with Bearer
 * grant_type=one_log_out             RP-initiated logout endpoint
 * no id_token at all                 OIDC always returns one
 * one URL for all four operations    four separate endpoints
 * </pre>
 *
 * <h2>What is overridden, and what is not</h2>
 *
 * <p>Exactly three things: the authorization URL, the token request, and the
 * identity fetch. Everything else is inherited — {@code state} generation and
 * verification, the callback endpoint, session creation, federated identity
 * storage, first-login detection, account linking, and token issuance.</p>
 *
 * <h2>What never leaves this class</h2>
 *
 * <p>The OneID access token is opaque, long-lived and can only be validated by
 * calling OneID again, against a documented ceiling of 300 requests per minute
 * for the whole client system. It is used here to fetch the identity and then
 * discarded. No microservice ever receives it; they receive a Keycloak token,
 * which they verify offline against the realm's public keys.</p>
 *
 * <p>The PIN is used as the broker user id, so Keycloak can recognise the same
 * person on a later login, and is stored as a Keycloak attribute. It is never
 * mapped into a token and never reaches a service database.</p>
 */
public class OneIdIdentityProvider extends AbstractOAuth2IdentityProvider<OneIdIdentityProviderConfig>
        implements SocialIdentityProvider<OneIdIdentityProviderConfig> {

    private static final Logger log = Logger.getLogger(OneIdIdentityProvider.class);

    /** OneID's replacements for the standard OAuth2 constants. */
    static final String RESPONSE_TYPE_ONE_CODE = "one_code";
    static final String GRANT_TYPE_AUTHORIZATION_CODE = "one_authorization_code";
    static final String GRANT_TYPE_IDENTIFY = "one_access_token_identify";
    static final String GRANT_TYPE_LOGOUT = "one_log_out";

    /** Keycloak user attributes written from the OneID payload. */
    static final String ATTR_ONEID_USER_ID = "oneid_user_id";
    static final String ATTR_USER_TYPE = "user_type";
    static final String ATTR_IDENTITY_VERIFIED = "identity_verified";
    static final String ATTR_AUTH_METHOD = "auth_method";
    static final String ATTR_ORG_TINS = "org_tins";
    static final String ATTR_PIN = "oneid_pin";

    public OneIdIdentityProvider(KeycloakSession session, OneIdIdentityProviderConfig config) {
        super(session, config);
    }

    @Override
    protected String getDefaultScopes() {
        // OneID's "scope" is an administrator-issued client identifier such as
        // "myportal", not a space-separated permission list. There is no sensible
        // default, so it comes entirely from configuration.
        return "";
    }

    /**
     * Departure 1: {@code response_type=one_code}, not {@code code}.
     *
     * <p>Built by hand rather than by calling super, because the parent writes
     * the standard value and it is not a setting. {@code state} still comes from
     * Keycloak, which generated it and will verify it on the way back — that is
     * the CSRF defence for the whole login redirect, and reimplementing it would
     * be the easiest thing here to get wrong.</p>
     */
    @Override
    protected UriBuilder createAuthorizationUrl(AuthenticationRequest request) {
        return UriBuilder.fromUri(getConfig().getAuthorizationUrl())
                .queryParam(OAUTH2_PARAMETER_RESPONSE_TYPE, RESPONSE_TYPE_ONE_CODE)
                .queryParam(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
                .queryParam(OAUTH2_PARAMETER_REDIRECT_URI, request.getRedirectUri())
                .queryParam(OAUTH2_PARAMETER_SCOPE, getConfig().getDefaultScope())
                .queryParam(OAUTH2_PARAMETER_STATE, request.getState().getEncoded());
    }

    /**
     * Hands the callback to an endpoint that knows OneID's token request.
     *
     * <p>The same pattern Keycloak's own OIDC provider uses for its endpoint. The
     * inherited {@code authResponse} still verifies {@code state} and handles
     * errors; only the shape of the token request changes.</p>
     */
    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new OneIdEndpoint(callback, realm, event, this);
    }

    /**
     * Departure 3: user data comes from a grant call, not a userinfo endpoint.
     *
     * <p>Standard OAuth2 fetches the profile with {@code GET /userinfo} and a
     * Bearer header. OneID instead takes another POST to the same URL, with the
     * client id, the client secret and the access token as <em>form fields</em>.
     * Keycloak's inherited userinfo handling cannot express that, which is the
     * third and last reason this class exists.</p>
     */
    @Override
    protected BrokeredIdentityContext doGetFederatedIdentity(String accessToken) {
        try {
            JsonNode response = SimpleHttp.doPost(getConfig().getUserInfoUrl(), session)
                    .param(OAUTH2_PARAMETER_GRANT_TYPE, GRANT_TYPE_IDENTIFY)
                    .param(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
                    .param(OAUTH2_PARAMETER_CLIENT_SECRET, getConfig().getClientSecret())
                    .param(OAUTH2_PARAMETER_ACCESS_TOKEN, accessToken)
                    .param(OAUTH2_PARAMETER_SCOPE, getConfig().getDefaultScope())
                    .asJson();

            return toIdentity(OneIdUserInfo.from(response));
        } catch (IOException e) {
            // Never surface the raw cause to the user: it can carry endpoint
            // detail, and a login screen is not the place for it.
            log.errorf(e, "OneID identify call failed");
            throw new IdentityBrokerException("Could not obtain user information from OneID");
        }
    }

    /**
     * Maps the OneID payload onto the identity Keycloak will store.
     *
     * <h3>Why the PIN is the broker id</h3>
     *
     * <p>Keycloak recognises a returning person by this value, so it has to be
     * stable for life. {@code sess_id} changes every login, the passport number
     * changes on reissue, and {@code user_id} is documented as a <em>login</em>,
     * which an administrator can generally change. The PIN is the only field in
     * the payload that is immutable by design.</p>
     *
     * <p>{@code user_id} becomes the username instead, so it is what appears in
     * {@code preferred_username} and therefore in logs. A national identification
     * number has no business being there.</p>
     */
    private BrokeredIdentityContext toIdentity(OneIdUserInfo user) {
        if (!user.successful()) {
            // Log the code, which is diagnostic; show the person nothing about it.
            log.warnf("OneID returned ret_cd=%s", user.retCd());
            throw new IdentityBrokerException("OneID could not identify this user");
        }
        if (user.pin() == null || user.pin().isBlank()) {
            // Without the PIN there is nothing stable to link on, and creating an
            // account keyed on something mutable would silently duplicate the
            // person on their next login.
            throw new IdentityBrokerException("OneID response contained no PIN");
        }

        BrokeredIdentityContext identity =
                new BrokeredIdentityContext(user.pin(), getConfig());

        identity.setUsername(user.userId());
        identity.setFirstName(user.firstName());
        identity.setLastName(user.surName());
        identity.setName(user.fullName());
        identity.setIdp(this);

        identity.setUserAttribute(ATTR_ONEID_USER_ID, user.userId());
        identity.setUserAttribute(ATTR_USER_TYPE, user.userType());
        identity.setUserAttribute(ATTR_AUTH_METHOD, user.authMethod());
        // OneID's "valid" field: identity proofed by e-signature or Mobile-ID.
        // An unconfirmed account still logs in; the platform refuses individual
        // high-value OPERATIONS rather than refusing the person, which is the
        // behaviour a government portal should have.
        identity.setUserAttribute(ATTR_IDENTITY_VERIFIED, Boolean.toString(user.valid()));
        // Kept inside Keycloak. Never mapped into a token, never sent to a service.
        identity.setUserAttribute(ATTR_PIN, user.pin());

        // Legal entities become a multi-valued attribute, which a protocol mapper
        // turns into the org_tins claim. TINs only: no names, no flags, nothing
        // unbounded, and nothing that decides authorization on its own — the
        // membership table does that.
        if (!user.legalEntityTins().isEmpty()) {
            identity.setUserAttribute(ATTR_ORG_TINS, user.legalEntityTins());
        }

        // A legal-entity e-signature names its organization cryptographically,
        // so that one is recorded as authoritative rather than chosen.
        if (user.pkcsLegalTin() != null && !user.pkcsLegalTin().isBlank()) {
            identity.setUserAttribute("pkcs_legal_tin", user.pkcsLegalTin());
        }

        // Session-scoped, for logout correlation and audit. Not an identity.
        if (user.sessionId() != null) {
            identity.setSessionNote("oneid_sess_id", user.sessionId());
        }

        log.infof("OneID identity accepted: user_id=%s user_type=%s verified=%s legal_entities=%d",
                user.userId(), user.userType(), user.valid(), user.legalEntityTins().size());

        return identity;
    }

    /**
     * The callback endpoint, overriding only the token request.
     *
     * <p>Departure 2: {@code grant_type=one_authorization_code} instead of
     * {@code authorization_code}. Everything else about the exchange — the
     * single-use code, the client secret in the body, the redirect URI echo — is
     * ordinary, so only the grant name changes.</p>
     *
     * <p>Note what deliberately does not happen: no retry. An authorization code
     * is single use, so a retry can only fail, and a repeated exchange looks like
     * an attack rather than a hiccup.</p>
     */
    protected static class OneIdEndpoint extends Endpoint {

        private final OneIdIdentityProvider provider;

        public OneIdEndpoint(AuthenticationCallback callback, RealmModel realm, EventBuilder event,
                             OneIdIdentityProvider provider) {
            super(callback, realm, event, provider);
            this.provider = provider;
        }

        @Override
        public SimpleHttp generateTokenRequest(String authorizationCode) {
            OneIdIdentityProviderConfig config = provider.getConfig();
            return SimpleHttp.doPost(config.getTokenUrl(), session)
                    .param(OAUTH2_PARAMETER_GRANT_TYPE, GRANT_TYPE_AUTHORIZATION_CODE)
                    .param(OAUTH2_PARAMETER_CLIENT_ID, config.getClientId())
                    .param(OAUTH2_PARAMETER_CLIENT_SECRET, config.getClientSecret())
                    .param(OAUTH2_PARAMETER_CODE, authorizationCode)
                    // Must be byte-for-byte the URI the browser was redirected
                    // to, or OneID rejects the exchange. Built by Keycloak's own
                    // helper rather than assembled here, so it cannot drift from
                    // the value the authorization request used.
                    .param(OAUTH2_PARAMETER_REDIRECT_URI, brokerRedirectUri());
        }

        /** {@code {keycloak}/realms/{realm}/broker/{alias}/endpoint} */
        private String brokerRedirectUri() {
            return Urls.identityProviderAuthnResponse(
                            session.getContext().getUri().getBaseUri(),
                            provider.getConfig().getAlias(),
                            realm.getName())
                    .toString();
        }
    }
}
