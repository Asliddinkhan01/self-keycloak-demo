# Testing

The 26 test scenarios from the specification, the tests that prove each one, and how to run them.


All 26 scenarios are automated. They sit in four places, each chosen by what it can reach:

| Where | Needs | Proves |
|---|---|---|
| `platform-security`, `oneid-identity-provider` unit and slice tests | nothing | the security logic, fast, with its edge cases |
| `cadastral-service` `PermissionRefreshTest` | Docker | permissions read from the service's own Flyway migrations |
| `frontend/src/keycloak.test.js` | Node | the browser forgets its session |
| `e2e-tests` | the running platform | the real Keycloak, the OneID provider, the realm, the services wired together |

## Running them

The fast tests, with the end-to-end module compiled but skipped:

```bash
mvn test
```

The frontend:

```bash
npm --prefix frontend test
```

The end-to-end scenarios, with Postgres, Keycloak, mock-oneid and the four Spring applications
running as in the earlier phases:

```bash
mvn -pl e2e-tests -Pe2e test
```

The one slow check, about two and a half minutes, measuring exactly when a copied access token stops
working after logout:

```bash
mvn -pl e2e-tests -Pe2e test -Dtest=LogoutE2ETest -De2e.slow=true
```

If part of the platform is down, the suite stops once with the list of what it could not reach,
rather than failing every test with connection errors.

## Scenario map

| # | Scenario | End to end | Also proven in |
|---|---|---|---|
| 1 | User logs in with OneID | `AuthenticationE2ETest` | |
| 2 | Keycloak creates/fetches user | `AuthenticationE2ETest` | `OneIdAttributeRoleMapperTest` |
| 3 | Keycloak issues JWT | `AuthenticationE2ETest` | |
| 4 | Vue calls backend | `AuthenticationE2ETest` | |
| 5 | Backend accepts JWT | `AuthenticationE2ETest` | `ResourceServerSecurityTest` |
| 6 | QURUVCHI can access project endpoints | `RolesE2ETest` | |
| 7 | BANK cannot access QURUVCHI-only endpoint | `RolesE2ETest` | `ResourceServerSecurityTest` |
| 8 | ADMIN can access admin endpoints | `RolesE2ETest` | |
| 9 | Multiple roles work together | `RolesE2ETest` | `KeycloakAuthoritiesConverterTest`, `PermissionRefreshTest` |
| 10 | Role with PROJECT_CREATE can create | `PermissionsE2ETest` | `ResourceServerSecurityTest`, `PermissionRefreshTest` |
| 11 | Role without PROJECT_CREATE receives 403 | `PermissionsE2ETest` | `ResourceServerSecurityTest`, `PermissionRefreshTest` |
| 12 | Permission changes reflected after refresh | `PermissionsE2ETest` (the live half) | **`PermissionRefreshTest`**, `PermissionCatalogTest` |
| 13 | User belongs to Company A and Company B | `OrganizationContextE2ETest` | |
| 14 | User can operate using Company A | `OrganizationContextE2ETest` | `OrganizationContextFilterTest`, `ResourceServerSecurityTest` |
| 15 | User can switch to Company B | `OrganizationContextE2ETest` | `OrganizationContextFilterTest`, `ResourceServerSecurityTest` |
| 16 | User cannot use Company C, not even by a record id | `OrganizationContextE2ETest` | `OrganizationContextFilterTest`, `ResourceServerSecurityTest` |
| 17 | Fake X-Organization-TIN returns 403 | `OrganizationContextE2ETest` | `OrganizationContextFilterTest` |
| 18 | Cadastral Service obtains client_credentials token | `ServiceToServiceE2ETest` | |
| 19 | Organization Service accepts it | `ServiceToServiceE2ETest` | |
| 20 | Unauthorized service is rejected | `ServiceToServiceE2ETest` | `ResourceServerSecurityTest` |
| 21 | Cadastral Service cannot perform unrelated privileged operations | `ServiceToServiceE2ETest` | `KeycloakAuthoritiesConverterTest` |
| 22 | Service account is NOT SUPER_ADMIN | `ServiceToServiceE2ETest` | |
| 23 | User logs out from our application | `LogoutE2ETest` | |
| 24 | Keycloak session is terminated | `LogoutE2ETest` | |
| 25 | Frontend clears authentication state | | **`keycloak.test.js`** |
| 26 | Protected API access no longer available | `LogoutE2ETest`, plus the slow check | |

