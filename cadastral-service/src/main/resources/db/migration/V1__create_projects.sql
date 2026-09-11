-- Flyway V1 for cadastral-service.

CREATE TABLE projects (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(255) NOT NULL,
    address          VARCHAR(255),
    status           VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',

    -- The acting organization, stored as a value rather than a foreign key.
    -- This service has no privileges on the organization_service schema, so a
    -- join is impossible by design. The TIN is a stable public identifier and
    -- the authoritative record lives one HTTP call away.
    organization_tin VARCHAR(9)   NOT NULL,

    -- Who created it: Keycloak's sub, taken from the verified token. Never from
    -- a request body or a header.
    created_by_sub   UUID         NOT NULL,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_organization_tin ON projects (organization_tin);

CREATE TABLE payments (
    id               UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id       UUID           NOT NULL REFERENCES projects (id),
    amount           NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    organization_tin VARCHAR(9)     NOT NULL,
    created_by_sub   UUID           NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_project_id ON payments (project_id);

-- Fixture rows for the two seeded organizations, so that a read endpoint
-- returns something before anything has been created through the API.
-- created_by_sub is the all-zero UUID, standing for "seeded, not by a person".
INSERT INTO projects (name, address, status, organization_tin, created_by_sub) VALUES
    ('Chilonzor turar-joy majmuasi', 'Toshkent, Chilonzor tumani', 'IN_PROGRESS',
     '111111111', '00000000-0000-0000-0000-000000000000'),
    ('Yunusobod savdo markazi',      'Toshkent, Yunusobod tumani', 'DRAFT',
     '111111111', '00000000-0000-0000-0000-000000000000'),
    ('Sergeli logistika markazi',    'Toshkent, Sergeli tumani',   'DRAFT',
     '222222222', '00000000-0000-0000-0000-000000000000');
