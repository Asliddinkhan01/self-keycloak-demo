-- ---------------------------------------------------------------------------
-- One database, five schemas, five logins.
--
-- Runs ONCE, when the postgres container first creates its data directory.
-- To re-run it:  docker compose down -v && docker compose up -d
--
-- WHY SCHEMA PER SERVICE
--
-- In a microservice system each service owns its tables. Nothing reads another
-- service's data directly; they talk over HTTP. A schema per service gives
-- every service its own namespace and its own credentials while keeping a
-- single connection endpoint and a single backup.
--
-- The isolation below is enforced by PostgreSQL privileges, not by convention.
-- Each role owns exactly one schema and is never granted USAGE on the others,
-- so cadastral_service literally cannot read user_service.users. The README
-- shows the psql command that proves it.
-- ---------------------------------------------------------------------------

-- ---- logins ---------------------------------------------------------------
-- Trivial passwords on purpose: this is a local learning environment. In any
-- shared environment these come from the secret store, never from a file.
CREATE ROLE keycloak             LOGIN PASSWORD 'keycloak';
CREATE ROLE user_service         LOGIN PASSWORD 'user_service';
CREATE ROLE organization_service LOGIN PASSWORD 'organization_service';
CREATE ROLE cadastral_service    LOGIN PASSWORD 'cadastral_service';
CREATE ROLE mock_oneid           LOGIN PASSWORD 'mock_oneid';

GRANT CONNECT ON DATABASE appdb TO
    keycloak, user_service, organization_service, cadastral_service, mock_oneid;

-- ---- schemas --------------------------------------------------------------
-- AUTHORIZATION makes the role the OWNER, which is what lets each service
-- create its own tables (Flyway for the Spring services, Liquibase for
-- Keycloak) without any further grants.
CREATE SCHEMA keycloak             AUTHORIZATION keycloak;
CREATE SCHEMA user_service         AUTHORIZATION user_service;
CREATE SCHEMA organization_service AUTHORIZATION organization_service;
CREATE SCHEMA cadastral_service    AUTHORIZATION cadastral_service;
CREATE SCHEMA mock_oneid           AUTHORIZATION mock_oneid;

-- ---- isolation ------------------------------------------------------------
-- No role is granted USAGE on a schema it does not own, so cross-schema reads
-- are refused by the database itself. Nothing further is needed: a schema is
-- visible only to its owner and to superusers unless USAGE is given.
--
-- Also stop anyone creating objects in the default "public" schema, so a table
-- cannot accidentally land outside a service's namespace.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- ---- convenience ----------------------------------------------------------
-- Each role's search_path points at its own schema, so an unqualified table
-- name resolves correctly in a plain psql session.
ALTER ROLE keycloak             SET search_path = keycloak;
ALTER ROLE user_service         SET search_path = user_service;
ALTER ROLE organization_service SET search_path = organization_service;
ALTER ROLE cadastral_service    SET search_path = cadastral_service;
ALTER ROLE mock_oneid           SET search_path = mock_oneid;

-- ---- extensions -----------------------------------------------------------
-- gen_random_uuid() lives in pgcrypto before PostgreSQL 13 and in core after,
-- but creating the extension keeps the migrations portable either way.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
