-- Flyway V2 for user-service: the permissions THIS service enforces.
--
-- Three tables, and it matters which one is missing.
--
--   roles             reference data: the role codes Keycloak can issue,
--                     with descriptions, so role_permissions has something to
--                     point at and a typo becomes a constraint violation
--                     instead of a silently ineffective grant.
--
--   permissions       the capabilities this service checks.
--
--   role_permissions  which roles confer them. Editable, versioned, reviewable.
--
-- There is NO user_roles table, in this service or any other. Role ASSIGNMENT
-- lives in Keycloak and only in Keycloak. Mirroring it here would create a
-- second source of truth, and the local copy would always be the stale one.
--
-- Note also that this service seeds only USER_* and PLATFORM_ADMIN. PROJECT_*
-- and ORGANIZATION_* belong to the services that enforce them. A permission is
-- only meaningful where it is checked.

CREATE TABLE roles (
    code        VARCHAR(64)  PRIMARY KEY,
    description VARCHAR(255)
);

CREATE TABLE permissions (
    code        VARCHAR(64)  PRIMARY KEY,
    resource    VARCHAR(32)  NOT NULL,
    action      VARCHAR(16)  NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE role_permissions (
    role_code       VARCHAR(64) NOT NULL REFERENCES roles (code),
    permission_code VARCHAR(64) NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_code, permission_code)
);

-- The six business roles, matching the realm roles in realm-export.json.
INSERT INTO roles (code, description) VALUES
    ('JISMONIY_SHAXS', 'Jismoniy shaxs. Baseline role for every OneID account.'),
    ('YURIDIK_SHAXS',  'Yuridik shaxs vakili. Acts on behalf of an organization.'),
    ('QURUVCHI',       'Quruvchi. Creates and updates construction projects.'),
    ('BANK',           'Bank operator. Reads and records payments.'),
    ('ADMIN',          'Administrator. Manages user accounts.'),
    ('SUPER_ADMIN',    'Platform administrator. Deliberately NOT a superset of ADMIN.');

INSERT INTO permissions (code, resource, action, description) VALUES
    ('USER_READ',      'USER',     'READ',   'List and view user profiles'),
    ('USER_CREATE',    'USER',     'CREATE', 'Create a user profile'),
    ('USER_UPDATE',    'USER',     'UPDATE', 'Update a user profile'),
    ('USER_DELETE',    'USER',     'DELETE', 'Delete a user profile'),
    ('PLATFORM_ADMIN', 'PLATFORM', 'ADMIN',  'Platform-level administration');

INSERT INTO role_permissions (role_code, permission_code) VALUES
    ('ADMIN',       'USER_READ'),
    ('ADMIN',       'USER_UPDATE'),
    ('ADMIN',       'USER_DELETE'),

    -- SUPER_ADMIN holds PLATFORM_ADMIN, which no other role has, and does NOT
    -- hold USER_DELETE, which ADMIN does. Neither role contains the other.
    -- That is deliberate: it means no role in this platform is quietly a
    -- wildcard, and the two admin endpoints prove it in both directions.
    ('SUPER_ADMIN', 'PLATFORM_ADMIN'),
    ('SUPER_ADMIN', 'USER_READ'),
    ('SUPER_ADMIN', 'USER_UPDATE');
