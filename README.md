# Keycloak Microservices Demo

A deliberately small project whose only real subject is **Keycloak**: how to run it,
how to configure a realm, how a browser app gets a JWT from it, and how a Spring Boot
microservice validates that JWT and authorizes requests by role.

Everything else (no gateway, no service discovery, no message broker) has been left out on
purpose so that nothing distracts from the security flow. The one piece of real
infrastructure is PostgreSQL: Keycloak stores its realm there, and each service owns its
own schema. See *The database* below.

```
keycloak-microservices-demo/
├── docker-compose.yml          PostgreSQL + Keycloak
├── README.md                   this file
├── postgres/
│   └── init/01-schemas.sql     creates the 3 schemas and 3 logins, runs once
├── keycloak/
│   ├── import/
│   │   └── realm-export.json   realm "demo": roles, client, users - imported on startup
│   └── themes/demo/login/      custom login + registration theme
│       ├── theme.properties
│       └── resources/css/demo.css
├── user-service/               port 8081, owns schema user_service
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/userservice/
│       │   ├── UserServiceApplication.java
│       │   ├── config/SecurityConfig.java
│       │   ├── config/KeycloakRealmRoleConverter.java
│       │   ├── domain/AppUser.java              JPA entity
│       │   ├── repo/AppUserRepository.java
│       │   ├── service/UserProfileService.java  just-in-time provisioning
│       │   ├── dto/UserDto.java
│       │   ├── dto/MeDto.java
│       │   └── web/UserController.java
│       └── resources/
│           ├── application.yml
│           └── db/migration/V1__create_app_user.sql
├── product-service/            port 8082, owns schema product_service
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/productservice/
│       │   ├── ProductServiceApplication.java
│       │   ├── config/SecurityConfig.java
│       │   ├── config/KeycloakRealmRoleConverter.java
│       │   ├── domain/Product.java              JPA entity
│       │   ├── repo/ProductRepository.java
│       │   ├── service/ProductService.java
│       │   ├── dto/CreateProductRequest.java
│       │   ├── dto/ProductResponse.java
│       │   └── web/ProductController.java
│       └── resources/
│           ├── application.yml
│           └── db/migration/V1__create_product.sql
└── frontend/                   Vue 3 + Vite, port 5173
    ├── package.json
    ├── vite.config.js
    ├── index.html
    └── src/{main.js, App.vue, keycloak.js, api.js, style.css}
```

---

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Docker Desktop | any recent | running PostgreSQL and Keycloak |
| JDK | 21 | the two Spring Boot services |
| Maven | 3.9+ | building the services (IntelliJ ships its own, so this is optional) |
| Node.js | 20+ | the Vue frontend |

Check on Windows PowerShell:

```powershell
docker --version; java -version; mvn -v; node -v
```

If `java` is missing, install a JDK 21 build (for example Eclipse Temurin 21) and reopen
your terminal. IntelliJ can also download a JDK for you under **File > Project Structure > SDKs**.

---

## Ports

| Component | Port | URL |
|---|---|---|
| PostgreSQL | 5442 | `jdbc:postgresql://localhost:5442/appdb` |
| Keycloak | 8180 | http://localhost:8180 |
| user-service | 8081 | http://localhost:8081 |
| product-service | 8082 | http://localhost:8082 |
| Vue frontend | 5173 | http://localhost:5173 |

Both infrastructure ports are deliberately off their defaults. PostgreSQL is on 5442 rather
than 5432 and Keycloak on 8180 rather than 8080, because both defaults are usually already
taken on a developer machine. Inside the Docker network the containers still use 5432 and
8080; only the published host ports differ.

**Why Keycloak is on 8180 and not its default 8080.** Port 8080 is the single most
contested port on a developer machine: Tomcat, Jenkins, YouTrack, and every other Spring
app want it. When two things share it, the failure is nasty and hard to read, because your
service silently fetches the wrong server's response instead of Keycloak's JWKS and then
rejects every token with a bare 401. Moving Keycloak to 8180 removes the whole class of
problem. Inside the container Keycloak still listens on 8080; only the published host port
differs.

The Keycloak URL is hard-wired into four places, so if you change the port, change all four:

| File | What to change |
|---|---|
| `docker-compose.yml` | the published port and `KC_HOSTNAME` |
| `user-service/src/main/resources/application.yml` | `issuer-uri` |
| `product-service/src/main/resources/application.yml` | `issuer-uri` |
| `frontend/src/keycloak.js` | `url` |

The frontend's own port, 5173, is wired into `vite.config.js`, the `redirectUris` and
`webOrigins` of the `demo-frontend` client in `keycloak/import/realm-export.json`, and
`app.cors.allowed-origin` in both `application.yml` files.

---

## Accounts

Keycloak admin console, master realm: **admin / admin** at http://localhost:8180

Realm `demo`:

| Username | Password | Realm roles | Use it to see |
|---|---|---|---|
| `admin` | `password` | `USER`, `ADMIN` | everything works |
| `user` | `password` | `USER` | 403 on the admin endpoints |
| `nobody` | `password` | none | 403 even on the plain USER endpoints |

You can also create your own account: the realm has self-registration switched on, so the
Keycloak login page carries a **Register** link and the Vue app has a **Sign up** button.
A self-registered account automatically receives the `USER` role and nothing more. See
*Self-registration and default roles* below for how that is wired.

---

## Running it

### 1. Start PostgreSQL and Keycloak

```bash
docker compose up -d
```

One command brings up both. Compose waits for PostgreSQL to report healthy before it starts
Keycloak, because Keycloak now stores its realm in that database and cannot boot without it.

Watch it come up:

```bash
docker compose logs -f keycloak
```

You are looking for these two lines:

```
Realm 'demo' imported
Keycloak 26.1.5 on JVM ... started
```

Then open http://localhost:8180 and sign in as `admin` / `admin`.

### 2. Start user-service

New terminal:

```bash
cd user-service
mvn spring-boot:run
```

Windows PowerShell, if `mvn` is not on your PATH, open the project in IntelliJ and run
`UserServiceApplication` directly, or use IntelliJ's bundled Maven from the Maven tool window.

**Start Keycloak first, and watch out for this trap.** Spring Boot builds the JWT decoder
*lazily*: the bean it creates for `issuer-uri` is a `SupplierJwtDecoder` that does not
contact Keycloak until the first token arrives. So a service whose Keycloak is unreachable
**starts perfectly happily** and only fails later, on every request, like this:

```
HTTP/1.1 401
WWW-Authenticate: Bearer
```

Note what is missing: no `error="invalid_token"`, no `error_description`. That bare
challenge is the fingerprint of a decoder that could not initialise. Spring's
`JwtAuthenticationProvider` turns a decoder failure into an `AuthenticationServiceException`,
which is not an `OAuth2AuthenticationException`, so no error details reach the header. A
genuinely bad token looks quite different:

```
WWW-Authenticate: Bearer error="invalid_token", error_description="..."
```

Learn to tell those two apart and you will save yourself hours. See the troubleshooting
table for how to confirm it.

### 3. Start product-service

Another terminal:

```bash
cd product-service
mvn spring-boot:run
```

### 4. Start the frontend

Another terminal:

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173

### Stopping and resetting

```bash
docker compose down
```

keeps the PostgreSQL volume, so realms, users, products and profile rows all survive.

```bash
docker compose down -v
```

deletes the volume, which now wipes **everything**: the Keycloak realm, both service
schemas, and the Flyway history. The next `docker compose up -d` re-runs
`postgres/init/01-schemas.sql`, re-imports `realm-export.json`, and the services re-run
their migrations from scratch on next start.

You need `-v` in two situations: after editing `realm-export.json`, because **an existing
realm is never overwritten by the import** (the strategy is `IGNORE_EXISTING`), and after
editing `postgres/init/01-schemas.sql`, because those scripts only run when the data
directory is first created.

---

## The database

One PostgreSQL server, one database, three schemas:

```
appdb
 ├── keycloak          owned by role "keycloak"          87 tables, managed by Keycloak
 ├── user_service      owned by role "user_service"      app_user + flyway_schema_history
 └── product_service   owned by role "product_service"   product  + flyway_schema_history
```

