# API examples

Every endpoint, called against the running Compose stack, with the response it really gave. Tokens
are redacted, and ids, subjects and timestamps will differ on your machine. The capture named the
projects it created with an `api-example-` prefix so it could delete them afterwards; the prefix is
left out here.

The examples use `curl` and, to pull fields out of JSON, `jq`. On Windows, run them in Git Bash.

## Getting tokens

The Vue app signs in through a separate window, with the authorization code flow and PKCE; see
[flows](flows.md#the-sign-in-window). For trying the API by hand, the development users can use the
password grant, which `platform-web` allows locally:

```bash
token() {
  curl -s http://localhost:8190/realms/platform/protocol/openid-connect/token \
    -d grant_type=password -d client_id=platform-web -d scope=openid \
    -d username="$1" -d password=password | jq -r .access_token
}
ALI=$(token ali); BANK=$(token bank_user); DUAL=$(token dual)
ADMIN=$(token admin_user); SUPER=$(token super_admin)
```

The token response:

```json
{
  "access_token": "<redacted>",
  "expires_in": 300,
  "refresh_expires_in": 1800,
  "refresh_token": "<redacted>",
  "token_type": "Bearer",
  "id_token": "<redacted>",
  "not-before-policy": 0,
  "session_state": "df3b2541-2cad-41fc-b83d-a0eb4f456ca3",
  "scope": "openid profile email"
}
```

A service signs in as itself, with its secret from `.env`:

```bash
source .env
CADASTRAL=$(curl -s http://localhost:8190/realms/platform/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=cadastral-service \
  -d client_secret="$CADASTRAL_SERVICE_CLIENT_SECRET" | jq -r .access_token)
```

```json
{
  "access_token": "<redacted>",
  "expires_in": 300,
  "refresh_expires_in": 0,
  "token_type": "Bearer",
  "not-before-policy": 0,
  "scope": "profile email"
}
```

Development sessions have the session bootstrap run first, as the app does. Call
`GET /api/users/me` once per user before trying organization-scoped endpoints, so their
memberships exist. [Flows](flows.md#the-keycloak-token) shows both tokens decoded.

## Reading the answers

| Status | Meaning | How to recognise it |
|---|---|---|
| 200, 201 | done | |
| 400 | an organization-scoped endpoint without `X-Organization-TIN`, or an invalid body | Spring's error JSON |
| 401 | no token, or not a valid one for this service | `WWW-Authenticate: Bearer`, plus `error="invalid_token"` when a token was sent |
| 403 | the caller lacks the permission, or a person called `/internal/**` | `WWW-Authenticate: Bearer error="insufficient_scope"`, empty body |
| 403 | not a member of the organization in the header | JSON body, `"error": "organization_membership_required"` |
| 404 | not found, **including** a record that belongs to another organization | empty body |

---

## Public

### `GET /api/public/hello`, no token

```bash
curl -s -i http://localhost:8090/api/public/hello
```

```http
HTTP/1.1 200
Content-Type: application/json

{"message":"Public endpoint. No access token was required.","service":"user-service"}
```

## Session

### A protected path without a token

```bash
curl -s -i http://localhost:8090/api/users/me
```

```http
HTTP/1.1 401
WWW-Authenticate: Bearer
```

### `GET /api/users/me`, the session bootstrap

Upserts the local profile, reconciles memberships from the token's `org_tins`, and reports what this
service sees.

```bash
curl -s http://localhost:8090/api/users/me -H "Authorization: Bearer $ALI"
```

```json
{
  "subject": "0789b5e3-be38-4098-85d6-0a99d28f5259",
  "username": "ali",
  "localProfileId": "b5577740-1802-4f74-bb68-4db7da9a032a",
  "fullName": "Ali Karimov",
  "userType": "I",
  "identityVerified": true,
  "tokenUse": "user",
  "realmRolesFromToken": ["JISMONIY_SHAXS", "QURUVCHI", "YURIDIK_SHAXS"],
  "effectivePermissionsHere": [],
  "springAuthorities": ["ROLE_JISMONIY_SHAXS", "ROLE_QURUVCHI", "ROLE_YURIDIK_SHAXS", "SCOPE_email", "SCOPE_openid", "SCOPE_profile", "TOKEN_USE_USER"],
  "organizationTinsReportedByOneId": ["111111111", "222222222"],
  "organizationTinsActive": ["111111111", "222222222"],
  "issuer": "http://localhost:8190/realms/platform"
}
```

`effectivePermissionsHere` is empty on purpose. It lists **user-service's** permissions, and ali's
roles grant none there. `QURUVCHI`'s project permissions live in cadastral-service.

## Organizations

### `GET /api/organizations/mine`

```bash
curl -s http://localhost:8090/api/organizations/mine -H "Authorization: Bearer $ALI"
```

```json
{
  "activeMemberships": [
    {"tin": "111111111", "name": "\"IT-GROUP\" mas'uliyati cheklangan jamiyati", "shortName": "IT-GROUP", "isBasic": false, "source": "ONEID"},
    {"tin": "222222222", "name": "\"QURILISH SAVDO\" mas'uliyati cheklangan jamiyati", "shortName": "QURILISH SAVDO", "isBasic": false, "source": "ONEID"}
  ],
  "note": "Read-only. Memberships are written by user-service through /internal/memberships/{sub}/sync, never from a browser."
}
```

### `GET /api/organizations`, needs `ORGANIZATION_READ`

```bash
curl -s http://localhost:8090/api/organizations -H "Authorization: Bearer $ALI"
```

```json
[
  {"tin": "111111111", "name": "\"IT-GROUP\" mas'uliyati cheklangan jamiyati", "shortName": "IT-GROUP", "active": true},
  {"tin": "222222222", "name": "\"QURILISH SAVDO\" mas'uliyati cheklangan jamiyati", "shortName": "QURILISH SAVDO", "active": true},
  {"tin": "333333333", "name": "\"MILLIY BANK\" aksiyadorlik jamiyati", "shortName": "MILLIY BANK", "active": true},
  {"tin": "444444444", "name": "\"BEGONA TASHKILOT\" mas'uliyati cheklangan jamiyati", "shortName": "BEGONA TASHKILOT", "active": true}
]
```

`bank_user` holds `JISMONIY_SHAXS` and `BANK`, neither of which grants `ORGANIZATION_READ`:

```bash
curl -s -i http://localhost:8090/api/organizations -H "Authorization: Bearer $BANK"
```

```http
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token.", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
```

### `GET /api/organizations/{tin}`

```bash
curl -s http://localhost:8090/api/organizations/111111111 -H "Authorization: Bearer $ALI"
```

```json
{"tin": "111111111", "name": "\"IT-GROUP\" mas'uliyati cheklangan jamiyati", "shortName": "IT-GROUP", "active": true}
```

### `GET /api/organizations/current`, the acting organization

```bash
curl -s http://localhost:8090/api/organizations/current -H "Authorization: Bearer $ALI" -H "X-Organization-TIN: 111111111"
```

```json
{"tin": "111111111", "name": "\"IT-GROUP\" mas'uliyati cheklangan jamiyati", "shortName": "IT-GROUP", "active": true}
```

An organization ali does not belong to:

```bash
curl -s -i http://localhost:8090/api/organizations/current -H "Authorization: Bearer $ALI" -H "X-Organization-TIN: 444444444"
```

```http
HTTP/1.1 403
Content-Type: application/json;charset=ISO-8859-1

{"error":"organization_membership_required","message":"You are not an active member of organization 444444444"}
```

## Projects

### `GET /api/projects`

```bash
curl -s http://localhost:8090/api/projects -H "Authorization: Bearer $ALI" -H "X-Organization-TIN: 111111111"
```

```json
[
  {"id": "a1d41d96-99fb-4262-9967-01d537c9fa5c", "name": "Chilonzor turar-joy majmuasi", "address": "Toshkent, Chilonzor tumani", "status": "IN_PROGRESS", "organizationTin": "111111111", "createdBySub": "00000000-0000-0000-0000-000000000000"},
  {"id": "7e38e92e-f2b2-4488-baa5-d599cc65284c", "name": "Yunusobod savdo markazi", "address": "Toshkent, Yunusobod tumani", "status": "DRAFT", "organizationTin": "111111111", "createdBySub": "00000000-0000-0000-0000-000000000000"}
]
```

Without the organization header:

```bash
curl -s -i http://localhost:8090/api/projects -H "Authorization: Bearer $ALI"
```

```http
HTTP/1.1 400
Content-Type: application/json

{"timestamp":"2026-09-14T07:17:32.207+00:00","status":400,"error":"Bad Request","path":"/api/projects"}
```

### `POST /api/projects`, needs `PROJECT_CREATE`

The organization comes from the verified header, never from the body.

```bash
curl -s -X POST http://localhost:8090/api/projects -H "Authorization: Bearer $ALI" -H "X-Organization-TIN: 111111111" -H "Content-Type: application/json" -d '{"name":"Chilonzor maktabi","address":"Toshkent, Chilonzor tumani"}'
```

```http
HTTP/1.1 201
Content-Type: application/json

{"id":"8a101c36-58e0-42f4-88fd-ec61ae79513b","name":"Chilonzor maktabi","address":"Toshkent, Chilonzor tumani","status":"DRAFT","organizationTin":"111111111","createdBySub":"0789b5e3-be38-4098-85d6-0a99d28f5259"}
```

A body without `name` is 400. `bank_user` in its own organization, without the permission:

```bash
curl -s -i -X POST http://localhost:8090/api/projects -H "Authorization: Bearer $BANK" -H "X-Organization-TIN: 333333333" -H "Content-Type: application/json" -d '{"name":"x","address":"x"}'
```

```http
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token.", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
```

`ali`, who has the permission, for an organization that is not theirs:

```bash
curl -s -i -X POST http://localhost:8090/api/projects -H "Authorization: Bearer $ALI" -H "X-Organization-TIN: 333333333" -H "Content-Type: application/json" -d '{"name":"x","address":"x"}'
```

```http
HTTP/1.1 403
Content-Type: application/json;charset=ISO-8859-1

{"error":"organization_membership_required","message":"You are not an active member of organization 333333333"}
```

### `PUT /api/projects/{id}`, needs `PROJECT_UPDATE`

```bash
curl -s -X PUT http://localhost:8090/api/projects/8a101c36-58e0-42f4-88fd-ec61ae79513b -H "Authorization: Bearer $ALI" -H "X-Organization-TIN: 111111111" -H "Content-Type: application/json" -d '{"name":"Chilonzor maktabi, 2-bosqich","address":"Toshkent, Chilonzor tumani"}'
```

```json
{"id":"8a101c36-58e0-42f4-88fd-ec61ae79513b","name":"Chilonzor maktabi, 2-bosqich","address":"Toshkent, Chilonzor tumani","status":"DRAFT","organizationTin":"111111111","createdBySub":"0789b5e3-be38-4098-85d6-0a99d28f5259"}
```

The same project, while acting for ali's other organization. It is not found from there:

```bash
curl -s -i -X PUT http://localhost:8090/api/projects/8a101c36-58e0-42f4-88fd-ec61ae79513b -H "Authorization: Bearer $ALI" -H "X-Organization-TIN: 222222222" -H "Content-Type: application/json" -d '{"name":"x","address":"x"}'
```

```http
HTTP/1.1 404
```

## Payments

### `POST /api/payments`, needs `PAYMENT_CREATE`

`dual` holds both `QURUVCHI` and `BANK`, so the same token creates the project and records the payment.

```bash
curl -s -X POST http://localhost:8090/api/payments -H "Authorization: Bearer $DUAL" -H "X-Organization-TIN: 111111111" -H "Content-Type: application/json" -d '{"projectId":"8a101c36-58e0-42f4-88fd-ec61ae79513b","amount":1500000.00}'
```

```http
HTTP/1.1 201
Content-Type: application/json

{"id":"df7e5d07-42e7-4ec9-b223-c0069c044ab2","projectId":"8a101c36-58e0-42f4-88fd-ec61ae79513b","amount":1500000,"organizationTin":"111111111","createdBySub":"abafa3fe-1cbc-4559-9b11-3d03e16c4de7"}
```

A project of another organization, from `bank_user`, who is a real member of 333333333 and holds
`PAYMENT_CREATE`:

```bash
curl -s -i -X POST http://localhost:8090/api/payments -H "Authorization: Bearer $BANK" -H "X-Organization-TIN: 333333333" -H "Content-Type: application/json" -d '{"projectId":"8a101c36-58e0-42f4-88fd-ec61ae79513b","amount":1}'
```

```http
HTTP/1.1 404
```

Before phase 13 this answered 201; see the [journal](implementation-journal.md), phase 13.

`ali` has `QURUVCHI` only, so cannot record payments:

```bash
curl -s -i -X POST http://localhost:8090/api/payments -H "Authorization: Bearer $ALI" -H "X-Organization-TIN: 111111111" -H "Content-Type: application/json" -d '{"projectId":"8a101c36-58e0-42f4-88fd-ec61ae79513b","amount":1}'
```

```http
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token.", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
```

### `GET /api/payments`

```bash
curl -s http://localhost:8090/api/payments -H "Authorization: Bearer $DUAL" -H "X-Organization-TIN: 111111111"
```

```json
[{"id":"df7e5d07-42e7-4ec9-b223-c0069c044ab2","projectId":"8a101c36-58e0-42f4-88fd-ec61ae79513b","amount":1500000,"organizationTin":"111111111","createdBySub":"abafa3fe-1cbc-4559-9b11-3d03e16c4de7"}]
```

## Administration

### `GET /api/admin/users`, needs `USER_READ`

```bash
curl -s http://localhost:8090/api/admin/users -H "Authorization: Bearer $ADMIN"
```

The first two of the seven profiles returned:

```json
[
  {"id":"41223d71-2731-44f7-abbc-3a1ecfe396af","keycloakSub":"dcb8dfe1-ced1-4f17-9b87-7c2c80d012d2","oneidUserId":"admin_user","fullName":"Aziza Adminova","identityVerified":true},
  {"id":"efe4c2d5-70dd-4303-8e69-0cc4efb0d9ad","keycloakSub":"51346345-ff17-45f3-9426-7ca2aade0477","oneidUserId":"akarimov","fullName":"Ali Karimov","identityVerified":true}
]
```

### `PUT /api/admin/users/{id}`, needs `USER_UPDATE`

```bash
curl -s -X PUT http://localhost:8090/api/admin/users/41223d71-2731-44f7-abbc-3a1ecfe396af -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" -d '{"fullName":"Aziza Adminova"}'
```

```json
{"id":"41223d71-2731-44f7-abbc-3a1ecfe396af","keycloakSub":"dcb8dfe1-ced1-4f17-9b87-7c2c80d012d2","oneidUserId":"admin_user","fullName":"Aziza Adminova","identityVerified":true}
```

### `DELETE /api/admin/users/{id}`, needs `USER_DELETE`

`ADMIN`, for an id that does not exist:

```bash
curl -s -i -X DELETE http://localhost:8090/api/admin/users/00000000-0000-0000-0000-000000000001 -H "Authorization: Bearer $ADMIN"
```

```http
HTTP/1.1 404
```

`SUPER_ADMIN` lacks `USER_DELETE`, so is refused before any lookup:

```bash
curl -s -i -X DELETE http://localhost:8090/api/admin/users/00000000-0000-0000-0000-000000000001 -H "Authorization: Bearer $SUPER"
```

```http
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token.", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
```

### `GET /api/platform/audit`, needs `PLATFORM_ADMIN`

```bash
curl -s http://localhost:8090/api/platform/audit -H "Authorization: Bearer $SUPER"
```

```json
{
  "message": "Platform-level endpoint. ADMIN cannot reach this; SUPER_ADMIN can.",
  "caller": "super_admin",
  "profileCount": 7,
  "rolePermissionsEnforcedHere": {
    "ADMIN": ["USER_DELETE", "USER_READ", "USER_UPDATE"],
    "SUPER_ADMIN": ["PLATFORM_ADMIN", "USER_READ", "USER_UPDATE"]
  }
}
```

The same call as `ADMIN` answers 403 with `insufficient_scope`.

## Service to service

Services call organization-service directly on port 8092. The gateway does not route `/internal/**`.

### `GET /internal/ping`

```bash
curl -s http://localhost:8092/internal/ping -H "Authorization: Bearer $CADASTRAL"
```

```json
{"service":"organization-service","callerType":"SERVICE","callingClient":"cadastral-service","principal":"service-account-cadastral-service","audience":["organization-service"],"rolesGrantedHere":["ORG_READ"]}
```

### `GET /internal/memberships/{sub}`, needs `ORG_READ`

```bash
ALI_SUB=$(curl -s http://localhost:8090/api/users/me -H "Authorization: Bearer $ALI" | jq -r .subject)
curl -s http://localhost:8092/internal/memberships/$ALI_SUB -H "Authorization: Bearer $CADASTRAL"
```

```json
{"keycloakSub":"0789b5e3-be38-4098-85d6-0a99d28f5259","activeTins":["111111111","222222222"]}
```

The same call with a person's token, whatever their roles:

```bash
curl -s -i http://localhost:8092/internal/memberships/$ALI_SUB -H "Authorization: Bearer $ALI"
```

```http
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token.", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
```

### `POST /internal/memberships/{sub}/sync`, needs `ORG_MEMBERSHIP_SYNC`

cadastral-service may read memberships but not rewrite them:

```bash
curl -s -i -X POST http://localhost:8092/internal/memberships/$ALI_SUB/sync -H "Authorization: Bearer $CADASTRAL" -H "Content-Type: application/json" -d '{"tins":[]}'
```

```http
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token.", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
```

user-service holds the role. With its own service token, `USER_SVC`, obtained like `CADASTRAL`:

```bash
curl -s -X POST http://localhost:8092/internal/memberships/$ALI_SUB/sync -H "Authorization: Bearer $USER_SVC" -H "Content-Type: application/json" -d '{"tins":["111111111","222222222"]}'
```

```json
{"keycloakSub":"0789b5e3-be38-4098-85d6-0a99d28f5259","reported":["111111111","222222222"],"activeTins":["111111111","222222222"]}
```

### A service token at the gateway

```bash
curl -s -i http://localhost:8090/api/users/me -H "Authorization: Bearer $CADASTRAL"
```

```http
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
```

## CORS

The preflight a browser sends before the app's first `POST`:

```bash
curl -s -i -X OPTIONS http://localhost:8090/api/projects -H "Origin: http://localhost:5174" -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: authorization,content-type,x-organization-tin"
```

```http
HTTP/1.1 200
Access-Control-Allow-Origin: http://localhost:5174
Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS
Access-Control-Allow-Headers: authorization, content-type, x-organization-tin
```

From any other origin:

```bash
curl -s -i -X OPTIONS http://localhost:8090/api/projects -H "Origin: http://evil.example" -H "Access-Control-Request-Method: POST"
```

```http
HTTP/1.1 403

Invalid CORS request
```

## Logout

The browser is sent to Keycloak's end-session endpoint, carrying the ID token as `id_token_hint`:

```http
GET /realms/platform/protocol/openid-connect/logout?client_id=platform-web&post_logout_redirect_uri=http%3A%2F%2Flocalhost%3A5174%2F&id_token_hint=<ID token>

HTTP/1.1 302
Location: http://localhost:5174/
```

Refreshing with that session's refresh token afterwards:

```http
POST /realms/platform/protocol/openid-connect/token
grant_type=refresh_token&client_id=platform-web&refresh_token=<redacted>

HTTP/1.1 400
{"error":"invalid_grant","error_description":"Session not active"}
```

## OneID's protocol, against the mock

What Keycloak sends to OneID's single URL, shown against mock-oneid. These calls come only from
Keycloak, never from a browser or a service.

A wrong client secret:

```bash
curl -s -i http://localhost:8191/sso/oauth/Authorization.do -d grant_type=one_authorization_code -d client_id=platform -d client_secret=wrong -d code=x -d redirect_uri=http://localhost:8190/realms/platform/broker/oneid/endpoint
```

```http
HTTP/1.1 401
Content-Type: application/json

{"error":"invalid_client","ret_cd":"1"}
```

An unknown or already used code, with the right secret:

```http
HTTP/1.1 400
Content-Type: application/json

{"error":"invalid_grant","ret_cd":"1"}
```

Identify with a token OneID does not know:

```http
HTTP/1.1 200
Content-Type: application/json

{"ret_cd":"1"}
```

The status codes and the `error` field are the mock's guesses. OneID documents `ret_cd` only; see
[open questions](architecture.md#open-questions-for-the-oneid-operator).
