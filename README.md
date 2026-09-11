# OneID Platform

A production-oriented learning project: **OneID → Keycloak → Spring Boot microservices → Vue 3**,
with role-based access control, database-backed CRUD permissions, organization (TIN) context,
and OAuth2 service-to-service authentication.

The governing rule of the whole design:

> **Microservices never talk to OneID.** Only Keycloak does, and only at login.
> Every service trusts exactly one token issuer: Keycloak.

Two design documents accompany this code:

- **Design review** — what the OneID specification actually says, why it is not OpenID Connect,
  and why the integration is a Keycloak provider rather than a separate service.
- **Build plan** — modules, ports, schemas, token shapes, and the thirteen phases.

---

## Status

| Phase | Scope | State |
|---|---|---|
| 1–2 | Structure, PostgreSQL, Keycloak, realm | **done** |
| 3 | Resource servers + Flyway | **done** |
| 4 | JWT role converter + caller type | **done** |
| 5 | Permissions | **done** |
| 6 | Organizations and TIN context | next |
| 7 | Service-to-service | |
| 8 | Gateway and Vue | |
| 9–10 | OneID provider SPI, mock OneID | |
| 11 | Logout | |
| 12–13 | Tests, documentation | |

Modules are added to `pom.xml` as each phase lands, so `mvn verify` passes on a fresh clone
at every point rather than failing on an empty directory.

---

## Prerequisites

| Tool | Version |
|---|---|
| Docker Desktop | any recent |
| JDK | 21 |
| Maven | 3.9+ (IntelliJ ships its own) |
| Node.js | 20+ (from phase 8) |

---

## Ports

| Component | Port |
|---|---|
| PostgreSQL | 5452 |
| Keycloak | 8190 |
| mock-oneid | 8191 |
| api-gateway | 8090 |
| user-service | 8091 |
| organization-service | 8092 |
| cadastral-service | 8093 |
| Vue frontend | 5174 |

Every port is deliberately off its default. 5432 and 8080 are the two most contested ports on a
developer machine, and a clash there is genuinely hard to diagnose: a service silently fetches
some other program's response instead of Keycloak's JWKS, then rejects every token with a bare
`401` and no error description. The Compose project name is pinned to `oneid-platform` in `docker-compose.yml`, so moving or
renaming the folder does not orphan the running containers and the data volume.

---

## Project structure

```
.
├── docker-compose.yml            postgres + keycloak
├── pom.xml                       aggregator, imports the Spring BOMs
├── postgres/init/01-schemas.sql  5 schemas, 5 logins, grants
├── keycloak/import/              realm: roles, clients, dev users
├── platform-security/            shared: JWT defaults, claims, roles, permissions
├── user-service/                 :8091  schema user_service
├── organization-service/         :8092  schema organization_service
├── cadastral-service/            :8093  schema cadastral_service
├── api-gateway/                  :8090  phase 8
├── oneid-identity-provider/      Keycloak SPI JAR, phase 9
├── mock-oneid/                   :8191  phase 10
├── frontend/                     :5174  phase 8
└── docs/
```

Only `platform-security` is shared, and it holds security decisions and nothing else: no
entities, no controllers, no business logic. That discipline is what keeps a shared library
from quietly becoming a distributed monolith.

---

## Running what exists today

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

## Accounts

Keycloak admin console, master realm: **admin / admin**.

The `platform` realm currently contains **development fixture users**, so that roles and
permissions can be tested before the OneID provider exists in phase 9. They are replaced by
real OneID identities once brokering is in place.

| Username | Password | Realm roles | Organizations |
|---|---|---|---|
| `ali` | `password` | `JISMONIY_SHAXS`, `YURIDIK_SHAXS`, `QURUVCHI` | 111111111, 222222222 |
| `malika` | `password` | `JISMONIY_SHAXS` | none |
| `bank_user` | `password` | `JISMONIY_SHAXS`, `BANK` | 333333333 |
| `dual` | `password` | `JISMONIY_SHAXS`, `QURUVCHI`, `BANK` | 111111111 |
| `admin_user` | `password` | `JISMONIY_SHAXS`, `ADMIN` | none |
| `super_admin` | `password` | `JISMONIY_SHAXS`, `SUPER_ADMIN` | none |
| `unverified` | `password` | `JISMONIY_SHAXS` | none, `identity_verified=false` |

`SUPER_ADMIN` is deliberately **not** a superset of `ADMIN`. It holds `PLATFORM_ADMIN`, which no
other role has, and lacks `USER_DELETE`, which `ADMIN` has. Endpoints in phase 5 prove both
directions, so that no role is silently a wildcard.

---

## Design decisions already baked in

**The PIN never leaves Keycloak.** The JShShIR is the federated-identity broker key and a
Keycloak user attribute. It is in no token and no service database, so no microservice can leak
what it never receives.

**`org_tins` carries taxpayer numbers only.** Not names, not flags, not the full `legal_info`
array. TINs are public company identifiers, the list is small, and it is used once per session
to reconcile membership. It is never the authorization decision — `user_organizations` is the
source of truth, which also lets an administrator grant membership OneID knows nothing about.

**organization-service is registered as a Keycloak client but has no flow enabled.** It never
asks for a token; the registration exists only so that it can own client roles and act as a
token audience. Contrast with `user-service` and `cadastral-service`, which are confidential
clients with service accounts because they make outbound calls.

**Client secrets come from the environment.** `realm-export.json` contains
`${USER_SERVICE_CLIENT_SECRET}` placeholders that Keycloak resolves at import, so no secret is
committed. Copy `.env.example` to `.env` and change them for anything beyond your laptop.

---

## Open question, carried in the code as a TODO

**How does OneID expect its `refresh_token` to be redeemed?** The documented token response
contains one, but no grant type is given for it. Nothing will be invented. Note that this is a
different object from the Keycloak refresh token, with a different issuer and lifecycle;
conflating the two is the mistake this note exists to prevent.

## Deliberately out of scope

- **OneID → us logout synchronisation.** No back-channel mechanism is documented.
- **us → OneID logout.** Our logout ends the Keycloak session only. The consequence is worth
  knowing before testing: the person stays signed in at sso.egov.uz, so clicking Login again
  returns them with no credential prompt. That looks like a broken logout and is not. The call
  sits behind `ONEID_CALL_LOGOUT`, off by default.