**A note on the image tag.** `docker-compose.yml` uses `postgres:latest`. That is convenient
locally but it is a moving target: the day `latest` rolls to the next major version, your
existing volume becomes unreadable and the container refuses to start until you run
`docker compose down -v`. For anything shared with other people, pin a major version such
as `postgres:18` instead. The volume is mounted on `/var/lib/postgresql` rather than
`/var/lib/postgresql/data`, because PostgreSQL 18 images moved to major-version
subdirectories; mounting the parent works on both 17 and 18.

**Why schemas and not three databases.** In a microservice system each service must own its
tables: no service reads another's data directly, they talk over HTTP. There are three usual
ways to arrange that, from most isolated to least: a server per service, a database per
service, or a schema per service. This project uses the third, which keeps one connection
endpoint and one backup while still giving every service its own namespace and its own
credentials. It is a common real-world compromise, and it is by far the easiest to inspect
while learning, because a single `psql` session shows you all three side by side.

**The isolation is real, not a convention.** Each role owns exactly one schema and is never
granted `USAGE` on the others. Try it:

```bash
docker exec -e PGPASSWORD=product_service demo-postgres psql -U product_service -d appdb -c "SELECT * FROM user_service.app_user"
```

```
ERROR:  permission denied for schema user_service
```

The same command against `keycloak.user_entity` fails the same way. A service cannot reach
across the boundary even by accident, which is the property that makes schema-per-service
worth doing rather than just agreeing not to peek.

### Keycloak's own storage

Four environment variables in `docker-compose.yml` move Keycloak off its embedded H2 file:

```yaml
KC_DB: postgres
KC_DB_URL: jdbc:postgresql://postgres:5432/appdb
KC_DB_USERNAME: keycloak
KC_DB_PASSWORD: keycloak
KC_DB_SCHEMA: keycloak
```

Note the URL: `postgres:5432` is the *container* name and *internal* port on the Docker
network, not `localhost:5442`. The Spring services run on your host and therefore use the
published port; Keycloak runs inside the network and does not.

Keycloak builds and upgrades its own 87 tables using Liquibase at startup. You never write
a migration for it. Have a look:

```bash
docker exec demo-postgres psql -U postgres -d appdb -c "SELECT name FROM keycloak.realm"
docker exec demo-postgres psql -U postgres -d appdb -c "SELECT username FROM keycloak.user_entity"
```

That second query is worth running once. It shows you where your users *actually* live, and
that the password column holds no plaintext: credentials sit in a separate `credential`
table as salted hashes. Neither Spring service can read either table.

### The services' storage

Both services use Spring Data JPA over PostgreSQL, with **Flyway owning the schema**:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: user_service
  flyway:
    schemas: user_service
    default-schema: user_service
```

Three things are worth understanding there.

`ddl-auto: validate` means Hibernate is forbidden from touching the schema. Every table is
created by a numbered SQL file under `src/main/resources/db/migration/`, Flyway applies it
once and records it in `flyway_schema_history`, and Hibernate only *checks* at startup that
the entities and the real tables agree. If they drift, the service refuses to start instead
of silently corrupting data. The common alternative, `ddl-auto: update`, lets Hibernate
invent DDL at runtime and is a well-known way to lose data in production.

`default_schema` keeps the schema name out of the Java code. Neither `@Table(name = "product")`
nor the migration SQL mentions `product_service`; the one place it appears is configuration,
so the same code could run against a differently named schema in another environment.

Each service logs in as its own PostgreSQL role, so the isolation above applies to the
running application, not just to `psql`.

To change a table later you add `V2__whatever.sql`. You never edit a migration that has
already been applied, because Flyway stores a checksum and will refuse to start if one
changes underneath it.

### What lives where

This is the part most people get wrong, so it is worth stating plainly.

| Data | Home | Why |
|---|---|---|
| usernames, passwords, roles, sessions | Keycloak | it is the identity provider; that is its whole job |
| profile rows, products | the service schemas | application data Keycloak has no business storing |

Look at `user_service.app_user` and notice what is **not** there: no password, no password
hash, no roles. Duplicating roles into a service table is the classic mistake. It creates
two sources of truth that drift apart, and the copy is always the stale one. If you need to
know what a user may do, read it from their token.

The two halves are joined by one column:

```sql
keycloak_id VARCHAR(36) UNIQUE   -- the "sub" claim of the access token
```

`sub` is Keycloak's immutable user id. Usernames and e-mail addresses can be changed by an
administrator; `sub` cannot, which is why it and not the username is the foreign key to
identity.

### Just-in-time provisioning

Nothing creates a profile row when someone registers in Keycloak. Keycloak does not know
this service exists. Instead `UserProfileService.findOrCreateFrom(jwt)` creates or links the
row the first time that user calls `GET /api/users/me`, in three steps: match on `sub`, else
match a seeded row on username and attach the `sub`, else insert a new row.

You can watch it happen. Start clean, then look at the table:

```bash
docker exec demo-postgres psql -U postgres -d appdb -c "SELECT id, keycloak_id, username FROM user_service.app_user"
```

Every `keycloak_id` is null. Now log in as `user` in the Vue app and press **Get my profile**,
and run the query again: that one row has a `sub`, and the other two are still null. Sign up
a brand new account through the Register link and call the same endpoint, and a fourth row
appears that no migration ever inserted.

This is how most real systems bridge an external identity provider and local data.

### Looking around with psql

```bash
docker exec -it demo-postgres psql -U postgres -d appdb
```

Useful once you are in:

```
\dn                          list the schemas and their owners
\dt user_service.*           list one service's tables
SELECT * FROM product_service.product;
SELECT version, description, success FROM user_service.flyway_schema_history;
\q
```

To connect as a service role instead, which is how you see the permission boundaries:

```bash
docker exec -it -e PGPASSWORD=user_service demo-postgres psql -U user_service -d appdb
```

Its `search_path` is already set to its own schema, so `SELECT * FROM app_user;` works
unqualified and `SELECT * FROM product_service.product;` is refused.

---

## Customising the login page

The login page you see is **not** the stock Keycloak one. The realm uses a custom theme
called `demo`, which lives entirely in two files:

```
keycloak/themes/demo/login/theme.properties
keycloak/themes/demo/login/resources/css/demo.css
```

A Keycloak theme is only a folder with a fixed shape:

```
themes/<name>/<type>/theme.properties
themes/<name>/<type>/resources/...
```

where `<type>` is one of `login`, `account`, `admin`, `email` or `welcome`. This project
customises `login` only, so the account and admin consoles keep their normal appearance.

The whole of `theme.properties` is three lines:

```properties
parent=keycloak
import=common/keycloak
styles=css/login.css css/demo.css
```

`parent=keycloak` is what keeps this maintainable. Every FreeMarker template
(`login.ftl`, `register.ftl`, `error.ftl` and the rest) is inherited from the built-in
theme, so nothing is copied and nothing breaks when you upgrade Keycloak. Only the
stylesheet list is overridden. Note that `styles` is **replaced**, not merged, so the
parent's `css/login.css` has to be listed again or the page loses all its base styling.

Three wiring details make it work:

1. `docker-compose.yml` mounts `./keycloak/themes` to `/opt/keycloak/themes`. That folder
   is where Keycloak looks for custom themes; the built-in ones live inside a jar.
2. `realm-export.json` sets `"loginTheme": "demo"`. Without that line the realm keeps
   using the stock theme no matter what is on disk.
3. `start-dev` disables theme caching, so editing `demo.css` and reloading the browser is
   enough. No container restart, no rebuild.

The header text comes from the realm, not the CSS:

```json
"displayNameHtml": "<div class=\"kc-logo-text\"><span>Keycloak Demo</span><small>...</small></div>"
```

Two gotchas are worth knowing, because both bit this project during development. The
parent theme paints the Keycloak hexagon logo as a *background image* on `.kc-logo-text`,
and it hides the inner `<span>` with an ID-qualified rule. So `demo.css` has to switch the
background image off and beat the parent's specificity with
`#kc-header-wrapper .kc-logo-text span`. Whenever a rule of yours seems to be ignored,
that is almost always what is happening: open DevTools and look at which rule wins.

To restyle the page, edit the four colour variables at the top of `demo.css` and reload.
To go further than CSS, copy a single template out of the Keycloak themes jar into
`keycloak/themes/demo/login/` and edit it; the parent supplies everything you do not copy:

```bash
docker cp keycloak:/opt/keycloak/lib/lib/main/org.keycloak.keycloak-themes-26.1.5.jar .
```

---

## Self-registration and default roles

The realm allows users to create their own accounts:

```json
"registrationAllowed": true,
"resetPasswordAllowed": true,
"rememberMe": true
```

