# Configuration

Every setting, who reads it, and what it becomes in production. Values come from environment
variables. Locally, `docker compose` reads them from `.env`, which you create from `.env.example`
and which git ignores.

## Two ways to run

**Everything in Docker Compose.** The default, and the one the README's quick start uses:

```bash
mvn -DskipTests package
```

```bash
docker compose up -d --build
```

**Infrastructure in Compose, a service from your IDE.** Useful for debugging one service:

```bash
docker compose up -d postgres keycloak mock-oneid
```

A service started on the host uses the `localhost` defaults in its `application.yml`, but its
secrets have no defaults: set them in the run configuration, for example
`USER_SERVICE_CLIENT_SECRET` for user-service, with the values from `.env`. If a container of the
same service is running, stop it first to free the port:

```bash
docker compose stop user-service
```

Containers find each other by service name. So if you move organization-service to the host, the
user-service and cadastral-service containers can no longer reach it at `organization-service:8092`.
Run those on the host too, or point their `ORGANIZATION_SERVICE_URL` at
`http://host.docker.internal:8092`.

---

## One server, two names

Inside a container, `localhost` is the container itself. So every server a container calls has two
names: the one your browser uses, and the one containers use inside the Docker network.

| Server | From the browser or host | From a container |
|---|---|---|
| Keycloak | `http://localhost:8190` | `http://keycloak:8080` |
| mock OneID | `http://localhost:8191` | `http://mock-oneid:8191` |
| api-gateway | `http://localhost:8090` | not called by containers |
| user-service | `http://localhost:8091` | `http://user-service:8091` |
| organization-service | `http://localhost:8092` | `http://organization-service:8092` |
| cadastral-service | `http://localhost:8093` | `http://cadastral-service:8093` |
| PostgreSQL | `localhost:5452` | `postgres:5432` |
| Vue app | `http://localhost:5174` | not called by containers |

This matters most for Keycloak. Every token's `iss` claim is Keycloak's **public** URL
(`KC_HOSTNAME`), because that is where browsers find it. Services must require exactly that
issuer, yet cannot reach that URL from their containers. Compose resolves it by giving each service
two settings:

| Setting | Value in a container | Purpose |
|---|---|---|
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8190/realms/platform` | the `iss` every token must carry |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` | `http://keycloak:8080/realms/platform/protocol/openid-connect/certs` | where to fetch the public signing keys |

With a JWK set URI configured, Spring downloads keys from it and skips issuer discovery, but still
validates `iss` against the issuer URI, along with signature, expiry and audience. A service run
from the IDE has no JWK set URI, so it uses issuer discovery against `localhost`, which works on the host.

Mock OneID has the same split, for the same reason: the authorization URL is where the **browser**
is sent, and the token and identify URLs are called by **Keycloak** from its container.

In production both names are the same public HTTPS host for Keycloak and OneID, and the services are
reachable only inside the private network.

---

## Environment variables

### In `.env`

