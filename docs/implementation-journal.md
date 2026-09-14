# Implementation journal

How the platform was built, phase by phase, and what each phase proved. It is kept because the
findings are the most instructive part of the project: each "things this phase found" section
describes a mistake that compiled, started and looked right.

**Read it as history.** Commands, file names and ports are as they were at the end of each phase,
and a later phase sometimes changes what an earlier one describes; where that happened, the later
section says so. For running the platform today see the [README](../README.md), and for the
design as it stands see [architecture](architecture.md).

Phase 13 put the whole application into Docker Compose and changed three things that earlier
sections describe differently:

- The Keycloak image takes the provider jar straight from `oneid-identity-provider/target`.
  There is no longer a copy step into `keycloak/target-provider`.
- user-service and cadastral-service configure their OAuth2 client with Keycloak's `token-uri`
  instead of `issuer-uri`, so they no longer call Keycloak at startup.
- Client secrets, the Keycloak admin credentials and the OneID credentials no longer have
  committed fallback values. They come from the environment or `.env`, or startup fails.

---

## Running the infrastructure (as of phases 1–2)

```bash
cp .env.example .env
```

```bash
docker compose up -d
```

Wait for the realm import, then open http://localhost:8190 and sign in as `admin` / `admin`.

```bash
docker compose logs -f keycloak
```

You are looking for `Realm 'platform' imported` followed by the Keycloak startup line.

To reset everything, including the Keycloak realm and all service schemas:

```bash
docker compose down -v
```

That is required after editing `realm-export.json` (an existing realm is never overwritten;
the import strategy is `IGNORE_EXISTING`) or `postgres/init/01-schemas.sql` (init scripts run
only when the data directory is first created).

---

## Verifying phase 1–2

**The five schemas exist, each owned by its own role:**

```bash
docker exec oneid-postgres psql -U postgres -d appdb -c "\dn"
```

**Isolation is enforced by PostgreSQL, not by convention.** This is refused:

```bash
docker exec -e PGPASSWORD=cadastral_service oneid-postgres psql -U cadastral_service -d appdb -c "SELECT 1 FROM keycloak.realm"
```

```
ERROR:  permission denied for schema keycloak
```

**Keycloak stores its realm in PostgreSQL, not in an embedded file:**

```bash
docker exec oneid-postgres psql -U postgres -d appdb -c "SELECT name FROM keycloak.realm"
```

**A human token has the designed shape.** `platform-web` has direct access grants enabled for
local testing only; the browser uses the authorization-code flow with PKCE.

```bash
curl -s -d "client_id=platform-web" -d "username=ali" -d "password=password" -d "grant_type=password" http://localhost:8190/realms/platform/protocol/openid-connect/token
```

Decode the `access_token` at https://jwt.io and check:

| Claim | Expected |
|---|---|
| `realm_access.roles` | `QURUVCHI`, `YURIDIK_SHAXS`, `JISMONIY_SHAXS` |
| `token_use` | `user` |
| `org_tins` | `["111111111","222222222"]` |
| `identity_verified` | `true` |
| `pin` | **absent** — by design, see below |

**A service token is visibly different:**

```bash
curl -s -d "client_id=cadastral-service" -d "client_secret=cadastral-service-secret" -d "grant_type=client_credentials" http://localhost:8190/realms/platform/protocol/openid-connect/token
```

| Claim | Expected |
|---|---|
| `azp` | `cadastral-service` |
| `aud` | `organization-service` |
| `token_use` | `service` |
| `resource_access["organization-service"].roles` | `["ORG_READ"]` — and nothing else |

That last row is least privilege made concrete. cadastral-service can read organizations and
can do nothing else, anywhere.

---

## Verifying phase 3

Start the infrastructure, then each service in its own terminal:

```bash
mvn -DskipTests install
```

```bash
cd user-service && mvn spring-boot:run
```

```bash
cd organization-service && mvn spring-boot:run
```

```bash
cd cadastral-service && mvn spring-boot:run
```

Each one applies its Flyway migration into its own schema on first start:

```
Successfully applied 1 migration to schema "user_service", now at version v1
```

**The public endpoint answers with no token at all:**

```bash
curl -i http://localhost:8091/api/public/hello
```

**Everything else answers 401 with no token:**

```bash
curl -o /dev/null -w "%{http_code}\n" http://localhost:8091/api/users/me
curl -o /dev/null -w "%{http_code}\n" http://localhost:8092/api/organizations
curl -o /dev/null -w "%{http_code}\n" http://localhost:8093/api/projects
```

**And 200 with a valid Keycloak token:**

```bash
TOKEN=$(curl -s -d "client_id=platform-web" -d "username=ali" -d "password=password" -d "grant_type=password" http://localhost:8190/realms/platform/protocol/openid-connect/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8091/api/users/me
```

### The gap phase 4 closed

That last response is worth reading carefully:

```json
"realmRolesFromToken": ["JISMONIY_SHAXS", "QURUVCHI", "YURIDIK_SHAXS"],
"springAuthorities":   ["SCOPE_email", "SCOPE_profile"]
```

That was phase 3. The token plainly carried three roles and Spring Security had none of them —
the single most common Keycloak-with-Spring failure, and not a bug: Spring's default converter
reads only the `scope` claim and knows nothing about Keycloak's `realm_access`.

With the phase 4 converter in place the same call now returns:

```json
"realmRolesFromToken": ["JISMONIY_SHAXS", "QURUVCHI", "YURIDIK_SHAXS"],
"springAuthorities":   ["ROLE_JISMONIY_SHAXS", "ROLE_QURUVCHI", "ROLE_YURIDIK_SHAXS",
                        "SCOPE_email", "SCOPE_profile", "TOKEN_USE_USER"]
```

### Telling a decoder failure from a bad token

```bash
curl -i -H "Authorization: Bearer not.a.real.token" http://localhost:8091/api/users/me
```

```
WWW-Authenticate: Bearer error="invalid_token", error_description="..."
```

The presence of `error="invalid_token"` proves the decoder initialised and judged the token. A
**bare** `WWW-Authenticate: Bearer` with no error description means the opposite: the decoder
could not reach Keycloak at all, so no token can ever succeed. Learn to tell those two apart.

---

## Verifying phase 4