That single flag makes Keycloak render a **Register** link under the login form, backed by
its own `register.ftl` template. The Vue app's **Sign up** button just jumps straight to
it, via `keycloak.register()` in `frontend/src/keycloak.js`. That call issues the very same
authorization-code request as `login()`, with one extra parameter telling Keycloak to open
the registration page first. As with login, the form belongs to Keycloak; this project
never renders a password field.

**The interesting part is what role a new account gets.** Nothing in the registration form
mentions roles, so without extra configuration a new user would have no roles at all and
would be met by 403 everywhere, exactly like the `nobody` account. Keycloak solves this
with the realm's *default role*, a composite role that is granted to every new user
automatically. This realm defines it explicitly:

```json
"defaultRole": {
  "name": "default-roles-demo",
  "composite": true,
  "clientRole": false,
  "containerId": "demo"
},
"roles": { "realm": [
  { "name": "USER" },
  { "name": "ADMIN" },
  { "name": "default-roles-demo", "composite": true,
    "composites": { "realm": ["USER"] } }
]}
```

A composite role is a role that contains other roles. Because `default-roles-demo`
contains `USER`, and every new account gets `default-roles-demo`, every new account
effectively has `USER`. You can see it in a fresh account's token:

```json
"realm_access": {
  "roles": ["offline_access", "uma_authorization", "default-roles-demo", "USER"]
}
```

`USER` is there even though it was never assigned by hand, and `KeycloakRealmRoleConverter`
maps it to `ROLE_USER` like any other role. `ADMIN` is deliberately **not** in the
composite: nobody should be able to grant themselves administrative rights by filling in a
sign-up form. An operator assigns `ADMIN` in the admin console.

Note that the three seeded users (`admin`, `user`, `nobody`) list their roles explicitly in
the import instead of relying on the default, which keeps the teaching examples obvious and
keeps `nobody` genuinely role-less.

One trap if you edit this: a composite may only reference roles that already exist when the
import runs. Listing Keycloak's own built-ins such as `offline_access` or `uma_authorization`
inside `composites` makes the whole import fail with `Unable to find composite realm role`,
and the container exits. Reference only your own roles, and let Keycloak add its built-ins
afterwards, which it does automatically.

To turn self-registration off again, set `"registrationAllowed": false`, or untick
**User registration** under **Realm settings > Login** in the admin console.

---

## A. Architecture

```
                        ┌──────────────────────────────┐
                        │          Keycloak            │
                        │      localhost:8180          │
                        │                              │
                        │  Realm:   demo               │
                        │  Client:  demo-frontend      │
                        │  Roles:   USER, ADMIN        │
                        │  Users:   admin, user, nobody│
                        └───────┬──────────────┬───────┘
                                │              │
              1. login (browser redirect)      │  3. GET /certs (JWKS)
              2. access token back             │     once, then cached
                                │              │
                        ┌───────▼──────┐       │
                        │   Vue SPA    │       │
                        │ localhost:5173│      │
                        └───┬──────┬───┘       │
                            │      │           │
        Authorization: Bearer <JWT>│           │
                            │      │           │
              ┌─────────────▼──┐ ┌─▼───────────▼──────┐
              │  user-service  │ │  product-service   │
              │     :8081      │ │       :8082        │
              │ resource server│ │  resource server   │
              └───────┬────────┘ └─────────┬──────────┘
                      │                    │
                      │   JDBC :5442       │
                      ▼                    ▼
        ┌─────────────────────────────────────────────────┐
        │              PostgreSQL  appdb                  │
        │                                                 │
        │  schema keycloak        schema user_service     │
        │  (Keycloak's 87 tables) (app_user)              │
        │                         schema product_service  │
        │                         (product)               │
        └─────────────────────────────────────────────────┘
                      ▲
                      │ JDBC, inside the Docker network
                      └──────────── Keycloak
```

Four things are worth noticing in that picture:

1. **The Vue app talks to Keycloak, the services do not.** The services never see a
   password and never perform a login.
2. **The services contact Keycloak once**, on the first request, to fetch the public keys.
   After that every request is validated offline.
3. **Both services trust the same realm.** That is what makes a single sign-on across
   many microservices possible: one login, one token, many services.
4. **They share a database server but not a schema.** The arrows into PostgreSQL never
   cross: user-service cannot read product-service's tables, and neither can read
   Keycloak's. That is enforced by PostgreSQL privileges, not by politeness.

---

## B. Keycloak concepts, mapped onto this project

**Realm** - an isolated universe of users, roles, clients and settings. Two realms know
nothing about each other. This project has one realm called `demo`. The `master` realm,
which always exists, is only for administering Keycloak itself. In production you never put
application users in `master`.
*In this project:* `keycloak/import/realm-export.json`, field `"realm": "demo"`.

**Client** - an application that asks Keycloak for tokens. Not a user. A client is either
*public* (a browser app or mobile app, cannot keep a secret) or *confidential* (a backend,
has a client secret). This project has exactly one client, `demo-frontend`, and it is public.
*Why only one?* Because only one thing in this system ever asks for a token: the Vue app.
`user-service` and `product-service` only *consume* tokens. They validate signatures with
public keys, so they need no client, no secret, and no registration at all. This is the
single most common point of confusion when people come from the old Keycloak adapters,
which did make you register every backend.
You would add a client for a backend when that backend needs to call another service
*as itself*, with no user present. That is the client-credentials flow, and the client
would be confidential with `serviceAccountsEnabled`.

**User** - a person in the realm. `admin`, `user` and `nobody` here.
*In this project:* the `users` array of the realm export.

**Role** - a named permission. Keycloak has *realm roles* (global to the realm) and
*client roles* (scoped to one client). This project uses realm roles, `USER` and `ADMIN`,
because they are the simplest thing that works and they land in a predictable JWT claim.
*In this project:* the `roles.realm` array of the realm export.

**Group** - a folder of users that carries roles. Put a user in group `Administrators`
and they inherit its roles. Not used here, because with three users it would only add a
layer to look through. In a real system with hundreds of users, you assign roles to groups
and users to groups, never roles to users directly.

**OAuth2** - a framework for *delegated authorization*. It answers "may this application
act on this resource on the user's behalf?" It says nothing about who the user is.

**OpenID Connect (OIDC)** - a thin layer on top of OAuth2 that adds *authentication*:
who the user is. It adds the ID token, the `/userinfo` endpoint, and the discovery
document. Keycloak is an OIDC provider. Everything in this project is OIDC.

**Access token** - the token you send to an API. In Keycloak it is a JWT. It carries the
user's identity and roles and it is short-lived, 5 minutes in this realm.
*In this project:* the value in `Authorization: Bearer ...`.

**Refresh token** - a longer-lived token whose only purpose is to obtain a new access
token without asking the user to log in again. It goes to Keycloak, never to your API.
*In this project:* `keycloak.updateToken(30)` in `frontend/src/keycloak.js` uses it.

**ID token** - a JWT describing *who logged in*, meant for the client application, not
for APIs. The Vue app reads it to display the username. Never send an ID token to an API.
*In this project:* `keycloak.tokenParsed` is the access token; `keycloak.idTokenParsed`
is the ID token.

**JWT** - JSON Web Token. Three base64url parts separated by dots:
`header.payload.signature`. The payload is *not encrypted*, only signed. Anyone can read
it; nobody can change it without invalidating the signature. Paste one into https://jwt.io
and you will see every claim.

**Issuer (`iss`)** - the URL of the realm that minted the token,
`http://localhost:8180/realms/demo` here. A resource server rejects any token whose `iss`
does not match its configured `issuer-uri`. This is what stops a token from your test realm
being accepted by your production service.

**JWKS** - JSON Web Key Set, the list of *public* keys the realm signs with, published at
`http://localhost:8180/realms/demo/protocol/openid-connect/certs`. Open it in a browser.
Spring downloads this once, caches it, and uses it to verify signatures locally.

---

## C. The authentication flow, step by step

