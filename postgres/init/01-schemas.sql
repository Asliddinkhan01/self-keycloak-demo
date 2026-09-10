-- ---------------------------------------------------------------------------
-- One database, three schemas, three logins.
--
-- This script runs ONCE, the first time the postgres container creates its
-- data directory. To re-run it: docker compose down -v && docker compose up -d
--
-- WHY SCHEMAS AND NOT THREE DATABASES?
--
-- In a microservice system each service must own its tables: no service reads
-- another's data directly, they talk over HTTP. There are three usual ways to
-- get that:
--
--   1. one database server per service   most isolation, most to operate
--   2. one server, one database per service
--   3. one server, one database, one SCHEMA per service   <-- this project
--
-- Option 3 keeps a single connection endpoint and a single backup, while still
-- giving every service its own namespace and its own credentials. It is a very
-- common real-world compromise, and it is the easiest to inspect while you are
-- learning: one psql session can show you all three schemas side by side.
--
-- The isolation here is enforced by PostgreSQL privileges, not by convention.
-- Each role owns exactly one schema and is never granted USAGE on the others,
-- so product_service literally cannot read user_service.app_user. Try it: the
-- README shows the exact psql command and the "permission denied" it returns.
-- ---------------------------------------------------------------------------

-- ---- logins ---------------------------------------------------------------
-- Passwords are trivial on purpose; this is a local learning environment.
CREATE ROLE keycloak        LOGIN PASSWORD 'keycloak';
CREATE ROLE user_service    LOGIN PASSWORD 'user_service';
CREATE ROLE product_service LOGIN PASSWORD 'product_service';

GRANT CONNECT ON DATABASE appdb TO keycloak, user_service, product_service;

-- ---- schemas --------------------------------------------------------------
-- AUTHORIZATION makes the role the OWNER, which is what lets each service
-- create its own tables (Flyway migrations for the two Spring services,
-- Liquibase for Keycloak) without any further grants.
CREATE SCHEMA keycloak        AUTHORIZATION keycloak;
CREATE SCHEMA user_service    AUTHORIZATION user_service;
CREATE SCHEMA product_service AUTHORIZATION product_service;

-- ---- isolation ------------------------------------------------------------
-- No role is granted USAGE on a schema it does not own, so cross-schema reads
-- are refused by the database itself. Nothing further is needed for that: a
-- schema is only visible to its owner and to superusers unless USAGE is given.
--
-- Also stop anyone from creating objects in the default "public" schema, so
-- tables cannot accidentally land outside a service's namespace.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- ---- convenience ----------------------------------------------------------
-- Give every role a search_path pointing at its own schema, so an unqualified
-- table name resolves correctly even in a plain psql session.
ALTER ROLE keycloak        SET search_path = keycloak;
ALTER ROLE user_service    SET search_path = user_service;
ALTER ROLE product_service SET search_path = product_service;
