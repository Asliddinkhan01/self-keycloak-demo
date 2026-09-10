-- Flyway migration V1 for user-service.
--
-- Flyway runs this once, records it in user_service.flyway_schema_history, and
-- never runs it again. To change the table later you add V2__*.sql; you never
-- edit a migration that has already been applied.
--
-- No schema qualifier is needed on the table name: spring.flyway.default-schema
-- puts this session's search_path on user_service.

CREATE TABLE app_user (
    id          BIGSERIAL     PRIMARY KEY,

    -- The link between Keycloak and this service.
    -- It holds the "sub" claim of the access token, which is the stable,
    -- immutable Keycloak user id. Usernames and e-mail addresses can change;
    -- sub cannot. Nullable because the demo rows are seeded before anyone has
    -- logged in, and get linked on that user's first call to /api/users/me.
    keycloak_id VARCHAR(36)   UNIQUE,

    username    VARCHAR(60)   NOT NULL UNIQUE,
    full_name   VARCHAR(120),
    email       VARCHAR(160),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  app_user             IS 'Application-owned profile data. Credentials and roles live in Keycloak, never here.';
COMMENT ON COLUMN app_user.keycloak_id IS 'The "sub" claim of the access token.';

-- Seed the three demo accounts so GET /api/users returns something before
-- anyone logs in. Note what is NOT stored: no password, no password hash, and
-- no roles. Keycloak owns all three.
INSERT INTO app_user (username, full_name, email) VALUES
    ('user',   'Ursula User', 'user@demo.local'),
    ('admin',  'Adam Admin',  'admin@demo.local'),
    ('nobody', 'Nora Nobody', 'nobody@demo.local');