```
User clicks "Login" in the Vue app
   │
   ▼
Vue: keycloak.login()
   │   full-page redirect to
   │   http://localhost:8180/realms/demo/protocol/openid-connect/auth
   │     ?client_id=demo-frontend
   │     &redirect_uri=http://localhost:5173/
   │     &response_type=code
   │     &scope=openid
   │     &code_challenge=<sha256 of a random secret>   ← PKCE
   │     &code_challenge_method=S256
   ▼
Keycloak shows ITS OWN login page
   │   the Vue app never sees the password
   ▼
User types username + password
   │
   ▼
Keycloak creates an SSO session and redirects back to
   http://localhost:5173/?code=<authorization code>&state=...
   │   the code is single-use and short-lived
   ▼
Vue: keycloak.init() sees the code in the URL and POSTs it to
   http://localhost:8180/realms/demo/protocol/openid-connect/token
     grant_type=authorization_code
     code=<the code>
     client_id=demo-frontend
     code_verifier=<the original random secret>        ← PKCE proof
   │
   ▼
Keycloak returns JSON: { access_token, refresh_token, id_token, expires_in }
   │
   ▼
Vue stores them in memory and calls the API:
   fetch('http://localhost:8082/api/products', {
     headers: { Authorization: `Bearer ${keycloak.token}` }
   })
   │
   ▼
Spring Security's BearerTokenAuthenticationFilter extracts the token
   │
   ▼
NimbusJwtDecoder verifies signature (JWKS), issuer, expiry
   │
   ▼
KeycloakRealmRoleConverter turns realm_access.roles into ROLE_* authorities
   │
   ▼
AuthorizationFilter / @PreAuthorize checks the authorities
   │
   ▼
ProductController method runs, JSON comes back
```

**Why an authorization code and not the token directly?** Because the code travels in the
browser's address bar, where it lands in history and server logs. It is useless on its own:
exchanging it requires the PKCE `code_verifier`, which never left the Vue app's memory.
The old *implicit flow*, which did put tokens in the URL, is deprecated for this reason.

**Why is there no login form in the Vue code?** Deliberately. If your app rendered the
password field, your app would handle passwords, and the entire point of an identity
provider would be lost. The login page you see belongs to Keycloak, is themable in
Keycloak, and can gain two-factor authentication or a Google login button without a single
change to this project.

---

## D. How the backend validates a JWT

```
Incoming request
  Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6Ii4uLiJ9.eyJpc3MiOi...
        │
        ▼
BearerTokenAuthenticationFilter          strips "Bearer ", gets the raw token
        │
        ▼
issuer-uri from application.yml          http://localhost:8180/realms/demo
        │
        ▼
GET /realms/demo/.well-known/openid-configuration     ← once, at startup
        │  reads "jwks_uri" from the response
        ▼
GET /realms/demo/protocol/openid-connect/certs        ← once, then cached
        │  the realm's PUBLIC keys
        ▼
NimbusJwtDecoder
        ├─ match the token header's "kid" to a key in the JWKS
        ├─ verify the RS256 signature with that public key
        ├─ check "exp"  (not expired)
        ├─ check "nbf"  (not used too early)
        └─ check "iss" == issuer-uri
        │
        ▼
Jwt object with all claims
        │
        ▼
JwtAuthenticationConverter
        ├─ principal name  = claim "preferred_username"
        └─ authorities     = KeycloakRealmRoleConverter(jwt)
        │
        ▼
JwtAuthenticationToken placed in the SecurityContext
        │
        ▼
authorizeHttpRequests rules and @PreAuthorize expressions
```

**Why does the backend not call Keycloak on every request?**

Because a signature is self-verifying. Keycloak signs the token with its *private* key,
which only Keycloak has. The service holds the matching *public* key. Anyone with the
public key can prove the token was signed by Keycloak and has not been altered by a single
byte, without asking anyone. That is asymmetric cryptography, and it is what makes JWTs
scale: you can run fifty microservices and none of them adds load to Keycloak.

The price of that is **revocation**. A token stays valid until it expires, even if you
disable the user in Keycloak one second after it was issued. That is why access tokens are
short: 5 minutes here (`accessTokenLifespan` in the realm export). If you truly need
instant revocation you use *token introspection* instead, where the service asks Keycloak
about every token, and you accept the extra network call. This project uses the JWT route,
which is what almost all microservice systems do.

**What about the audience?** The realm export adds an `audience-demo-api` protocol mapper
so tokens carry `"aud": "demo-api"`. Spring's resource server does **not** validate the
audience by default. Adding that check is a one-bean change and is a good exercise:

```java
// optional hardening, not enabled in this project
@Bean
JwtDecoder jwtDecoder(OAuth2ResourceServerProperties props) {
    NimbusJwtDecoder decoder =
        JwtDecoders.fromIssuerLocation(props.getJwt().getIssuerUri());
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(props.getJwt().getIssuerUri()),
        new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains("demo-api"))));
    return decoder;
}
```

---

## E. Roles: from Keycloak to `hasRole("ADMIN")`

This is the part that trips almost everyone up, so follow it slowly.

```
   Keycloak: realm role ADMIN, assigned to user "admin"
        │
        ▼
   Access token payload contains:
        "realm_access": { "roles": ["ADMIN", "USER"] }
        │
        ▼
   KeycloakRealmRoleConverter reads realm_access.roles
   and prefixes each with ROLE_
        │
        ▼
   GrantedAuthority("ROLE_ADMIN"), GrantedAuthority("ROLE_USER")
        │
        ▼
   @PreAuthorize("hasRole('ADMIN')")  →  hasAuthority("ROLE_ADMIN")  →  true
```

**Why is a converter necessary at all?**

Spring Security's default is `JwtGrantedAuthoritiesConverter`, and it reads exactly one
claim: `scope` (or `scp`). It turns `"scope": "profile email"` into the authorities
`SCOPE_profile` and `SCOPE_email`. That is the OAuth2 standard, and `realm_access` is not
part of it: it is a Keycloak invention. Spring cannot guess that a nested object called
`realm_access` holds roles, so out of the box **your authority list contains no roles at all**
and every `hasRole(...)` returns false. That produces the classic symptom: the token clearly
shows `ADMIN` on jwt.io, and the API still answers 403.

**Why the `ROLE_` prefix?**

`hasRole("ADMIN")` is not a separate mechanism. Spring expands it to
`hasAuthority("ROLE_ADMIN")`. It adds the prefix when *checking*, never when *building*.
So the converter has to add it. Keycloak roles are named `USER` and `ADMIN` without a
prefix on purpose, precisely so the mapping stays one obvious line of code.

If you prefer not to write a converter, the equivalent can be expressed as a bean-less
SpEL check instead, but you would repeat it everywhere:

```java
@PreAuthorize("principal.claims['realm_access']['roles'].contains('ADMIN')")
```

The converter is better: one place, and `hasRole` keeps working everywhere.

**Client roles instead of realm roles.** If you assign client roles rather than realm
roles, they appear under a different claim and the converter has to read that instead:

```json
"resource_access": {
  "demo-frontend": { "roles": ["ADMIN"] }
}
```

**Try breaking it.** Comment out this one line in either `SecurityConfig`:

```java
converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
```

Restart, log in as `admin`, press *Create product*. You now get **403 Forbidden** with a
perfectly valid admin token. Put the line back and it works. Doing this once teaches the
concept better than any amount of reading.

---

## Endpoint reference

### product-service, http://localhost:8082

| Method | Path | Protection | Where it is enforced | Touches |
|---|---|---|---|---|
| GET | `/api/products/public` | none | `permitAll()` in `SecurityConfig` | counts rows |
| GET | `/api/products` | any valid token | `anyRequest().authenticated()` | reads `product` |
| POST | `/api/products` | role `ADMIN` | `@PreAuthorize("hasRole('ADMIN')")` | inserts into `product` |
| DELETE | `/api/products/{id}` | role `ADMIN` | `@PreAuthorize("hasRole('ADMIN')")` | deletes from `product` |

### user-service, http://localhost:8081

| Method | Path | Protection | Where it is enforced | Touches |
|---|---|---|---|---|
| GET | `/api/users/me` | role `USER` | `.hasRole("USER")` matcher | reads, links or inserts `app_user` |
| GET | `/api/users/me/claims` | role `USER` | `.hasRole("USER")` matcher | token only, no database |
| GET | `/api/users` | role `ADMIN` | `.hasRole("ADMIN")` matcher | reads `app_user` |

Note that `GET /api/users` no longer returns roles. It returns profile rows from
`user_service.app_user`, and roles are not stored there on purpose. To see a user's roles,
read them from their token via `/api/users/me` or `/api/users/me/claims`.

The two services use different styles on purpose so you can compare them. URL matchers are
easy to read as a whole-application policy. `@PreAuthorize` sits next to the code it
protects and cannot be forgotten when someone changes a `@RequestMapping` path.

