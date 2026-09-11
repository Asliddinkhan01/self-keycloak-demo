-- Flyway V1 for organization-service.
--
-- These two tables are the source of truth for "may this person act on behalf
-- of this organization". The org_tins claim in the token only SEEDS them; it
-- never makes the authorization decision. That separation is what lets an
-- administrator grant a membership OneID knows nothing about, and lets one be
-- revoked without waiting for a token to expire.

CREATE TABLE organizations (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- STIR, the taxpayer identification number. Nine digits, and the identifier
    -- OneID uses for a legal entity in legal_info[].tin.
    tin        VARCHAR(9)   NOT NULL UNIQUE,

    name       VARCHAR(255) NOT NULL,
    short_name VARCHAR(120),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_organizations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Keycloak's sub, not a foreign key to user_service.users: this service has
    -- no privileges on that schema, and in a microservice system it should not.
    keycloak_sub    UUID        NOT NULL,

    organization_id UUID        NOT NULL REFERENCES organizations (id),

    -- From OneID legal_info[].is_basic: the entity the person selected there.
    -- A preference, not an authorization fact.
    is_basic        BOOLEAN     NOT NULL DEFAULT FALSE,

    -- ONEID for memberships reconciled from the token, MANUAL for ones an
    -- administrator granted. Keeping the provenance means a OneID
    -- reconciliation can deactivate what it created without touching the rest.
    source          VARCHAR(16) NOT NULL DEFAULT 'ONEID',

    -- Deactivated rather than deleted when an entity disappears from OneID, so
    -- that historical records still resolve their organization.
    active          BOOLEAN     NOT NULL DEFAULT TRUE,

    linked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_user_organization UNIQUE (keycloak_sub, organization_id)
);

-- Hit on every organization-scoped request, so it earns an index.
CREATE INDEX idx_user_organizations_sub ON user_organizations (keycloak_sub);

-- Fixture organizations matching the development users in realm-export.json.
-- Memberships are NOT seeded: they are keyed on a Keycloak sub that only exists
-- after the realm import, so they are reconciled at login instead.
INSERT INTO organizations (tin, name, short_name) VALUES
    ('111111111', '"IT-GROUP" mas''uliyati cheklangan jamiyati', 'IT-GROUP'),
    ('222222222', '"QURILISH SAVDO" mas''uliyati cheklangan jamiyati', 'QURILISH SAVDO'),
    ('333333333', '"MILLIY BANK" aksiyadorlik jamiyati', 'MILLIY BANK'),
    ('444444444', '"BEGONA TASHKILOT" mas''uliyati cheklangan jamiyati', 'BEGONA TASHKILOT');

-- 444444444 exists but nobody is a member of it. It is the organization used to
-- prove that a forged X-Organization-TIN is refused with 403.
COMMENT ON TABLE user_organizations IS 'Source of truth for acting-on-behalf-of. Validated on every org-scoped request.';