Each test's display name starts with its scenario number, and surefire is configured to report
display names, so `target/surefire-reports` reads as this table rather than as method names.

## Result

| Suite | Tests | Result |
|---|---|---|
| `e2e-tests`, `-Pe2e`, against the running platform | 26 | passed |
| `e2e-tests`, slow check, `-De2e.slow=true` | 1 | passed, about 2 minutes |
| `platform-security` | 30 | passed |
| `oneid-identity-provider` | 23 | passed |
| `cadastral-service`, Testcontainers PostgreSQL 18 | 2 | passed |
| `frontend`, Vitest | 7 | passed |

89 tests in total. A plain `mvn verify` passes with the end-to-end module skipped, as on a fresh
clone. After the end-to-end run the database held no `e2e-` rows, and the realm settings the slow
check changes for its run were back to their defaults.

This phase found no new defect in the platform. The two found in phase 11, by reading stored
sessions, are now tests.

## How the tests are built, and why

**The end-to-end tests only use what a real client could.** Browser redirects, the gateway, the
services' own endpoints, and Keycloak's admin API to inspect what was stored. They never touch the
database to arrange a result. The only database access deletes the `e2e-` projects and payments
they created, so repeated runs do not fill the lists the Vue app shows.

**OneID logins are driven like a browser.** `OneIdBrowser` follows every redirect itself and keeps
Keycloak's cookies, so it ends up holding a real Keycloak SSO session, which is exactly what the
logout tests need to destroy. The one shortcut is the mock's identity picker, where a person would
click a name. Where the login mechanism is not what is under test (roles, organizations), the
development users sign in by password grant instead, which is quicker and avoids depending on the mock.

**"Backend accepts JWT" is tested by what it refuses.** Accepting a good token proves little. The
test also sends the same token with edited claims under its original signature, the same claims
with `alg: none`, a genuine Keycloak token from the master realm, and no token. It sends them to the
gateway and, separately, straight to user-service, because every service validates on its own. It
also verifies the RS256 signature against the realm's JWKS with nothing but the JDK, so the test
does not simply trust the same library the services use.

**Scenario 12 runs against a database, not the live service.** The running service refreshes
permissions every five minutes, and proving a change live would mean editing the shared development
database and waiting. `PermissionRefreshTest` instead starts a throwaway PostgreSQL, applies
cadastral-service's real Flyway migrations, and drives the production `JdbcRolePermissionSource`,
`PermissionCatalog` and authorities converter. It calls `refresh()` where the scheduler would. The
end-to-end half then shows the running services take permissions from their own tables, never from
the token.

**Scenario 25 is browser code, so it is tested as browser code.** Vitest runs `keycloak.js` with
keycloak-js replaced by a fake that keeps the same contract, and `BroadcastChannel` replaced by an
in-memory one with browser semantics: a message reaches the other tabs, never the sender. It checks
that the logout URL carries `id_token_hint`, that tokens are already gone when the browser leaves,
that other tabs are signed out, and that a refused refresh does not bounce the person to login.

**The phase 11 regressions are pinned.** `OneIdIdentityProviderTest` fails if the OneID access
token is ever kept on a session with OneID logout off, or if `oneid_sess_id` stops being written.

**One dependency had to be pinned.** Docker here only accepts API versions from 1.40 up. Spring
Boot 3.5.6 manages Testcontainers 1.21.3, which defaults to API 1.32 and is reported to be refused
with "client version 1.32 is too old". The aggregator imports the Testcontainers 1.21.4 BOM before
Boot's, and the POM says when to remove that.

### Since phase 13

The end-to-end suite now passes against both ways of running the platform: the services as separate
processes, as in phase 12, and the whole stack in Docker Compose.

Phase 13 added one end-to-end test, after writing the authorization documentation turned up a gap
the 26 scenarios had not covered. A BANK operator who is a genuine member of their own organization,
and holds `PAYMENT_CREATE`, could record a payment against **another organization's project** by
sending its id. Membership was checked; ownership of the referenced record was not. The new test in
`OrganizationContextE2ETest` fails without the fix and passes with it: the call now answers 404, and
nothing is recorded. See the [journal](implementation-journal.md), phase 13.