---

## Test scenarios

### Getting a token from the command line

The `demo-frontend` client has *Direct access grants* enabled, so you can trade a username
and password for a token in one call. This is only for local testing; a browser app must
use the redirect flow shown above.

PowerShell:

```powershell
$token = (Invoke-RestMethod -Method Post -Uri "http://localhost:8180/realms/demo/protocol/openid-connect/token" -Body @{ client_id="demo-frontend"; username="admin"; password="password"; grant_type="password" }).access_token
$token
```

Git Bash or WSL:

```bash
TOKEN=$(curl -s -d "client_id=demo-frontend" -d "username=admin" -d "password=password" -d "grant_type=password" http://localhost:8180/realms/demo/protocol/openid-connect/token | jq -r .access_token)
```

Paste the value into https://jwt.io and look for `realm_access`.

### Scenario 1 - a plain USER

1. Open http://localhost:5173 and click **Login**.
2. Sign in as `user` / `password`.
3. The page shows the badge `USER` only.
4. **Get products** returns `200` with the seeded list.
5. **Create product (ADMIN)** returns `403 Forbidden`.
6. **List all users (ADMIN)** returns `403 Forbidden`.

Same thing with curl:

```powershell
$token = (Invoke-RestMethod -Method Post -Uri "http://localhost:8180/realms/demo/protocol/openid-connect/token" -Body @{ client_id="demo-frontend"; username="user"; password="password"; grant_type="password" }).access_token
curl.exe -i -H "Authorization: Bearer $token" http://localhost:8082/api/products
curl.exe -i -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d "{\"name\":\"Desk\",\"price\":199.0}" http://localhost:8082/api/products
```

The first returns 200, the second 403.

### Scenario 2 - an ADMIN

1. Click **Logout**, then **Login**, and sign in as `admin` / `password`.
2. The page shows both `USER` and `ADMIN`.
3. **Create product (ADMIN)** returns `201 Created` and the JSON of the new product,
   including `createdBy: "admin"` which came out of the token.
4. Put that product's `id` in the small id box and press **Delete product (ADMIN)**.
   It returns `204` with an empty body.
5. Delete the same id again: `404`, because the product is gone but you were still allowed
   to try. Authorization and existence are different questions.

### Scenario 3 - no token at all

Press **Products WITHOUT token** in the UI, or:

```powershell
curl.exe -i http://localhost:8082/api/products
```

```
HTTP/1.1 401
WWW-Authenticate: Bearer
```

And the public endpoint, still with no token:

```powershell
curl.exe -i http://localhost:8082/api/products/public
```

```
HTTP/1.1 200
```

### Scenario 4 - authenticated but with no roles

Log in as `nobody` / `password`. The token is perfectly valid and `nobody` has no realm
roles at all, so **Get my profile (USER)** returns `403`. This isolates authorization from
authentication better than any other test in the project.

### Scenario 5 - the data is really in PostgreSQL

Create a product as `admin`, then delete one of the seeded rows, then **stop and restart
product-service**. Call `GET /api/products` again. Your new product is still there and the
deleted one is still gone. Before this project used a database that was not true: every
restart reset the list, because it lived in a `ConcurrentHashMap`.

Confirm it from the database side rather than through the API:

```bash
docker exec demo-postgres psql -U postgres -d appdb -c "SELECT id, name, price, created_by FROM product_service.product ORDER BY id"
```

The `created_by` column of your new row says `admin`. That value was never typed into the
request body. It came from the `preferred_username` claim of the access token, through
`authentication.getName()`, into a SQL insert. That single column is the whole chain of this
project in miniature: Keycloak authenticated a person, signed a claim, Spring verified the
signature and mapped it to a principal, and PostgreSQL stored the result.

### Scenario 6 - the schemas really are isolated

```bash
docker exec -e PGPASSWORD=product_service demo-postgres psql -U product_service -d appdb -c "SELECT * FROM user_service.app_user"
docker exec -e PGPASSWORD=user_service    demo-postgres psql -U user_service    -d appdb -c "SELECT * FROM product_service.product"
docker exec -e PGPASSWORD=product_service demo-postgres psql -U product_service -d appdb -c "SELECT username FROM keycloak.user_entity"
```

All three are refused with `permission denied for schema`. Then check that each role can
still read its own tables:

```bash
docker exec -e PGPASSWORD=user_service demo-postgres psql -U user_service -d appdb -c "SELECT count(*) FROM app_user"
```

### 401 versus 403

| | 401 Unauthorized | 403 Forbidden |
|---|---|---|
| Means | "I do not know who you are" | "I know who you are, and no" |
| Cause here | no token, expired token, bad signature, wrong issuer | valid token, missing role |
| Fix | log in, or refresh the token | get the role assigned in Keycloak |
| Spring class | `AuthenticationEntryPoint` | `AccessDeniedHandler` |

The name *Unauthorized* for 401 is a historical mistake in the HTTP spec. Read it as
*Unauthenticated*. If you can explain this table in an interview you are ahead of most
candidates.

### Watching it happen in the logs

Both services log Spring Security at `DEBUG`. When a request is rejected you will see lines
naming the exact reason, for example an `AccessDeniedException` for a missing authority, or
a `JwtValidationException` with `Jwt expired at ...`. Turn this off in
`application.yml` when the noise stops being useful.

---

## Configuring Keycloak by hand

The realm import means you never *have* to do this, but you should do it once, because
interviews and real projects are about the admin console, not about a JSON file.

Delete the imported realm first (`Realm settings > Action > Delete`) or just build a
second realm called `demo2` alongside it.

### Create a realm

1. Top-left realm dropdown > **Create realm**.
2. Realm name: `demo`. **Create**.

Everything below happens inside this realm. The most common beginner mistake is creating
users in `master`; check the dropdown before every step.

### Create realm roles

1. **Realm roles** > **Create role**.
2. Role name: `USER`. **Save**.
3. Repeat for `ADMIN`.

Realm roles land in the `realm_access.roles` claim, which is what
`KeycloakRealmRoleConverter` reads.

### Create the frontend client

1. **Clients** > **Create client**.
2. Client type `OpenID Connect`, Client ID `demo-frontend`. **Next**.
3. Client authentication: **Off**. This is what makes it a *public* client.
   Authorization: Off.
   Authentication flow: tick **Standard flow** (that is the authorization code flow) and
   **Direct access grants** (so the curl examples above work). **Next**.
4. Login settings:
   - Root URL: `http://localhost:5173`
   - Valid redirect URIs: `http://localhost:5173/*`
   - Valid post logout redirect URIs: `http://localhost:5173/*`
   - Web origins: `http://localhost:5173`
5. **Save**.
6. Open **Advanced** > *Advanced settings* and set
   **Proof Key for Code Exchange Code Challenge Method** to `S256`.

Two of those fields cause most login failures:

- **Valid redirect URIs** is an allow-list. Keycloak refuses to redirect anywhere else,
  which is what stops an attacker from sending your authorization code to their own site.
  A mismatch shows as *"Invalid parameter: redirect_uri"* on the Keycloak page.
- **Web origins** controls the `Access-Control-Allow-Origin` header on Keycloak's own token
  endpoint. Empty means the browser blocks the code-for-token exchange with a CORS error.

### Create users

1. **Users** > **Add user**.
2. Username `admin`, Email `admin@demo.local`, First/Last name as you like,
   **Email verified** on. **Create**.
3. Tab **Credentials** > **Set password**. Password `password`,
   **Temporary: Off** (otherwise Keycloak forces a password change at first login).
4. Tab **Role mapping** > **Assign role**. Switch the filter to **Filter by realm roles**,
   tick `USER` and `ADMIN`. **Assign**.
5. Repeat for `user` with only the `USER` role.

### Verify

**Realm settings > General > Endpoints > OpenID Endpoint Configuration** opens the
discovery document. Confirm that `issuer` reads exactly
`http://localhost:8180/realms/demo`, the same string as the `issuer-uri` in both
`application.yml` files. If those two ever differ by so much as a trailing slash, every
request gets a 401.

### Exporting your work

To turn a hand-built realm back into a `realm-export.json`:

```bash
docker exec -it keycloak /opt/keycloak/bin/kc.sh export --dir /tmp/export --realm demo --users realm_file
docker cp keycloak:/tmp/export/demo-realm.json ./keycloak/import/realm-export.json
```