| Variable | Local value | Read by | Production |
|---|---|---|---|
| `POSTGRES_DB` | `appdb` | PostgreSQL, Keycloak, services | your database |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | `postgres` | PostgreSQL superuser, used only by the init script | from a secret store |
| `POSTGRES_PORT` | `5452` | host port | not published |
| `KEYCLOAK_PORT` | `8190` | host port | behind TLS |
| `KEYCLOAK_ADMIN_USER`, `KEYCLOAK_ADMIN_PASSWORD` | `admin` | Keycloak bootstrap admin; the e2e tests use it to inspect state. **Required.** | from a secret store; rotate after first start |
| `KEYCLOAK_HOSTNAME_URL` | `http://localhost:8190` | Keycloak `KC_HOSTNAME`, therefore every token's issuer; the frontend build; the services' issuer check | `https://` public host |
| `KEYCLOAK_REALM` | `platform` | URLs derived in compose, the frontend build, the e2e tests | `platform` |
| `ONEID_AUTHORIZATION_URL` | `http://localhost:8191/sso/oauth/Authorization.do` | Keycloak realm import; the browser is redirected here | `https://sso.egov.uz/sso/oauth/Authorization.do` |
| `ONEID_TOKEN_URL`, `ONEID_IDENTIFY_URL` | `http://mock-oneid:8191/sso/oauth/Authorization.do` | Keycloak realm import; Keycloak calls these | the same URL as above |
| `ONEID_CLIENT_ID`, `ONEID_CLIENT_SECRET`, `ONEID_SCOPE` | `platform`, placeholder secret | Keycloak realm import and mock-oneid. **Required.** | issued by the OneID operator |
| `ONEID_REDIRECT_URI` | Keycloak's broker endpoint | nothing: the value to register with the OneID operator | an HTTPS hostname |
| `ONEID_CALL_LOGOUT` | `false` | Keycloak realm import; see [flows](flows.md#logout) | a decision, not a default |
| `USER_SERVICE_CLIENT_SECRET` | placeholder | Keycloak realm import, user-service, e2e tests. **Required.** | from a secret store |
| `CADASTRAL_SERVICE_CLIENT_SECRET` | placeholder | Keycloak realm import, cadastral-service, e2e tests. **Required.** | from a secret store |
| `GATEWAY_PORT` | `8090` | host port; server port on the host | behind TLS |
| `USER_SERVICE_PORT`, `ORGANIZATION_SERVICE_PORT`, `CADASTRAL_SERVICE_PORT` | `8091`–`8093` | host ports; server ports on the host | not published |
| `MOCK_ONEID_PORT` | `8191` | host port | not deployed |
| `FRONTEND_PORT` | `5174` | host port; the gateway's CORS origin | behind TLS |

**Required** means there is no fallback anywhere in the repository. Compose stops with
`set ... in .env`, and a service started without it fails at startup. The realm import reads the
OneID and client secrets once, when the realm is first created.

`FRONTEND_PORT` is also written into the realm: `platform-web`'s redirect URIs, web origins and
post-logout redirect URIs all name `http://localhost:5174`. Changing the port means changing those
too.

### Set by `docker-compose.yml`, not in `.env`

| Variable | Container value | Read by | Host default |
|---|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `postgres`, `5432`, `appdb` | services | `localhost`, `5452`, `appdb` |
| `DB_USER`, `DB_PASSWORD` | not set | services | the service's own role, for example `user_service` |
| `KEYCLOAK_ISSUER_URI` | Keycloak's public realm URL | gateway, services | `http://localhost:8190/realms/platform` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` | `http://keycloak:8080/.../certs` | gateway, services | not set: issuer discovery |
| `KEYCLOAK_TOKEN_URI` | `http://keycloak:8080/.../token` | user-service, cadastral-service | `http://localhost:8190/.../token` |
| `ORGANIZATION_SERVICE_URL` | `http://organization-service:8092` | gateway, user-service, cadastral-service | `http://localhost:8092` |
| `USER_SERVICE_URL`, `CADASTRAL_SERVICE_URL` | service names | gateway | `localhost` ports |
| `FRONTEND_ORIGIN` | `http://localhost:5174` | gateway CORS | `http://localhost:5174` |
| `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, `VITE_KEYCLOAK_CLIENT_ID`, `VITE_GATEWAY_URL` | browser-facing URLs | frontend **build** | the same, in `keycloak.js` and `api.js` |

The `VITE_*` values are compiled into the JavaScript, so they are always the URLs a browser uses.
Changing them means rebuilding the frontend image.

### Tuning

| Variable | Default | Effect |
|---|---|---|
| `MEMBERSHIP_CACHE_TTL` | `60` | seconds cadastral-service trusts a membership answer |
| `PLATFORM_SECURITY_PERMISSIONREFRESHMS` | `300000` | milliseconds between permission catalog refreshes, in every service with a permission table |

---

## Realm configuration

`keycloak/import/realm-export.json` is imported the **first** time Keycloak starts with an empty
database. After that it is ignored, because the import strategy never overwrites an existing
realm. To apply a change to the file, reset the database:

```bash
docker compose down -v
```

That also resets every service schema. To change a live realm without losing data, use the admin
console at `http://localhost:8190` instead.

| Setting | Value | Why |
|---|---|---|
| `accessTokenLifespan` | 300 s | short, because a copied access token cannot be recalled |
| `ssoSessionIdleTimeout` | 1800 s | idle sessions end after 30 minutes |
| `ssoSessionMaxLifespan` | 36000 s | every session ends after 10 hours |
| `sslRequired` | `none` | **local only**: the mock runs on HTTP. Production: `external` or `all` |
| `registrationAllowed` | `false` | accounts come from OneID, not from a sign-up form |
| `platform-web` | public client, PKCE S256, exact redirect URIs | a browser cannot keep a secret |
| `platform-web` direct access grants | enabled | **local only**, for scripted tests. Production: off |
| `user-service`, `cadastral-service` | confidential, service accounts, no browser flows | machine identities |
| `organization-service` | no flows at all | exists only to own client roles and be a token audience |
| OneID identity provider | `storeToken` off, sync mode `FORCE`, first-login profile review off | OneID is authoritative for its own data |
| User profile | `email` not required | OneID provides no email address |

---

## Ports

Every published port is deliberately off its default. 5432 and 8080 are the two most contested
ports on a developer machine. A clash there is hard to diagnose: a service silently fetches some
other program's response instead of Keycloak's keys, then rejects every token with a bare `401`
and no error description.

The Compose project name is pinned to `oneid-platform`, so moving or renaming the folder does not
orphan the running containers and the data volume.