Phase 4 adds three things to `platform-security`: the authorities converter, an explicit
principal-name resolver, and caller-type detection. Run user-service and organization-service,
then get tokens:

```bash
tok() { curl -s -d "client_id=platform-web" -d "username=$1" -d "password=password" -d "grant_type=password" http://localhost:8190/realms/platform/protocol/openid-connect/token | jq -r .access_token; }
```

```bash
svc() { curl -s -d "client_id=$1" -d "client_secret=$1-secret" -d "grant_type=client_credentials" http://localhost:8190/realms/platform/protocol/openid-connect/token | jq -r .access_token; }
```

### Roles now reach Spring Security

```bash
curl -s -H "Authorization: Bearer $(tok ali)" http://localhost:8091/api/users/me | jq '{realmRolesFromToken, springAuthorities}'
```

### SUPER_ADMIN is not a superset of ADMIN

Both directions are checked, because a role that is silently a wildcard would pass only one:

| Caller | `GET /api/admin/users` | `GET /api/platform/audit` |
|---|---|---|
| `ali` (QURUVCHI) | 403 | 403 |
| `admin_user` (ADMIN) | **200** | 403 |
| `super_admin` (SUPER_ADMIN) | 403 | **200** |

```bash
curl -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $(tok super_admin)" http://localhost:8091/api/admin/users
```

### Humans and machines are told apart

The `token_use` claim becomes an authority, so the distinction is enforced by an ordinary URL
rule rather than a custom `AuthorizationManager`:

| Caller | `GET :8092/internal/ping` | `GET :8091/api/users/me` |
|---|---|---|
| `ali` (human token) | 403 | **200** |
| `cadastral-service` (service token) | **200** | 403 |

```bash
curl -s -H "Authorization: Bearer $(svc cadastral-service)" http://localhost:8092/internal/ping | jq
```

```json
{
  "principal": "service-account-cadastral-service",
  "callerType": "SERVICE",
  "callingClient": "cadastral-service",
  "audience": ["organization-service"],
  "rolesGrantedHere": ["ORG_READ"]
}
```

That last field is least privilege you can see. The token carries `ORG_READ` against
organization-service and nothing else, anywhere — no `USER_*`, no write permission, and no
business role. It is a machine identity, not a person wearing `SUPER_ADMIN`.

### How the mapping works

```
realm_access.roles          ["QURUVCHI"]     ->  ROLE_QURUVCHI
resource_access.<me>.roles  ["ORG_READ"]     ->  ORG_READ
scope                       "profile email"  ->  SCOPE_profile, SCOPE_email
token_use                   "user"           ->  TOKEN_USE_USER
```

The `ROLE_` prefix is not decorative. `hasRole("ADMIN")` is evaluated as
`hasAuthority("ROLE_ADMIN")`; Spring adds the prefix when **checking** and never when
**building**, so the converter must add it. Keycloak roles are named without it precisely so
that the mapping stays one readable line.

Client roles get no prefix, because they are machine permissions rather than business roles and
read better as `hasAuthority('ORG_READ')`. Only roles granted against **this** service are
mapped, which is what makes per-service scoping real rather than advisory.

To see why the converter matters, comment out this line in `ResourceServerSecurity` and rerun
the table above — every role check starts failing against tokens that visibly carry the role:

```java
.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
```

---

## Verifying phase 5

Permissions live in the database, roles live in Keycloak, and the two meet in memory.

### Where the tables are, and why

Each service owns the `roles`, `permissions` and `role_permissions` tables **for the
permissions it enforces**. user-service defines `USER_*` and `PLATFORM_ADMIN`,
cadastral-service defines `PROJECT_*` and `PAYMENT_*`, organization-service defines
`ORGANIZATION_*`.

A central authorization service reads better on a diagram and has one serious flaw: it puts a
network dependency on the critical path of *every* authorization decision in *every* service.
When it is down, each caller must choose between failing closed — a platform-wide outage caused
by one component — and serving stale data it cannot verify. Local ownership removes that
failure mode, and the mapping is versioned in the same migration history as the endpoints it
protects, so the two cannot drift.

The cost is real: "what can QURUVCHI do across the whole platform?" now means asking every
service. That is an administrative question rather than a request-path one, and phase 7's
service-to-service machinery is the natural way to aggregate it.

There is **no `user_roles` table anywhere**. Role assignment lives in Keycloak and only in
Keycloak; a local copy would be a second source of truth and always the stale one.

### Permissions are resolved per request, not per login

`KeycloakAuthoritiesConverter` expands the token's roles into permissions using an in-memory
catalog loaded at startup and refreshed on a schedule. The whole table is a few hundred rows,
because permissions depend on roles and never on individual users, so there is no per-user
cache and nothing to invalidate. Two consequences worth knowing:

- Granting a permission takes effect on the caller's **next request**, not their next login.
- The token never grows as the platform gains resources, because no permission is ever a claim.

### The union of roles

```bash
tok() { curl -s -d "client_id=platform-web" -d "username=$1" -d "password=password" -d "grant_type=password" http://localhost:8190/realms/platform/protocol/openid-connect/token | jq -r .access_token; }
```

| Caller | Roles | `POST /api/projects` | `POST /api/payments` |
|---|---|---|---|
| `ali` | QURUVCHI | **201** | 403 |
| `bank_user` | BANK | 403 | **201** |
| `dual` | QURUVCHI + BANK | **201** | **201** |
| `super_admin` | SUPER_ADMIN | 403 | 403 |

`dual` needs no special case anywhere in the code. Effective permissions are the set union
across a caller's roles, with no precedence and no role containing another.

The last row is the point of the design. `SUPER_ADMIN` holds no `PROJECT_*` permission, so the
platform administrator cannot register a construction project. Roles here are sets of
capabilities, not levels.

### No role is quietly a wildcard

```bash
curl -o /dev/null -w "%{http_code}\n" -X DELETE -H "Authorization: Bearer $(tok admin_user)" http://localhost:8091/api/admin/users/00000000-0000-0000-0000-000000000001
```

| Caller | `DELETE /api/admin/users/{id}` | `GET /api/platform/audit` |
|---|---|---|
| `admin_user` (ADMIN) | **404** — allowed through, that id does not exist | 403 |
| `super_admin` (SUPER_ADMIN) | **403** — refused, no `USER_DELETE` | **200** |