Note that the admin console's own *Partial export* button deliberately leaves out users and
credentials, which is why the command line is used here.

---

## Why CORS is needed

The Vue app is served from `http://localhost:5173`. The APIs live on `:8081` and `:8082`.
Different port means different *origin*, and the browser's same-origin policy applies.

For a request carrying an `Authorization` header the browser first sends a **preflight**:

```
OPTIONS /api/products HTTP/1.1
Origin: http://localhost:5173
Access-Control-Request-Method: GET
Access-Control-Request-Headers: authorization
```

Unless the service answers with `Access-Control-Allow-Origin: http://localhost:5173` and
`Access-Control-Allow-Headers: authorization`, the browser never sends the real request and
your JavaScript sees an opaque network error with no status code. That is
`corsConfigurationSource()` in both `SecurityConfig` classes.

Two things to keep straight:

1. **CORS is not security.** It protects the *browser user*, not the server. curl and
   Postman ignore it completely, which is why an endpoint can work in curl and fail in the app.
2. **Keycloak needs its own CORS setting.** The *Web origins* field of the `demo-frontend`
   client is what lets the Vue app call Keycloak's token endpoint. Your Spring
   configuration has no effect on Keycloak.

---

## Development mode is not production

`docker-compose.yml` runs Keycloak with `start-dev`. That means:

- **HTTP only, no TLS.** Every token crosses the network in clear text. In production
  Keycloak runs behind HTTPS with `start` and `--hostname https://...`, and `sslRequired`
  is `all`.
- **The database is real, the server mode is not.** Keycloak stores everything in
  PostgreSQL here, which is production-shaped. But `start-dev` still skips the `kc.sh build`
  step, so every boot re-derives its configuration instead of using a pre-built image.
- **No hostname strictness and no clustering.** A production run uses `kc.sh build` to
  produce an optimized image, and `start --optimized`.
- **admin / admin.** Obviously.

Also production-only concerns that this project skips on purpose: rotating the realm
signing keys, brute-force detection, a shorter `ssoSessionIdleTimeout`, audience validation
in the resource servers, and a proper token-revocation story.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Every protected call returns 401 with `WWW-Authenticate: Bearer` and **no** `error=` | the JWT decoder cannot reach Keycloak | see *The bare-Bearer 401* below |
| Every call returns 401 with `error="invalid_token"` | the token really is bad: wrong `iss`, expired, wrong realm | compare the `iss` claim on jwt.io with `application.yml`, character for character |
| 403 for a user who clearly has the role | the role converter is not wired in | check `setJwtGrantedAuthoritiesConverter` in `SecurityConfig` |
| Keycloak page says *Invalid parameter: redirect_uri* | the Vue app is not on 5173, or the URI is not registered | `strictPort` in `vite.config.js`, and the client's Valid redirect URIs |
| Browser console shows a CORS error against :8180 | Web origins empty on the client | add `http://localhost:5173` to the client's Web origins |
| Browser console shows a CORS error against :8081 or :8082 | `app.cors.allowed-origin` wrong | check `application.yml` |
| Editing `realm-export.json` changes nothing | the realm already exists, import is `IGNORE_EXISTING` | `docker compose down -v` then `up -d` |
| 401 after leaving the tab open | access token expired and refresh failed | `keycloak.updateToken(30)` handles the normal case; if the SSO session itself expired you must log in again |
| Service fails at startup with `Schema-validation: missing table` | Flyway did not run, or ran against the wrong schema | check `spring.flyway.schemas` and `hibernate.default_schema` match, then `docker compose down -v` and `up -d` |
| `FlywayValidateException: Migration checksum mismatch` | an already-applied migration file was edited | never edit an applied migration; add `V2__*.sql`, or `docker compose down -v` to start over |
| `permission denied for schema ...` | a service is reaching into another service's schema | that is the isolation working as designed; fetch the data over HTTP instead |
| `Connection refused` on 5442 | PostgreSQL is not up, or something else holds the port | `docker compose ps`, then `Get-NetTCPConnection -LocalPort 5442 -State Listen` |
| Keycloak container exits immediately | it cannot reach PostgreSQL | `docker compose logs postgres`; compose waits for a healthcheck, so this usually means bad `KC_DB_*` values |
| Editing `postgres/init/01-schemas.sql` changes nothing | init scripts run only when the data directory is created | `docker compose down -v` then `up -d` |
| Postgres container exits with `data directory was initialized by PostgreSQL version N` | the image's major version changed under an existing volume | `docker compose down -v` then `up -d`; a volume belongs to one major version |
| Postgres 18 exits with `there appears to be PostgreSQL data in /var/lib/postgresql/data` | volume mounted the old way | mount the volume on `/var/lib/postgresql`, as this compose file does, and recreate it with `-v` |

### The bare-Bearer 401

This is the most confusing failure in the whole project, and it is worth learning to
recognise on sight. The symptom is that *every* protected call fails, even with a token you
have decoded on jwt.io and confirmed is a perfectly good admin token:

```
HTTP/1.1 401
WWW-Authenticate: Bearer
```

The tell is the missing detail. A token that is genuinely rejected produces
`error="invalid_token"` and an explanation. A bare `Bearer` means the resource server never
got as far as judging the token, because the decoder itself could not be built. Spring turns
that into an `AuthenticationServiceException`, which carries no OAuth2 error code, so the
header comes back empty.

The cause is always the same: **the service cannot fetch Keycloak's discovery document or
JWKS.** Either Keycloak is not running, or something else is answering on that port.

Confirm it in one command. Ask the service's own view of the world, not the browser's:

```bash
curl -s http://localhost:8180/realms/demo/.well-known/openid-configuration
```

You should get JSON whose `issuer` matches `issuer-uri` exactly. If you get HTML, a 503, or
a page belonging to some other application, you have found the problem: another program owns
that port. On Windows, find out who:

```powershell
Get-NetTCPConnection -LocalPort 8180 -State Listen | ForEach-Object { Get-Process -Id $_.OwningProcess }
```

A subtlety worth knowing, because it makes the bug look impossible: **curl and the JVM can
disagree.** `localhost` resolves to both `127.0.0.1` and `::1`, and the two can be served by
different processes. curl tends to try IPv6 first, Java tends to try IPv4 first. So curl can
report a healthy Keycloak while your Spring service is talking to something else entirely on
the same URL. Test each stack separately when in doubt:

```bash
curl -s -4 http://localhost:8180/realms/demo/.well-known/openid-configuration
curl -s -6 http://localhost:8180/realms/demo/.well-known/openid-configuration
```

This is exactly why this project publishes Keycloak on 8180 rather than the default 8080.

### The one pitfall you will hit if you dockerize the services

The services run on your host on purpose. If you move them into Docker Compose, they will
reach Keycloak at `http://keycloak:8080`, but the browser gets its tokens from
`http://localhost:8180`, so `iss` says `localhost` and the service expects `keycloak`.
Every request fails with 401 and the error message is unhelpful.

Note that `extra_hosts: ["localhost:host-gateway"]` does not help, because inside a
container `localhost` already means that container. There are two real fixes:

1. Use one hostname that resolves the same from the browser and from inside the network.
   Give the container `hostname: keycloak`, set `KC_HOSTNAME: http://keycloak:8080`, add
   `127.0.0.1 keycloak` to `C:\Windows\System32\drivers\etc\hosts`, and use
   `http://keycloak:8080/realms/demo` everywhere.
2. Keep `issuer-uri` on the external URL but override only the key lookup with
   `spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://keycloak:8080/realms/demo/protocol/openid-connect/certs`.

Both are more machinery than this project needs, which is exactly why the services stay on
the host.

---

## Part 1 - What is Keycloak?

Keycloak is an **identity provider**: a separate server whose entire job is knowing who
your users are and issuing proof of it.

Without it, every microservice needs its own user table, its own password hashing, its own
login page, its own password-reset email, and its own idea of what a role is. With six
services you have six copies of the hardest security code in your system, and a user who
changes their password has to do it six times.

Keycloak centralises all of it. It owns the users, the passwords, the login page, the
two-factor setup, the social logins, and the sessions. Your services own none of it. They
only receive a signed statement, the JWT, saying "this is user `admin`, they have roles
`USER` and `ADMIN`, and I, Keycloak, vouch for it until 12:05".

It speaks the standards, OAuth2 and OpenID Connect, so nothing in this project is
Keycloak-specific except the shape of one claim. Point `issuer-uri` at Auth0, Okta or
Microsoft Entra and most of this code would keep working.

