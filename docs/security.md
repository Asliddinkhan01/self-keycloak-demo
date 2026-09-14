# Security

What the platform promises, where each promise is enforced, and what proves it. Tests named here
are described in [testing](testing.md).

## The principles, and where they are kept

| Principle | How it is enforced | Proven by |
|---|---|---|
| No OneID secrets in the frontend | The OneID client secret exists only in Keycloak's identity-provider configuration, imported from the environment. The browser never talks to OneID's back channel. | The built frontend image is searched for it; see [below](#checked-by-searching) |
| No OneID access tokens in the frontend | The OneID code exchange and identify call are server-to-server, inside Keycloak. The browser receives only Keycloak's tokens. | `AuthenticationE2ETest`, log scans in the journal (phases 10 and 11) |
| No OneID access tokens passed to microservices | Services receive only Keycloak's JWT. By default Keycloak does not even keep the OneID token. | `OneIdIdentityProviderTest` |
| No sensitive OneID information in logs | The provider logs `user_id`, `user_type`, whether the identity is verified, the number of legal entities and `sess_id`. It never logs the PIN, a token or a secret, and failed calls log the exception type only. mock-oneid logs the same. | log scans in the journal (phases 10 and 11) |
| No service account with `SUPER_ADMIN` | Service accounts hold client roles on organization-service only: `ORG_READ`, plus `ORG_MEMBERSHIP_SYNC` for user-service. | `ServiceToServiceE2ETest` 22 |
| No trust of `X-Organization-TIN` without membership validation | `OrganizationContextFilter` checks every header value against active memberships before any controller runs. | `OrganizationContextFilterTest`, `OrganizationContextE2ETest` 16–17 |
| Every microservice validates the Keycloak JWT independently | Every service and the gateway import `ResourceServerSecurity`. Each has its own audience list. | `AuthenticationE2ETest` 5, which sends a forged token straight to user-service |
| HTTPS in production | Local runs use HTTP. The [production checklist](#production-checklist) makes TLS the first item. | — |
| Secrets from environment variables | The realm import uses `${...}` placeholders. Service configuration and compose have no fallback secret values. `.env` is git-ignored and kept out of every Docker build context. | startup fails without them |
| Validate the JWT issuer | `issuer-uri` in every service; in containers the same check with a separate key URL. | a genuine token from Keycloak's master realm is refused, `AuthenticationE2ETest` 5 |
| Validate the JWT signature | RS256 against the realm's published keys. | edited claims under the original signature, and `alg: none`, are refused: `AuthenticationE2ETest` 5 |
| Validate token expiration | Spring's timestamp validator, with its default 60-second clock skew. | `LogoutE2ETest`, slow check: refused at expiry plus 60 s |
| No deprecated Keycloak adapters | Spring Security's OAuth2 resource server and client on the backend; keycloak-js, Keycloak's supported browser library, on the frontend. | `pom.xml`, `package.json` |
| No invented OneID endpoints | The provider uses the four documented operations on OneID's single URL. mock-oneid's `/sso/oauth/select` is the mock's stand-in for OneID's own login page, not part of the protocol. | `OneIdIdentityProvider`, `OneIdUserInfoTest` |
| No invented refresh-token behaviour | OneID's `refresh_token` is ignored and carried as an [open question](architecture.md#open-questions-for-the-oneid-operator). | — |

### Checked by searching

The frontend image that compose builds was unpacked and its served files searched:

| Searched for | Occurrences |
|---|---|
| the value of `ONEID_CLIENT_SECRET` from `.env` | 0 |
| the value of `USER_SERVICE_CLIENT_SECRET` | 0 |
| the value of `CADASTRAL_SERVICE_CLIENT_SECRET` | 0 |
| OneID's back-channel grant names: `one_authorization_code`, `one_access_token_identify`, `one_log_out` | 0 |
| control: the Keycloak URL the bundle does use | found |
| control: the `X-Organization-TIN` header name the bundle does send | found |

The controls matter: a search that finds nothing proves something only if it can find something.
None of the images checked (Keycloak, frontend, user-service) contains a `.env` file, which the
root `.dockerignore` and the per-module build contexts keep out.

---

## Security requirements, one by one

**Client secrets.** The OneID secret is used only in Keycloak's back-channel form posts. Service
client secrets are used only by Spring's OAuth2 client, which acquires, caches and renews tokens.
Nothing hand-rolls a token cache. The browser client is public and has no secret at all.

**Redirect URIs.** `platform-web` accepts exactly `http://localhost:5174/*` for sign-in and
post-logout redirects. The sign-in window returns to `/auth-callback.html` under it; any redirect
URI outside the pattern is refused with 400. For OneID, Keycloak's broker endpoint is built by Keycloak's own helper, so
the value sent in the code exchange cannot drift from the one sent in the authorization request.
Real OneID forbids `localhost`, so production needs a registered HTTPS hostname.

**State, nonce and issuer.** For each sign-in, the app generates `state`, `nonce` and a PKCE
verifier, and keeps all three in the app window's memory. It accepts only the callback answer
carrying its own `state`, refuses one whose `iss` is not the realm, and refuses an ID token whose
`nonce` is not its own. The callback page passes the code only over a `BroadcastChannel`, which is
same-origin by definition, and removes it from its history. For OneID, Keycloak generates its own
`state` and verifies it on the way back; the provider passes it through and never implements it.

**CSRF.** The APIs are stateless and authenticate only by the `Authorization` header, which a
browser never attaches on its own. No cookie authenticates an API call, so CSRF protection is
deliberately off on the resource servers. Keycloak's own login forms keep theirs.

**Token expiration.** Access tokens live 5 minutes; keycloak-js refreshes them 30 seconds before
expiry. Sessions end after 30 minutes idle or 10 hours in total.

**Refresh tokens.** Keycloak's refresh token stays in the browser's memory, never in local storage.
Logout revokes it at once. OneID's refresh token is not used.

**Logout.** It ends the Keycloak session on the server and revokes its refresh token, clears
tokens in every tab of the app, and optionally calls OneID. An access token already issued stays
valid until it expires; the bound, the measurement and the alternatives are in
[flows](flows.md#logout).

**CORS.** The gateway alone answers CORS, for one origin, five methods and three headers. Services
behind it have CORS disabled; browsers are not meant to reach them.

**HTTPS.** Not used locally. See the checklist.

**Secret storage.** Locally, `.env`. In production, a secret manager that injects the same
environment variables.

**Service-account credentials.** Two confidential clients, each with its own secret and its own
privilege set, and neither able to sign a person in: no browser flow and no direct grants.

**JWT validation.** Signature, issuer, expiry and audience, in every service, without calling
Keycloak per request.

**Audience validation.** Person tokens carry `platform-api`, accepted by the gateway and every
service. Service tokens carry `organization-service`, accepted only there. A cadastral-service
token presented to user-service or to the gateway is refused.

**Least privilege.** cadastral-service may read memberships and not write them. user-service may
do both, because session bootstrap reconciles them. Human roles grant permissions per service, and
no role is a wildcard: `SUPER_ADMIN` lacks `USER_DELETE`, which `ADMIN` has.

**Sensitive personal data.** What happens to each OneID field:

| OneID field | Kept | Where |
|---|---|---|
| `pin` | Yes | Keycloak only: broker link and a user attribute. Never in a token, a service or a log. |
| `user_id` | Yes | Keycloak username, therefore `preferred_username` |
| `sur_name`, `first_name` | Yes | Keycloak user, user-service profile |
| `mid_name`, `full_name` | No | `full_name` is deliberately not split; see the journal, phase 10 |
| `pport_no`, `birth_date` | No | received and discarded |
| `user_type`, `valid`, `auth_method` | Yes | Keycloak attributes; `user_type` and `valid` (as `identity_verified`) reach the token |
| `legal_info` | TINs only | `org_tins` attribute and claim, then memberships |
| `pkcs_legal_tin` | Yes, when present | Keycloak attribute |
| `sess_id` | Per session | Keycloak session note, for logout correlation |
| access token | No, by default | kept on the Keycloak session only when OneID logout is on |

**Audit logging.** Minimal, and listed as a limitation. Refused organization contexts are logged
with subject, TIN and path, and OneID identities are logged on acceptance. Keycloak's event store
is not enabled.

---

## Production checklist

The repository is configured for a laptop. Before anything else:

1. **TLS everywhere.** Set `KC_HOSTNAME` to an `https://` URL and realm `sslRequired` to `external`
   or `all`. Run Keycloak with `start --optimized` behind a proxy that forwards the right headers.
2. **Remove the development users** from the realm, and turn off direct access grants on
   `platform-web`.
3. **Set production URLs** for `platform-web`'s redirect URIs, web origins and post-logout
   redirect URIs, and for the gateway's CORS origin.
4. **Register with OneID.** Register an HTTPS redirect URI, point the three OneID URLs at
   sso.egov.uz, load the issued credentials from a secret store, and decide `ONEID_CALL_LOGOUT`.
5. **Handle secrets properly.** Take every secret from a secret manager and rotate service client
   secrets. Replace the literal database role passwords in `postgres/init/01-schemas.sql` and
   compose.
6. **Expose only the gateway and Keycloak.** Services are internal; `/internal/**` must not be
   reachable from outside the network.
7. **Lower logging** from DEBUG to INFO. Enable Keycloak's event store, and ship logs centrally.
8. **Review token lifetimes** against how quickly a logout must take effect.
9. **Add rate limiting** at the edge, and explicit timeouts on Keycloak's outgoing HTTP client.
10. **Back up** the Keycloak schema and every service schema.
11. **Add a Content-Security-Policy** to the frontend's nginx configuration, naming the real
    Keycloak and gateway origins.