Reading 404 as success here matters: it means the permission check passed and the handler ran.

### Inspecting the live catalog

`SUPER_ADMIN` can see exactly what this service enforces, without a database client:

```bash
curl -s -H "Authorization: Bearer $(tok super_admin)" http://localhost:8091/api/platform/audit | jq .rolePermissionsEnforcedHere
```

And any caller can see their own effective permissions, per service:

```bash
curl -s -H "Authorization: Bearer $(tok ali)" http://localhost:8091/api/users/me | jq '{realmRolesFromToken, effectivePermissionsHere}'
```

`effectivePermissionsHere` is empty for `ali` at user-service and non-empty at
cadastral-service. That is not a bug: user-service defines no permission that QURUVCHI holds.
The field name says *here* for exactly that reason.

### Both annotation spellings work

```java
@PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_CREATE')")
@PreAuthorize("hasAuthority('PROJECT_CREATE')")
```

They do the same work, because permissions are already granted authorities by the time either
runs. The named bean is kept because it says "permission" at the call site, which distinguishes
it at a glance from the role checks, and because it is the seam where phase 6's
organization-aware check lands without touching every annotation.

**Prefer permissions to roles on business endpoints.** A role check hardcodes an organisational
fact into code: letting another role do the same thing means editing and redeploying every
affected endpoint. A permission check states a stable requirement, and granting it elsewhere is
one row in a table. Roles stay for coarse gates.

---

## Verifying phase 6

A person may belong to several legal entities. OneID says so: one physical person can carry a
`legal_info` array with many entries. The organization is therefore **request context, not
identity** — one account, one PIN, one login, and an acting organization that can change
between two consecutive calls.

### The header is a request, never an assertion

```
X-Organization-TIN: 111111111
```

Anyone can put nine digits in a header. Trusting that value would be the largest hole an
application like this can have: any authenticated user could file documents, read records and
incur obligations on behalf of any company in the country.

So every organization-scoped request is checked against `user_organizations` before the
controller runs. `ali` belongs to two organizations and not to a third:

| Header | Result |
|---|---|
| `111111111` — member | **201** |
| `222222222` — member | **201** |
| `444444444` — exists, not a member | **403** |
| `999999999` — invented | **403** |
| *(no header)* | **400** |

```bash
curl -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $(tok ali)" -H "X-Organization-TIN: 444444444" -H "Content-Type: application/json" -d '{"name":"x"}' http://localhost:8093/api/projects
```

```json
{"error":"organization_membership_required","message":"You are not an active member of organization 444444444"}
```

A refusal is logged with the subject and the claimed TIN, which turns an attempt into a
detection signal rather than a silent leak. No personal data and no token value is written.

The missing-header case is **400, not 403**, because the caller may well be entitled to act for
an organization and simply did not say which. Refusing rather than guessing a default matters:
filing a construction project against a silently chosen company is worse than an error.

### Switching organizations needs no new token

The same bearer token, the same endpoint, a different header:

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Organization-TIN: 111111111" http://localhost:8093/api/projects
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Organization-TIN: 222222222" http://localhost:8093/api/projects
```

Each returns only that organization's projects. Scoping the query by the verified TIN *is* the
authorization; returning everything and letting the UI filter would leak one company's
construction records to another. A project outside the acting organization is reported as
**404 rather than 403**, because "this exists but is not yours" confirms the existence of
another company's records to someone with no right to know.

### The database decides, not the token

The token carries `org_tins`, and checking against that claim would be faster and entirely
local. It is deliberately not what happens. The claim only **seeds** membership;
`user_organizations` decides.

That difference is visible. `malika`'s token has no `org_tins` claim at all. Grant her a
membership directly, as an administrator would:

```bash
docker exec oneid-postgres psql -U postgres -d appdb -c "INSERT INTO organization_service.user_organizations (keycloak_sub, organization_id, is_basic, source, active) SELECT '<her-sub>', id, false, 'MANUAL', true FROM organization_service.organizations WHERE tin='111111111'"
```

Within the cache window she can act for that organization, using the same token she already
had, with no re-login and no new claim. Reconciliation leaves the row alone because its source
is `MANUAL` — OneID has no opinion about memberships it never granted. With a claim check,
neither the grant nor a revocation would take effect until the token expired.

### How a service that owns no membership data answers the question

cadastral-service cannot read `user_organizations`: its PostgreSQL role has no privileges on
that schema, and a query is refused by the database. So it asks organization-service over HTTP
**as itself**, with a client-credentials token:

```
cadastral-service ──client_credentials──▶ Keycloak
                  ──Bearer service token──▶ organization-service /internal/memberships/{sub}