*In this project:* the whole of `docker-compose.yml`. That one container is Keycloak.

## Part 2 - What is a Realm?

A realm is a self-contained tenant: its own users, roles, clients, login page, token
lifetimes and signing keys. Nothing crosses a realm boundary. User `admin` in realm `demo`
and user `admin` in realm `master` are two unrelated people.

The realm is also the *issuer*. Its identity is a URL:

```
http://localhost:8180/realms/demo
```

Everything hangs off that URL, and you can browse it right now:

```
http://localhost:8180/realms/demo/.well-known/openid-configuration
http://localhost:8180/realms/demo/protocol/openid-connect/auth
http://localhost:8180/realms/demo/protocol/openid-connect/token
http://localhost:8180/realms/demo/protocol/openid-connect/certs
```

*In this project:* `"realm": "demo"` in `keycloak/import/realm-export.json`, and the same URL as
`issuer-uri` in both `application.yml` files. That string is the single point of agreement
between Keycloak and your services.

Typical realm strategy in real systems: one realm for customers, one for employees. Never
one realm per microservice, because then a single sign-on is impossible.

## Part 3 - What is a Client?

A client is an *application* registered with the realm, not a user. Registration is what
lets Keycloak answer "is this really my Vue app, and where am I allowed to send the token?"

This project has one client, `demo-frontend`, and it is public:

```json
"clientId": "demo-frontend",
"publicClient": true,
"standardFlowEnabled": true,
"redirectUris": ["http://localhost:5173/*"],
"webOrigins": ["http://localhost:5173"],
"attributes": { "pkce.code.challenge.method": "S256" }
```

**Public** means no client secret. A secret shipped inside JavaScript is not a secret,
anyone can open DevTools and read it. Public clients get their security from two other
places instead: the redirect URI allow-list, and PKCE.

**Why do the two Spring Boot services have no client?** Because they never ask for a token.
They only verify tokens that someone else already obtained, using Keycloak's published
public keys. There is nothing to register. Old Keycloak adapters made you create a
"bearer-only" client for each service, and that concept no longer exists in modern Keycloak.

**When would you add a client for a backend?** When the backend needs to act *as itself*
with no user involved: a nightly job calling another service, for example. Then you create
a confidential client with a secret and `serviceAccountsEnabled`, and the backend uses the
client-credentials grant to get its own token.

*In this project:* `frontend/src/keycloak.js` lines 1 to 20 must match the client
registration exactly, or login fails.

## Part 4 - What is a User and a Role?

A **user** is a person: username, credentials, email, attributes. A **role** is a named
permission you attach to users.

```json
"roles": { "realm": [ { "name": "USER" }, { "name": "ADMIN" } ] },
"users": [
  { "username": "user",  "realmRoles": ["USER"] },
  { "username": "admin", "realmRoles": ["USER", "ADMIN"] },
  { "username": "nobody","realmRoles": [] }
]
```

Notice that `admin` has both roles. Roles are not levels, they are a set. Nothing makes
`ADMIN` automatically include `USER`, so if you want an admin to also read their own
profile, you assign both. (Keycloak does support *composite roles*, where `ADMIN`
automatically grants `USER`. That is one checkbox on the role, and it is worth trying once
you are comfortable.)

**Realm roles versus client roles.** Realm roles are global to the realm and appear in
`realm_access.roles`. Client roles belong to one client and appear under
`resource_access.<clientId>.roles`. Realm roles are used here because there is one
application family and one obvious claim to read. Client roles matter when the same user
should be an admin in one application and a plain user in another.

*In this project:* run the app, log in, press **Show my raw token claims**, and read
`realm_access` in the response.

## Part 5 - What happens when I click Login?

Nothing local. `frontend/src/keycloak.js` calls `keycloak.login()`, which navigates the
whole browser away from your app:

```
http://localhost:8180/realms/demo/protocol/openid-connect/auth
  ?client_id=demo-frontend
  &redirect_uri=http%3A%2F%2Flocalhost%3A5173%2F
  &response_type=code
  &scope=openid
  &code_challenge=E9Melhoa2Owv...        (SHA-256 of a random string kept in the app)
  &code_challenge_method=S256
  &state=...&nonce=...
```

Keycloak checks that `demo-frontend` exists and that the `redirect_uri` is in its
allow-list, then renders **its own** login page. Your Vue app is not running at that moment;
it has been unloaded. Whatever you type goes to Keycloak.

On success Keycloak sets an SSO session cookie for `localhost:8180` and redirects back:

```
http://localhost:5173/?code=8a1f...&state=...
```

Your Vue app reloads from scratch. `initKeycloak()` in `main.js` runs before the app mounts,
spots the `code` parameter, and exchanges it. That is why the app is mounted inside
`initKeycloak().then(...)` and not before.

The SSO session cookie is what makes single sign-on work. Log in once, and a second
application in the same realm that redirects to Keycloak gets sent straight back with a code,
no password prompt.

## Part 6 - Where does the JWT come from?

From one POST, made by `keycloak-js` behind your back:

```
POST http://localhost:8180/realms/demo/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=8a1f...
&client_id=demo-frontend
&redirect_uri=http://localhost:5173/
&code_verifier=<the original random string>
```

Keycloak checks that the code is unused and unexpired, and that the SHA-256 of
`code_verifier` equals the `code_challenge` it stored earlier. Then it responds:

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6...",
  "expires_in": 300,
  "refresh_token": "eyJhbGciOiJIUzUxMiIs...",
  "id_token": "eyJhbGciOiJSUzI1NiIs...",
  "token_type": "Bearer"
}
```

The access token is a JWT signed with the realm's RS256 private key. Decoded, its payload
for the `admin` user in this project looks like:

```json
{
  "iss": "http://localhost:8180/realms/demo",
  "aud": "demo-api",
  "azp": "demo-frontend",
  "sub": "b1f0...-a UUID, the stable user id",
  "exp": 1757483000,
  "iat": 1757482700,
  "preferred_username": "admin",
  "email": "admin@demo.local",
  "realm_access": { "roles": ["ADMIN", "USER"] },
  "scope": "profile email"
}
```

Those are exactly the values verified against a running Keycloak 26.1.5 while building this
project. `realm_access.roles` is the claim everything in Part 8 depends on.

*In this project:* `keycloak.token` is the raw string, `keycloak.tokenParsed` is the decoded
payload. `api.js` puts the raw string into the `Authorization` header.

## Part 7 - How does Spring Boot validate the JWT?

Three lines of YAML start the whole machine:

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: http://localhost:8180/realms/demo
```

At **startup**, Spring Boot auto-configuration:

1. GETs `<issuer-uri>/.well-known/openid-configuration`.
2. Reads `jwks_uri` out of it.
3. Builds a `NimbusJwtDecoder` pointed at that JWKS URL, plus the default validators
   (`exp`, `nbf`, and `iss` equals `issuer-uri`).

On **each request**, `BearerTokenAuthenticationFilter`:

1. Pulls the token out of `Authorization: Bearer ...`.
2. Reads the `kid` from the token header and finds the matching public key in the cached
   JWKS, fetching the JWKS the first time.
3. Verifies the RS256 signature. Any tampering, even one character in the role list, fails here.
4. Checks the standard claims.
5. Hands the resulting `Jwt` to `JwtAuthenticationConverter`.
6. Puts a `JwtAuthenticationToken` into the `SecurityContext`.

No call to Keycloak is made in steps 1 to 6 after the JWKS is cached. That is the whole
appeal of JWTs: verification is a local cryptographic operation.

*In this project:* `.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))` in both
`SecurityConfig` classes is the switch that turns all of this on.

## Part 8 - How does Spring know the user is ADMIN?

It does not, until you tell it. This is the heart of the project.

The token says:

```json
"realm_access": { "roles": ["ADMIN", "USER"] }
```

Spring Security's default converter reads only the `scope` claim, so by default the
authority list is `[SCOPE_profile, SCOPE_email]`. No roles. `hasRole("ADMIN")` is false and
you get 403 while staring at a token that plainly says ADMIN.

`KeycloakRealmRoleConverter` closes the gap:

```java
Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
    for (Object role : roles) {
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
```

and `SecurityConfig` plugs it in:

```java
JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
converter.setPrincipalClaimName("preferred_username");
```

Now the authority list is `[ROLE_ADMIN, ROLE_USER, SCOPE_profile, SCOPE_email]`, and:

