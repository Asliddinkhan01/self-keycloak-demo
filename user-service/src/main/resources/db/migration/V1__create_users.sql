-- Flyway V1 for user-service.
--
-- Flyway applies this once, records it in user_service.flyway_schema_history,
-- and never runs it again. To change the table later, add V2__*.sql; never edit
-- a migration that has already been applied, because Flyway stores a checksum
-- and will refuse to start if one changes underneath it.
--
-- No schema qualifier is needed: spring.flyway.default-schema puts this
-- session's search_path on user_service.

CREATE TABLE users (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The join key for the whole platform. Keycloak's "sub" claim: stable,
    -- opaque, and already present on every request.
    keycloak_sub      UUID         NOT NULL UNIQUE,

    -- The OneID login. Useful for support, not sensitive, not a permanent key
    -- (a login can be changed; the PIN cannot, which is why the PIN is what
    -- Keycloak links on).
    oneid_user_id     VARCHAR(255),

    full_name         VARCHAR(255),
    first_name        VARCHAR(255),
    sur_name          VARCHAR(255),
    mid_name          VARCHAR(255),
    birth_date        DATE,

    -- OneID user_type: 'I' jismoniy shaxs, 'L' yuridik shaxs.
    user_type         VARCHAR(1),

    -- From OneID's "valid" field: whether the account reached
    -- "Tasdiqlangan foydalanuvchi" status via ERI or Mobile-ID. Gates
    -- high-value operations later; it is an assurance level, not a login check.
    identity_verified BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at     TIMESTAMPTZ
);

-- Deliberately absent: pin, pport_no, password, roles.
--
-- The PIN and passport number are sensitive personal data and this service has
-- no business storing them; the PIN stays inside Keycloak as the federated
-- identity key. Roles live in Keycloak and only in Keycloak — a local copy
-- would be a second source of truth, and it would always be the stale one.
COMMENT ON TABLE  users              IS 'Application-owned profile data. Credentials, roles and the PIN live in Keycloak.';
COMMENT ON COLUMN users.keycloak_sub IS 'Keycloak sub claim. The identity join key across all services.';

CREATE INDEX idx_users_oneid_user_id ON users (oneid_user_id);
