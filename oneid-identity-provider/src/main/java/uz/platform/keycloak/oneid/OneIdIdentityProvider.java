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
import org.keycloak.models.UserSessionModel;
import org.keycloak.services.Urls;
import org.keycloak.sessions.AuthenticationSessionModel;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

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
 * <p>The protocol needs exactly three: the authorization URL, the token request,
 * and the identity fetch. Two more handle what happens around a session: which
 * notes Keycloak keeps on it, and {@code one_log_out} when it ends. Everything
 * else is inherited — {@code state} generation and verification, the callback
 * endpoint, session creation, federated identity storage, first-login detection,
 * account linking, and token issuance.</p>
 *
 * <h2>Where the OneID access token goes</h2>
 *
 * <p>The OneID access token is opaque, long-lived and can only be validated by
 * calling OneID again, against a documented ceiling of 300 requests per minute
 * for the whole client system. It is used here to fetch the identity. No
 * microservice ever receives it and the browser never sees it; they receive a
 * Keycloak token, which services verify offline against the realm's public
 * keys.</p>
 *
 * <p>Whether Keycloak keeps it afterwards is decided by one setting, in
 * {@link #authenticationFinished}. {@code one_log_out} needs the token, so with
 * {@code callOneIdLogout} on it is kept as a note on the Keycloak user session,
 * which Keycloak 26 persists in its own database, until that session ends. With
 * the setting off it is not kept at all. The inherited implementation kept it
 * unconditionally; that was found by reading the stored sessions, not by any
 * error.</p>
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
    static final String ATTR_PKCS_LEGAL_TIN = "pkcs_legal_tin";

    /** User session note: OneID's session id, for correlating logout and audit. */
    static final String NOTE_ONEID_SESS_ID = "oneid_sess_id";

    /** Carries {@code sess_id} from the identify call to {@link #authenticationFinished}. */
    static final String CONTEXT_ONEID_SESS_ID = "oneid.sess_id";

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
        // Deliberately NOT identity.setName(user.fullName()). setName splits a
        // full name at the first space into first and last name, overwriting the
        // two lines above. OneID's full_name is "Surname Given Patronymic", so the
        // split stored first name "Karimov" and last name "Ali Valiyevich". This
        // was caught by an end-to-end login, not by the compiler. OneID supplies
        // the parts separately; use them, and never re-derive them from the whole.
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
            identity.setUserAttribute(ATTR_PKCS_LEGAL_TIN, user.pkcsLegalTin());
        }

        // Written onto the user session in authenticationFinished, NOT with
        // identity.setSessionNote. At this point the identity has no
        // authentication session yet, so setSessionNote parks the note in a map
        // that only Keycloak's token exchange ever copies onto a session; in a
        // browser login it silently goes nowhere. Found by reading the stored
        // sessions: zero of them carried the note.
        if (user.sessionId() != null && !user.sessionId().isBlank()) {
            identity.getContextData().put(CONTEXT_ONEID_SESS_ID, user.sessionId());
        }

        log.infof("OneID identity accepted: user_id=%s user_type=%s verified=%s legal_entities=%d",
                user.userId(), user.userType(), user.valid(), user.legalEntityTins().size());

        return identity;
    }

    /**
     * Decides what Keycloak remembers on the new user session.
     *
     * <p>Runs once per login, for first and returning logins alike, just before
     * the Keycloak session is created.</p>
     *
     * <ul>
     *   <li>{@code oneid_sess_id} is always kept. It identifies a OneID session,
     *       not a person, and cannot be used to act as anyone.</li>
     *   <li>The OneID access token is kept only when {@code one_log_out} will
     *       need it. The inherited implementation, which this replaces, stored it
     *       for every session regardless — a long-lived bearer credential for the
     *       national identity system, sitting in Keycloak's session table for no
     *       purpose.</li>
     * </ul>
     */
    @Override
    public void authenticationFinished(AuthenticationSessionModel authSession, BrokeredIdentityContext context) {
        if (context.getContextData().get(CONTEXT_ONEID_SESS_ID) instanceof String sessId) {
            authSession.setUserSessionNote(NOTE_ONEID_SESS_ID, sessId);
        }
        if (getConfig().isCallOneIdLogout()) {
            // Stores the token under FEDERATED_ACCESS_TOKEN, where
            // endOneIdSession reads it back.
            super.authenticationFinished(authSession, context);
        }
    }

    /**
     * A browser logout: the person pressed Logout in an application.
     *
     * <p>Returns {@code null}, which tells Keycloak to carry on with its own
     * logout and redirect. OneID documents no browser logout page to send the
     * person to; {@code one_log_out} is a server-to-server call.</p>
     */
    @Override
    public Response keycloakInitiatedBrowserLogout(KeycloakSession session, UserSessionModel userSession,
                                                   UriInfo uriInfo, RealmModel realm) {
        endOneIdSession(session, userSession);
        return null;
    }

    /** A logout with no browser: an administrator ending the session, for example. */
    @Override
    public void backchannelLogout(KeycloakSession session, UserSessionModel userSession,
                                  UriInfo uriInfo, RealmModel realm) {
        endOneIdSession(session, userSession);
    }

    /**
     * Calls {@code one_log_out} for the OneID token this session holds, if any.
     *
     * <p>Never fails the Keycloak logout. If OneID is unreachable, the Keycloak
     * session still ends: refusing to log someone out because a third party is
     * down would be the worse failure.</p>
     *
     * <p>Not called when a session merely expires. Keycloak has no hook for that,
     * so an idle-expired session leaves its OneID session alone even with the
     * setting on.</p>
     */
    private void endOneIdSession(KeycloakSession session, UserSessionModel userSession) {
        if (!getConfig().isCallOneIdLogout()) {
            return;
        }
        String accessToken = userSession.getNote(FEDERATED_ACCESS_TOKEN);
        String sessId = userSession.getNote(NOTE_ONEID_SESS_ID);
        if (accessToken == null) {
            // A session from before the setting was turned on, or one whose
            // logout already ran.
            log.debugf("No OneID token held for sess_id=%s; one_log_out not sent", sessId);
            return;
        }
        // Removed before the call, not after: one logout cannot send the token
        // twice, and a failed call is not retried against a rate-limited service.
        userSession.removeNote(FEDERATED_ACCESS_TOKEN);

        try {
            JsonNode response = SimpleHttp.doPost(getConfig().getTokenUrl(), session)
                    .param(OAUTH2_PARAMETER_GRANT_TYPE, GRANT_TYPE_LOGOUT)
                    .param(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
                    .param(OAUTH2_PARAMETER_CLIENT_SECRET, getConfig().getClientSecret())
                    .param(OAUTH2_PARAMETER_ACCESS_TOKEN, accessToken)
                    .param(OAUTH2_PARAMETER_SCOPE, getConfig().getDefaultScope())
                    .asJson();
            String retCd = response.hasNonNull("ret_cd") ? response.get("ret_cd").asText() : "absent";
            log.infof("OneID one_log_out for sess_id=%s: ret_cd=%s", sessId, retCd);
        } catch (IOException | RuntimeException e) {
            // The exception type only: a message could echo request detail.
            log.warnf("OneID one_log_out failed for sess_id=%s (%s); the Keycloak session ends regardless",
                    sessId, e.getClass().getSimpleName());
        }
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