```java
@PreAuthorize("hasRole('ADMIN')")
@PostMapping
public ResponseEntity<Product> createProduct(...)
```

`hasRole('ADMIN')` expands to `hasAuthority('ROLE_ADMIN')`, finds it, and the method runs.

The `ROLE_` prefix exists because Spring adds it when *checking* and never when *building*.
Keycloak roles are named without it so that the mapping is one readable line.

`setPrincipalClaimName("preferred_username")` is a small extra: without it,
`authentication.getName()` returns the `sub` UUID, and `createdBy` on every product would
read `b1f0e2c4-...` instead of `admin`.

*See it for yourself:* press **Get my profile (USER)** in the app. The response shows
`realmRoles` (the raw claim) next to `authorities` (what Spring built from it).

## Part 9 - What happens when I call an API without a token?

```
GET http://localhost:8082/api/products      (no Authorization header)
```

`BearerTokenAuthenticationFilter` finds no token, so no `Authentication` is placed in the
context. `AuthorizationFilter` then evaluates `anyRequest().authenticated()` against an
anonymous request and denies it. Because the request was never authenticated,
`ExceptionTranslationFilter` routes to the `AuthenticationEntryPoint`, which for a bearer
resource server answers:

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer
```

The same 401 comes back for an expired token, a token signed by a different realm, or a
token whose `iss` does not match. All of them mean the same thing: *I could not establish
who you are*.

The public endpoint is the counter-example. `permitAll()` matches before the authentication
requirement, so `GET /api/products/public` returns 200 with no header at all.

*In this project:* the **Products WITHOUT token** button calls with `withToken: false` on
purpose so you can watch this happen.

## Part 10 - What happens when I have USER but need ADMIN?

Log in as `user` and POST a product.

The token is valid, so the request *is* authenticated. Authorities are
`[ROLE_USER, SCOPE_profile, SCOPE_email]`. `@PreAuthorize("hasRole('ADMIN')")` looks for
`ROLE_ADMIN`, does not find it, and throws `AuthorizationDeniedException`, a subclass of
`AccessDeniedException`. `ExceptionTranslationFilter` sees an authenticated principal and
routes to the `AccessDeniedHandler`:

```
HTTP/1.1 403 Forbidden
```

401 says *who are you*. 403 says *I know exactly who you are, and the answer is no*. If you
retry a 403 with the same credentials you will always get 403 again; the fix is a role
change in Keycloak, not a new login. A useful consequence: after granting the role in the
admin console the user must obtain a **new token**, because the old one still carries the old
role list. Log out and back in, or wait for the refresh.

There is a third case worth knowing. `nobody` has no roles at all, so even
`GET /api/users/me`, which only needs `USER`, returns 403. Authentication succeeded,
authorization failed.

## Part 11 - Access token vs Refresh token vs ID token

| | Access token | Refresh token | ID token |
|---|---|---|---|
| Answers | may the bearer call this API | may I have a new access token | who logged in |
| Sent to | your APIs, `:8081` and `:8082` | Keycloak's token endpoint only | nobody, read locally |
| Lifetime here | 5 minutes | tied to the SSO session, 30 min idle | same as access token |
| Format | JWT, RS256 | opaque to you, do not parse it | JWT, RS256 |
| In this project | `keycloak.token` | held inside `keycloak-js` | `keycloak.idTokenParsed` |

The access token is short-lived to limit the damage of a leak. Since a resource server
cannot ask "was this revoked?" without giving up offline validation, the mitigation is
simply that the token stops working within minutes.

The refresh token exists so that the short lifetime does not force a login every five
minutes. `getValidToken()` in `frontend/src/keycloak.js` calls `keycloak.updateToken(30)`,
which refreshes only if the access token expires within 30 seconds, and otherwise does
nothing. Delete that call and the app starts throwing 401s after five minutes.

The two mistakes to avoid: **never send an ID token to an API** (it is addressed to the
client application, not to your service), and **never send a refresh token anywhere but
Keycloak** (it is the long-lived credential).

## Part 12 - OAuth2 vs OpenID Connect

**OAuth2** solves delegated authorization: "let this application access that resource on my
behalf." It defines the authorization code flow, the token endpoint, scopes, and the
`Authorization: Bearer` header. It deliberately says nothing about who the user is. Trying
to authenticate with plain OAuth2 is a known anti-pattern: an access token proves the
*bearer may call an API*, not *who is holding it*.

**OpenID Connect** is a small standard layered on top that adds authentication:

- the **ID token**, a JWT describing the user, with `iss`, `sub`, `aud`, `exp`, `nonce`
- the `openid` scope that requests it
- the **discovery document** at `/.well-known/openid-configuration`
- the **JWKS endpoint** publishing the signing keys
- the `/userinfo` endpoint

Every one of those appears in this project. `scope=openid` is in the login URL, discovery is
what `issuer-uri` fetches at startup, and JWKS is how signatures are checked.

The short version for an interview: *OAuth2 gives you an access token for calling APIs.
OIDC adds an ID token so you know who logged in. Keycloak implements both, and Spring's
resource server support consumes the OAuth2 half while the JavaScript adapter drives the
OIDC half.*

## Part 13 - Configuring Keycloak manually

See the full click-by-click walkthrough in *Configuring Keycloak by hand* above.
The three things worth internalising:

1. Check the realm dropdown before every action. Creating users in `master` is the most
   common mistake in Keycloak.
2. Redirect URIs and Web origins are allow-lists, and empty ones fail in confusing ways.
   A wrong redirect URI produces a Keycloak error page; empty Web origins produce a browser
   CORS error during the token exchange.
3. Set **Temporary: Off** when setting a password, unless you want a forced password change.

## Part 14 - How this would grow in a real system

Start from this project and add, roughly in this order:

**An API gateway.** Spring Cloud Gateway in front of the services. The gateway validates
the token once and forwards it downstream, so the browser talks to one origin, which also
makes the CORS configuration a single place rather than one per service.

**Service-to-service calls.** When `product-service` needs to call `user-service` with no
user present, it gets its own token via the client-credentials grant using a confidential
client with a service account. When it calls *on behalf of* the current user, it forwards
the incoming token, or exchanges it. Spring Security's
`OAuth2AuthorizedClientManager` and a `WebClient` filter do this for you.

**A confidential client and the BFF pattern.** SPAs holding tokens in memory are acceptable
but not ideal: any XSS in your app can read the token. The current best practice is a
Backend For Frontend, a small server that holds the tokens, keeps an HttpOnly session cookie
with the browser, and proxies API calls. The Vue app then has no token at all.

**Groups and composite roles.** Assign roles to groups, users to groups. Use composite
roles so `ADMIN` implies `USER` instead of assigning both by hand.

**A production Keycloak.** PostgreSQL via `KC_DB` is already done here. What is still
missing: `kc.sh build` producing an optimised image, `start --optimized` behind HTTPS,
`sslRequired: all`, more than one replica, and realm configuration applied with the admin
REST API or a GitOps operator rather than by clicking.

**A production database.** The schema-per-service split and the per-service roles here are
the real pattern, and they scale further than people expect. What would change: connection
pool sizing (HikariCP defaults to 10 connections per service, which multiplies fast),
managed backups and point-in-time recovery, a read replica if reporting queries appear, and
`flyway.clean-disabled` so nobody can wipe a schema by accident. The moment two services
genuinely need to scale or fail independently, split them onto separate database servers;
because each already has its own schema, its own role and its own migrations, that move is
a connection-string change rather than a rewrite. That is the real payoff of doing it this
way from the start.

**Hardening the resource servers.** Validate `aud` (the snippet is in section D), shorten
token lifetimes, turn on Keycloak's brute-force detection, rotate realm keys, and add
`spring-boot-starter-actuator` with a health check for the JWKS endpoint.

**Testing.** `spring-security-test` (already a dependency in both services) lets you write
controller tests without a running Keycloak:

```java
mockMvc.perform(post("/api/products")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
        .contentType(APPLICATION_JSON)
        .content("{\"name\":\"X\",\"price\":1.0}"))
    .andExpect(status().isCreated());
```

For a full integration test, Testcontainers has a Keycloak module that starts a real
Keycloak and imports a realm.

What stays exactly the same as it is here, no matter how large the system grows: the realm
is the issuer, the token is validated locally against JWKS, and roles reach Spring through
a converter. Everything else is scaffolding around those three ideas.