```

That endpoint is protected twice over. The `/internal/**` rule requires `token_use: service`,
so no browser reaches it, and `ORG_READ` means only a service actually granted that permission
gets an answer. A human token is refused:

```bash
curl -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $(tok ali)" http://localhost:8092/internal/memberships/00000000-0000-0000-0000-000000000000
```

```
403
```

Answers are cached for 60 seconds per person, so a burst of requests costs one call and a
revocation takes effect within a minute. A **failed** lookup is never cached, so an outage at
organization-service cannot freeze a wrong answer in place, and it fails closed: refusing a
legitimate request during an outage is recoverable, allowing an illegitimate one is not.

### A bug worth knowing about

The first implementation put `@RequestScope` on the `OrganizationContext` **class**, while the
bean was created by an `@Bean` method. Spring honours class-level scope only for
component-scanned beans, so the annotation was **silently ignored** and the bean was a
singleton.

It looked like it worked. Membership checks passed and forged TINs were refused. What actually
happened is that one caller's acting organization stayed set for the next caller's request, and
a request sending no header inherited whichever organization was last used — which is how the
missing-header case returned 201 instead of 400. The scope now lives on the `@Bean` method,
where it takes effect.

---

## Verifying phase 7

Two services now call organization-service, and the phase is about what separates them.

### Two machine identities, two different privilege sets

```bash
svc() { curl -s -d "client_id=$1" -d "client_secret=$1-secret" -d "grant_type=client_credentials" http://localhost:8190/realms/platform/protocol/openid-connect/token | jq -r .access_token; }
```

| Caller | `GET /internal/memberships/{sub}` | `POST /internal/memberships/{sub}/sync` |
|---|---|---|
| `cadastral-service` | **200** — has `ORG_READ` | **403** — no `ORG_MEMBERSHIP_SYNC` |
| `user-service` | **200** | **200** — has both |

cadastral-service reads whether someone belongs to a company, because that is all it ever
needs. user-service also writes, because bootstrapping a session is its job. Neither is
"a trusted internal service" with blanket access, and that distinction is two rows of client
roles in the realm rather than a convention anyone has to remember.

### Neither service account is SUPER_ADMIN

```bash
svc cadastral-service   # then decode the payload
```

```json
{
  "azp": "cadastral-service",
  "aud": "organization-service",
  "token_use": "service",
  "realm_access": { "roles": [] },
  "resource_access": { "organization-service": { "roles": ["ORG_READ"] } }
}
```

**No realm roles at all.** A service account carries client roles scoped to the service it
calls, and holds no business role anywhere. That is what makes an audit line mean something:
`SUPER_ADMIN` in a log now genuinely means a person did something, because no machine can
produce it.

Giving a service `SUPER_ADMIN` instead would fail in four ways at once. It abandons least
privilege, since a service that needs to read memberships could then delete users. It destroys
audit meaning. It makes one compromised service equal to full platform authority. And it cannot
be revoked without also revoking the humans who share the role.

### Audience validation

Spring does **not** validate `aud` by default. It is now switched on in every service, with two
accepted values: `platform-api`, which every human token carries, and the service's own name,
which service tokens addressed to it carry.

| Token | organization-service | cadastral-service | user-service |
|---|---|---|---|
| service token, `aud: organization-service` | **200** | **401** | **401** |
| human token, `aud: platform-api` | 200 | 200 | 200 |

```
WWW-Authenticate: Bearer error="invalid_token",
  error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid"
```

This is what stops a leaked or misdirected service token being replayed somewhere it was never
meant for. The signature, issuer and expiry on that token are all perfectly valid; the audience
is what refuses it.

### One write path

`GET /api/organizations/mine` is now read-only. Reconciliation moved to
`POST /internal/memberships/{sub}/sync`, reachable only by a service holding
`ORG_MEMBERSHIP_SYNC`. Two write paths to the same table is how they drift apart, so there is
one, and a browser cannot reach it.

Session bootstrap is therefore a single call the frontend makes after login:

```bash
curl -s -H "Authorization: Bearer $(tok ali)" http://localhost:8091/api/users/me | jq
```

user-service upserts the local profile from the verified token, calls organization-service as a
service to reconcile memberships, and returns roles, effective permissions and active
organizations together — everything the UI needs to render an organization switcher and hide
actions the person cannot perform.

Hiding actions in the UI is a convenience, never the control. Every endpoint checks the
permission again on the server, because a hidden button is one curl command away from being
pressed anyway.

### Where the OAuth2 client wiring lives

`ServiceWebClients` in `platform-security` builds both pieces: an
`AuthorizedClientServiceOAuth2AuthorizedClientManager` for the client-credentials grant, and a
`WebClient` with the exchange filter that attaches the token. Spring acquires, caches and
renews; there is no hand-written token cache anywhere in this project, because hand-rolled
caching is where refresh bugs live.

Call sites contain no security code at all — `OrganizationClient` just makes an HTTP call.

---

## Verifying phase 8

Everything now runs behind one entry point, and the browser talks only to it.

```bash
cd api-gateway && mvn spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

Open http://localhost:5174.

### What the gateway does, and what it refuses to do

It validates the token at the edge, owns CORS, routes, and forwards the request
**unchanged**. It has no database, imports `ResourceServerSecurity` but not
`PermissionSecurity`, and has no idea what `PROJECT_CREATE` means.

It also never replaces the token with headers such as `X-User-Id`. That would turn a signed,
verifiable assertion into a forgeable string and make every service depend on the gateway being
the only possible caller — the assumption that fails the day something reaches a service
directly.

Every behaviour still holds when the call goes through :8090 rather than straight to a service:

| Request through the gateway | Result |
|---|---|
| `/api/public/hello`, no token | 200 |
| `/api/users/me`, no token | 401 |
| `/api/users/me`, `ali` | 200 |
| `/api/projects`, `ali` + valid TIN | 200 |
| `/api/projects`, `ali`, no TIN header | 400 |
| `/api/projects`, `ali`, TIN 444444444 | 403 |
| `/internal/ping`, human token | 403 |

The last row is worth reading twice. `/internal/**` has **no gateway route at all**, so it
cannot be proxied to a service — and the shared security rule refuses a human token there
anyway. Two independent layers, and neither relies on the other.

### Services still validate for themselves

Edge validation is a filter, not a guarantee. A misrouted internal call, a port-forward during
debugging, or a future infrastructure change can all reach a service without passing through
the gateway, which is why every service repeats signature, issuer, audience and permission
checks. The two are not redundant; they defend different things.

### CORS

```bash
curl -i -X OPTIONS -H "Origin: http://localhost:5174" -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: authorization,content-type,x-organization-tin" http://localhost:8090/api/projects
```

```
HTTP/1.1 200
Access-Control-Allow-Origin: http://localhost:5174
Access-Control-Allow-Headers: authorization, content-type, x-organization-tin
```

Another origin gets 403. Note that `X-Organization-TIN` has to be named in the allow-list or
the browser blocks the preflight and the header never arrives — a failure that looks like a
backend bug and is not.

CORS is switched on **only** at the gateway. A service that declares no `CorsConfigurationSource`
gets it disabled, because nothing calls it cross-origin and an unnecessary allow-list is an
unnecessary thing to get wrong. And CORS is a browser rule, never authorization: curl ignores
it entirely, which is why an endpoint can work in curl and fail in the app.

### The frontend

One page, deliberately plain, exercising each layer of the model:

- **Load session** calls `/api/users/me`, which is where user-service reconciles memberships
  through organization-service and returns roles, effective permissions and organizations.
- **The organization switcher** sets `X-Organization-TIN`. Switching needs no new token.
  Type `444444444` into the box and every project call answers 403.
- **Every button stays enabled for everyone**, on purpose. Sign in as `bank_user` and press
  Create project to watch a real 403 arrive. Hiding a button is a convenience; the server check
  is the control, because a hidden button is one curl command away from being pressed.
- **Without organization** and **Without token** buttons produce 400 and 401 deliberately, so
  the three failure modes are visible side by side.

The browser never sees the OneID client secret, the OneID access token, or the PIN. It holds a
Keycloak token and nothing else.

### Which gateway, and why

Spring Cloud ships a reactive gateway and a servlet one. This project uses
`spring-cloud-starter-gateway-server-webmvc`, the servlet flavour, so the whole platform keeps
**one** security model: reactive Spring Security uses `SecurityWebFilterChain` and cannot reuse
the servlet `SecurityFilterChain` in `platform-security`. The reactive gateway performs better
under very high concurrency, and that is worth less here than not maintaining two security
stacks.

Routes live under `spring.cloud.gateway.server.webmvc.routes`. That prefix changed in Spring
Cloud 2025.0; the older `spring.cloud.gateway.mvc.routes` still binds, which means a stale
example from a blog post fails silently rather than loudly.

---

## Verifying phase 9

OneID is brokered into Keycloak by a provider jar compiled against Keycloak's own SPI and baked
into the Keycloak image. No microservice changed in this phase, which is the point: services
trust Keycloak, and where Keycloak gets its identities from is invisible to them.

### Building it

```bash
mvn -pl oneid-identity-provider -am -DskipTests package
```

```bash
cp oneid-identity-provider/target/oneid-identity-provider-1.0.0-SNAPSHOT.jar keycloak/target-provider/
```

```bash
docker compose build keycloak && docker compose up -d
```

Keycloak scans providers at **build** time, not at startup, so the jar must be present when
`kc.sh build` runs inside `keycloak/Dockerfile`. Dropping a jar into a running container does
nothing. That is why the Keycloak image is now a build artefact of this repository rather than
the stock image.

### Proof it loaded

```bash
docker compose logs keycloak | grep KC-SERVICES0047
```

```
KC-SERVICES0047: oneid (uz.platform.keycloak.oneid.OneIdIdentityProviderFactory)
is implementing the internal SPI social. This SPI is internal and may change without notice
```

That warning is both the confirmation and the honest cost of this design. The provider compiles
against Keycloak's **internal** API, so a major Keycloak upgrade is a task with a recompile and a
test pass, not a version bump. `keycloak.version` in the aggregator POM is the one place that
decides which Keycloak the jar is built for.

### Proof it speaks OneID, not OAuth2

The Keycloak sign-in page now carries a **OneID** button. Following it produces the
authorization request, and this is where the difference from standard OAuth2 is visible:

```
target        : http://mock-oneid:8080/sso/oauth/Authorization.do
response_type : one_code          <- not "code"
client_id     : platform
redirect_uri  : http://localhost:8190/realms/platform/broker/oneid/endpoint
scope         : platform          <- an administrator-issued client name, not a permission list
state         : generated by Keycloak, verified by Keycloak on the way back
```

### What the provider overrides, and why only that

OneID borrows OAuth2's shape and then renames the constants in places Keycloak does not expose
as settings. So exactly three methods are overridden, and everything else is inherited:

| Departure from OAuth2 | Overridden in |
|---|---|
| `response_type=one_code` | `createAuthorizationUrl` |
| `grant_type=one_authorization_code` | `OneIdEndpoint.generateTokenRequest` |
| user data via `grant_type=one_access_token_identify`, secret in the form body | `doGetFederatedIdentity` |

Inherited unchanged: `state` generation and verification, the callback endpoint, session
creation, federated identity storage, first-login detection, account linking and token
issuance — all the parts that are easy to get subtly wrong.

None of these signatures were guessed. They were read from the Keycloak 26.1.5 SPI jars with
`javap`, which is how two surprises surfaced before they became runtime failures: the token
request lives on the inner `Endpoint` class rather than the provider, and a social factory's
type bound requires the provider to implement `SocialIdentityProvider` as well.

### How OneID data is mapped

| OneID field | Becomes | Why |
|---|---|---|
| `pin` | broker user id, and attribute `oneid_pin` | the only immutable identifier; never in a token |
| `user_id` | Keycloak username, so `preferred_username` | a login, not sensitive, fine in logs |
| `first_name`, `sur_name` | first and last name | refreshed on each login |
| `valid` | attribute `identity_verified` | assurance level, not token validity |
| `user_type`, `auth_method` | attributes | assurance and context |
| `legal_info[].tin` | multi-valued attribute `org_tins` | seeds membership; never the decision |
| `sess_id` | session note `oneid_sess_id` | per-session, for logout correlation only (actually stored only from phase 11) |
| `pkcs_legal_tin` | attribute, when present | an organization asserted by e-signature |

A response with `ret_cd` other than `"0"` fails the login with a generic message and logs the
code. A response with no `pin` also fails, because there is nothing stable to link on and an
account keyed on anything mutable would silently duplicate the person on their next login. An
account with `valid: false` **logs in**: the platform refuses high-value operations rather than
refusing the person.

### One local-development concession

`realm-export.json` now sets `"sslRequired": "none"`. Keycloak validates identity-provider URLs
at import time and refuses a plain-HTTP authorization URL when the realm requires SSL, and the
local mock runs on HTTP. **Production must use `"external"` or `"all"`**, with OneID on HTTPS,
which it is. Note also that the OneID specification forbids `localhost` in `redirect_uri`, so a
real integration needs a resolvable HTTPS hostname registered with the operator.

### What phase 9 does not prove yet

Only the first departure is observable without a OneID server to talk to. The token exchange,
the identify call, and whether the attributes set on the brokered identity actually persist
onto the Keycloak user all need `mock-oneid`, which is phase 10. Until then the login button
leads to a host that does not exist.

Phase 10 answers all three, and changed two things shown above: the authorization URL is now
`http://localhost:8191/...`, split from the back-channel URLs, and the attributes did **not**
persist on their own.

---

## Verifying phase 10

`mock-oneid` is a small Spring Boot service that speaks the OneID wire protocol exactly as the
technical instruction documents it: one URL, four operations chosen by `response_type` or
`grant_type`, opaque base64 tokens, and `ret_cd` in every result. The Keycloak provider is the
same code in development and production; only its three URLs change. That is the point of a
mock *service* rather than a mock *class*: the redirect, the form posts, the JSON parsing and the
failure handling exercised locally are the ones that will run against `sso.egov.uz`.

### Running it

```bash
mvn -pl mock-oneid -am -DskipTests package
```

```bash
java -jar mock-oneid/target/mock-oneid-1.0.0-SNAPSHOT.jar
```

Keycloak must already include the provider jar from phase 9. Then press **Login** in the Vue app
and choose **OneID** on the Keycloak sign-in page.

### Front channel and back channel

The three OneID URLs are separate settings for a reason that only shows up locally:

| Setting | Called by | Local value | Production |
|---|---|---|---|
| `ONEID_AUTHORIZATION_URL` | the **browser**, redirected by Keycloak | `http://localhost:8191/...` | `https://sso.egov.uz/sso/oauth/Authorization.do` |
| `ONEID_TOKEN_URL` | **Keycloak**, from inside its container | `http://host.docker.internal:8191/...` | same URL |
| `ONEID_IDENTIFY_URL` | **Keycloak**, from inside its container | `http://host.docker.internal:8191/...` | same URL |

`localhost` inside the Keycloak container means the container itself, so the back channel needs
a different name for the same mock. In production one public HTTPS host is reachable from both
sides and all three values are identical.

### Fixtures

Every PIN and person is invented; every shape is one the specification describes. The logins
deliberately differ from the password-based development users, so a OneID login never collides
with an existing Keycloak username and triggers account linking.

| Login | `user_type` | `valid` | Legal entities | Demonstrates |
|---|---|---|---|---|
| `akarimov` | I | true | 111111111, 222222222 | organization switching |
| `myusupova` | I | true | none | no organization context |
| `bbankov` | L | true | 333333333 via `LEPKCSMETHOD` | organization asserted by e-signature |
| `ntasdiqlanmagan` | I | **false** | none | unconfirmed account still logs in |
| `broken` | — | — | — | `ret_cd "1"`, login must fail |
| `nopin` | I | true | none | no PIN, login must fail |

### One full OneID login

```
200  keycloak  /protocol/openid-connect/auth        sign-in page, OneID button
303  keycloak  /broker/oneid/login                  -> mock, response_type=one_code
200  mock      /sso/oauth/Authorization.do          the person chooses an identity
302  mock      /sso/oauth/select                    -> keycloak with code and state
302  keycloak  /broker/oneid/endpoint               back channel: one_authorization_code, then one_access_token_identify
302  keycloak  /login-actions/first-broker-login    user created, mapper runs   (first login only)
302  keycloak  /broker/after-first-broker-login                                  (first login only)
 ->  app       http://localhost:5174/?code=...      exchanged with PKCE for a Keycloak token
```

A returning person skips both first-login hops: Keycloak finds the federated identity by PIN and
goes straight back to the application.

| Login | Token realm roles | `org_tins` | `identity_verified` | Result |
|---|---|---|---|---|
| `akarimov` | JISMONIY_SHAXS, YURIDIK_SHAXS | 111111111, 222222222 | true | logged in |
| `bbankov` | JISMONIY_SHAXS, YURIDIK_SHAXS | 333333333 | true | logged in, `pkcs_legal_tin` stored |
| `myusupova` | JISMONIY_SHAXS | — | true | logged in |
| `ntasdiqlanmagan` | JISMONIY_SHAXS | — | **false** | logged in |
| `broken` | — | — | — | 502, generic message |
| `nopin` | — | — | — | 502, generic message |

### What the end-to-end run proved

- **The PIN never leaves Keycloak.** It is the federated identity's broker user id and a stored
  attribute, it appears in no token, no service schema has a column for it, and neither the mock's
  nor Keycloak's log contains a single fixture PIN, token or client secret.
- **Memberships reconcile for a real OneID user.** Before session bootstrap
  `/api/organizations/mine` returned nothing; after `/api/users/me` it returned both organizations
  with source `ONEID`. `X-Organization-TIN` then answered 200, 200 and 403 for a TIN the person
  does not belong to.
- **Failures stay generic.** A `ret_cd` of `"1"` and a missing PIN both end on Keycloak's
  "Unexpected error when authenticating with identity provider"; the specific reason is only in
  the server log.

### Three things this phase found

**Keycloak 26 required an email OneID does not have.** The default user profile marks `email`
required for users. OneID never sends one, so first login stopped on a review-profile form even
with `updateProfileFirstLoginMode` off, because an invalid profile forces the form. The realm now
carries a user profile with `email` optional, embedded in `realm-export.json` under
`components` → `org.keycloak.userprofile.UserProfileProvider`. Inventing an address such as
`akarimov@oneid.local` would have worked and planted false data in every account.

**Attributes would have been dropped silently.** That same profile declares only four attributes
and discards the rest, so `org_tins` and `identity_verified` would have vanished between OneID and
the token with no error anywhere. The tempting fix, switching the unmanaged-attribute policy to
`ENABLED`, would let a person edit their own `identity_verified` in the account console.
`OneIdAttributeRoleMapper` writes them onto the stored user from the broker instead, where no
person can reach, and refreshes them on every login with `FORCE` sync. It also grants the baseline
roles, and deliberately never revokes one.

**The full name was split the wrong way round.** Stored users first came out as first name
"Karimov", last name "Ali Valiyevich". Keycloak's `BrokeredIdentityContext.setName` splits a full
name at the first space, and OneID's `full_name` is "Surname Given Patronymic". The provider no
longer calls it and uses OneID's separate name fields. No compiler or unit test would have caught
this; one end-to-end login did.

One build fix came out of this phase too. `java -jar` on the mock failed with
`no main manifest attribute`: without `spring-boot-starter-parent`, declaring
`spring-boot-maven-plugin` does not bind `repackage`, so no module produced an executable jar.
`mvn spring-boot:run` had hidden it since phase 3. The aggregator POM now binds it for every
module.

### Where the specification is silent

The mock makes a plain, documented choice in each case rather than inventing detail. Each is also
a question for the operator:

- **Error shape.** A refused token exchange answers HTTP 400 with `{"ret_cd":"1","error":"invalid_grant"}`;
  a refused identify answers HTTP 200 with `{"ret_cd":"1"}`, since `ret_cd` is what the
  specification defines as the result.
- **`valid` is a string.** Sent as `"true"`, as in the specification's sample, not a JSON boolean.
- **`refresh_token` is returned and never accepted.** How OneID expects it to be redeemed is still
  undocumented.
- **`localhost` redirect URIs are allowed.** The specification forbids them for real OneID; the
  first real integration needs a resolvable HTTPS hostname registered with the operator.

---

## Verifying phase 11

Logout ends several different things, and they do not all end at the same moment. Treating
them as one is how "we log people out" quietly becomes untrue.

| What | Ends | Proof below |
|---|---|---|
| Keycloak session | at once, on the server | admin API shows 0 sessions, the stored row is gone |
| Keycloak refresh token | at once | `400 invalid_grant (Session not active)` |
| Tokens held by the browser | at once, in every tab of the app | cleared before the redirect, broadcast to other tabs |
| Access token already issued | at its `exp`, plus clock skew | still accepted by the gateway after logout, then 401 |
| OneID session at sso.egov.uz | only with `ONEID_CALL_LOGOUT=true` | `one_log_out` sent once, token refused afterwards |

### The flow

```
Vue      Logout
 │         build the end-session URL: client_id, post_logout_redirect_uri, id_token_hint
 │         tell other tabs (BroadcastChannel "platform-auth"), forget this tab's tokens
 ▼
Keycloak /realms/platform/protocol/openid-connect/logout
 │         delete the user session and, with it, its refresh token
 │         call the OneID provider's keycloakInitiatedBrowserLogout
 │              └── one_log_out to OneID, only if ONEID_CALL_LOGOUT is on
 ▼
302 -> http://localhost:5174/     the app loads signed out
```

### What changed in the frontend

- **The logout URL is built before anything is cleared.** It carries the ID token as
  `id_token_hint`. With the hint and a registered post-logout redirect URI, Keycloak ends the
  session without its "Do you want to log out?" page.
- **Tokens are forgotten before the redirect, not by it.** And not only in this tab: keycloak-js
  keeps tokens in memory per tab and `checkLoginIframe` is off, so a second tab would otherwise
  keep showing a signed-in page. A `BroadcastChannel` message clears the others.
- **A refused token refresh no longer bounces the person to Keycloak.** It used to call `login()`
  mid-click. Now the tab shows "Your session has ended", which is what actually happened.
- **The browser still cannot end the OneID session, by design.** It has no OneID token to do it
  with. That is Keycloak's job.

To see it in the UI: sign in through OneID in two tabs, press **Logout** in one. The other tab
switches to signed out without a reload. **Login** then shows the Keycloak sign-in page again.

### Proof: default settings

`logout-check.mjs off` signs in as `akarimov` through the mock, logs out exactly as keycloak-js
builds the URL, then probes every place the session could survive:

```
--- signed in as akarimov through OneID ---
  access token lifetime (exp - iat)             : 300s
  Keycloak sessions for this user (admin API)   : 1
  note keys on the stored session               : AUTH_TIME, identity_provider, identity_provider_identity, KC_DEVICE_NOTE, oneid_sess_id
  OneID access token held by Keycloak           : no
  gateway GET /api/users/me                     : 200
--- logout ---
  end-session response                          : 302 -> http://localhost:5174/
--- after logout ---
  Keycloak sessions for this user (admin API)   : 0
  stored session rows for that sid              : 0
  refresh_token grant                           : 400 invalid_grant (Session not active)
  Keycloak userinfo with the old access token   : 401
  Keycloak introspection of the old access token: active=false
  gateway with the old access token             : 200  (token exp in 293s)
  same logout URL a second time                 : 302 -> http://localhost:5174/
  same browser opens the app login again        : 200 Keycloak sign-in page: no session left to reuse
```

A second logout with the same URL still lands back in the app rather than on an error page, so a
double click or a stale tab cannot strand anyone.

### Two things this phase found

**The OneID access token was stored in every Keycloak session.** Phase 9 described it as used
to fetch the identity and then discarded. Reading `keycloak.offline_user_session` showed a
`FEDERATED_ACCESS_TOKEN` note on 2 of the 5 stored sessions. Keycloak's inherited
`AbstractOAuth2IdentityProvider.authenticationFinished` copies the identity provider's access
token onto every user session, and Keycloak 26 persists user sessions in its database. So a
long-lived bearer credential for the national identity system was sitting in plain text in the
`keycloak` schema for no purpose. The provider now overrides `authenticationFinished` and keeps
the token only when `one_log_out` will need it. The key list above is the proof.

**`oneid_sess_id` was never stored at all.** The provider set it with
`BrokeredIdentityContext.setSessionNote`, which, called before the identity has an
authentication session, parks the note in a map that only Keycloak's token exchange ever copies
onto a session. In a browser login it goes nowhere, silently: 0 of the 5 sessions had it. It is
now written in `authenticationFinished` too.

Nothing failed in either case. Both were found by reading what Keycloak had actually stored,
which is the only way either could have been found.

### Proof: `ONEID_CALL_LOGOUT` on

`logout-check.mjs on` switches the setting on for one run and back off afterwards:

```
  OneID access token held by Keycloak           : YES (value not printed)
  mock identify with that token                 : ret_cd 0
--- logout ---
  end-session response                          : 302 -> http://localhost:5174/
  mock identify with the OneID token            : ret_cd 1
--- administrator ends a second session (back-channel path) ---
  admin API DELETE /sessions/{sid}              : 204
  mock identify with that OneID token           : ret_cd 1
mock-oneid : Log out: token revoked      (exactly one per logout, two in total)
keycloak   : OneID one_log_out for sess_id=97a47914-...: ret_cd=0
```

- **Both logout paths call OneID.** A person pressing Logout goes through
  `keycloakInitiatedBrowserLogout`; an administrator ending a session goes through
  `backchannelLogout`.
- **At most one call per session.** The token note is removed before the call, so a logout
  cannot send it twice and a failure is not retried against a service limited to 300 requests a
  minute.
- **OneID being down never blocks our logout.** The call's failure is logged by exception type
  and the Keycloak session ends regardless.
- **Nothing sensitive in the logs.** A scan of both logs found no OneID token and no client secret.
  `sess_id` is logged for correlation; it identifies a session, not a person, and cannot be used to
  act as anyone.
- **Idle expiry does not call OneID.** Keycloak has no hook for a session that simply times out.

**Turning it on.** `ONEID_CALL_LOGOUT` is read at realm import only. For an existing realm, enable
**Also end the OneID session on logout** on the OneID identity provider in the admin console.
Sessions that started before hold no token, so their logout sends nothing.

**What cannot be seen locally.** The mock has no SSO session of its own and shows its picker on
every login, so the "silently signed straight back in" effect of leaving the setting off only
appears against real OneID. What is verifiable here is that the call is made and the token stops
working. Whether `one_log_out` ends the whole session at sso.egov.uz or only revokes that one
token is not documented. That is a question for the operator, not something to assume.

### How long a copied access token outlives logout

`logout-check.mjs expiry` shortens `platform-web` access tokens to 60 seconds for one run, lets
the ID token expire as it would in a tab left idle, logs out, then keeps probing:

```
  access token lifetime (exp - iat)             : 60s
  ID token expired at logout                    : yes, 2s ago
  end-session response                          : 302 -> http://localhost:5174/
  Keycloak introspection of the old access token: active=false
  gateway with the old access token             : 200  (token exp in -2s)
  gateway with the old access token             : 401  (61s past exp, 59s after logout)
  platform-web access.token.lifespan restored   : (unset)
```

- **An expired `id_token_hint` is still accepted.** Logging out of a tab left idle past its token
  lifetime works, with no confirmation page, so the frontend needs no refresh just to log out.
- **Keycloak knows at once that the token is dead; the services do not.** They never ask Keycloak.
  They check signature, issuer, audience and expiry offline, which is the point of them.
- **Spring accepts a token for 60 seconds past `exp`.** That is its default clock skew. With this
  realm's 300-second tokens, a token copied just before logout keeps working for up to 6 minutes.

**Why that window is accepted, for now.** It is the price of every service validating tokens
independently, which keeps Keycloak off the path of every request. Closing it takes one of:

| Option | Cost |
|---|---|
| Shorter access tokens (`accessTokenLifespan`) | more refreshes; the cheapest lever, and the first to pull |
| The gateway introspects every token | Keycloak on every request's path: added latency, and a Keycloak outage becomes a platform outage; a call made straight to a service still skips the check |
| OIDC back-channel logout into a deny-list of `sid` values | the list must be shared by every instance of every service, so Redis or a table: infrastructure this project avoids without a direct reason |

None is implemented. After logout the browser holds no token, the session cannot mint new ones,
and what remains is bounded and measured. A requirement that a logged-out token must stop working
immediately would be the direct reason for the second or third option.

### Scenario B: logging out at OneID

Not synchronised, deliberately. OneID documents no way to tell a relying party that a person
logged out there, and none is invented. The Keycloak session carries on until it is logged out
here or expires: 30 minutes idle, 10 hours at most.

---

## Verifying phase 12

Moved to [testing](testing.md), where the test suites are documented.

---

## Phase 13: documentation, and the whole stack in Docker Compose

The documentation now lives in `docs/`, and the README is an entry point. Writing it meant checking
every claim against the running system, which is how both findings below surfaced.

### The whole application in Compose

`docker compose up -d --build` now starts all eight containers. The one real problem was the one
the OneID URLs had already shown: **a server has two names.** A browser reaches Keycloak as
`localhost:8190`, and every token says so in `iss`. Inside a container, `localhost` is the container.

- **Resource servers** get `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` pointing at
  `keycloak:8080`. With a JWK set URI Spring skips issuer discovery and still validates `iss` against
  `issuer-uri`, which stays the public URL.
- **The OAuth2 clients** in user-service and cadastral-service use `token-uri` instead of
  `issuer-uri`. Client credentials need nothing else, and `issuer-uri` made Spring call Keycloak's
  discovery document at startup, so a service could not even start while Keycloak was down.
- **The Keycloak image** is built with the repository root as its context and copies the provider
  jar straight from `oneid-identity-provider/target`. The root `.dockerignore` admits that one file,
  which also keeps `.env` out of every image.

The e2e suite passed against the containers unchanged.

### Finding 1: a payment could reference another organization's project

Found by calling the API the way an attacker would while writing the data-scoping section.
`bank_user` is a real member of 333333333 and holds `PAYMENT_CREATE`:

```
201  POST /api/projects   ali, X-Organization-TIN 111111111
201  POST /api/payments   bank_user, X-Organization-TIN 333333333, projectId = ali's project
```

Every check the platform had passed: valid token, genuine membership, the right permission. What
nobody checked was that the project named **in the request body** belonged to the organization named
**in the header**. `PaymentController` now looks the project up within the verified organization and
answers 404, the same answer updating a foreign project already gave:

```
404  POST /api/payments   bank_user, X-Organization-TIN 333333333, projectId = ali's project
```

The lesson generalises: a membership check proves who you act for, not that every id you send belongs
to them. Any request that names a record by id needs its own ownership check. A new end-to-end test
pins it.

### Finding 2: committed fallback secrets

The specification says never to hardcode client secrets, Keycloak admin credentials or OneID
credentials. The services' `application.yml` files and `docker-compose.yml` carried fallbacks such as
`${USER_SERVICE_CLIENT_SECRET:user-service-secret}`, so a missing secret silently became a value
committed to git. The fallbacks are gone: compose uses `${VAR:?set VAR in .env}`, and the services
and mock-oneid refuse to start without them. The development database role passwords are still
literals in `postgres/init/01-schemas.sql`, and are listed as a limitation.

### Verifying the frontend carries no secret

The built frontend image was unpacked and searched for the values of the three secrets from `.env`
and for OneID's back-channel grant names. None occurs. The first attempt at this check reported 0 for
everything, including strings the bundle certainly contains: on Windows, `grep`'s `C:\...` paths
broke the field split that summed the counts. The repeated check includes **control strings the
bundle does contain**, the Keycloak URL and the `X-Organization-TIN` header name, and finds them. A
search that finds nothing proves nothing until it is shown to find something.

### API examples are captured, not written

Every example in [API examples](api-examples.md) is a real request against the Compose stack, with
tokens redacted: 40 calls, including the refusals.
