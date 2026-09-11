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
| 4 | JWT role converter | next |
| 5 | Permissions | |
| 6 | Organizations and TIN context | |
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

### The gap phase 4 closes

That last response is worth reading carefully:

```json
"realmRolesFromToken": ["JISMONIY_SHAXS", "QURUVCHI", "YURIDIK_SHAXS"],
"springAuthorities":   ["SCOPE_email", "SCOPE_profile"]
```

The token plainly carries three roles, and Spring Security has none of them. This is the
single most common Keycloak-with-Spring failure, and it is not a bug: Spring's default
converter reads only the `scope` claim and knows nothing about Keycloak's `realm_access`. Every
`hasRole(...)` would be false right now, against a token that visibly says otherwise.

Phase 4 adds the converter that closes it, and this endpoint is where the change becomes
visible.

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
