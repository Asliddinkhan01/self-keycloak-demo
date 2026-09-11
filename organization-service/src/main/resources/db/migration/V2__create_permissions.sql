-- Flyway V2 for organization-service: the permissions THIS service enforces.
--
-- Same three tables as the other services, seeded with ORGANIZATION_* only.
-- The duplication is the role reference list, six rows of static data, and it
-- buys referential integrity so a typo in role_permissions fails loudly.
-- What is never duplicated is role ASSIGNMENT, which lives only in Keycloak.

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

INSERT INTO roles (code, description) VALUES
    ('JISMONIY_SHAXS', 'Jismoniy shaxs. Baseline role for every OneID account.'),
    ('YURIDIK_SHAXS',  'Yuridik shaxs vakili. Acts on behalf of an organization.'),
    ('QURUVCHI',       'Quruvchi. Creates and updates construction projects.'),
    ('BANK',           'Bank operator. Reads and records payments.'),
    ('ADMIN',          'Administrator. Manages user accounts.'),
    ('SUPER_ADMIN',    'Platform administrator. Deliberately NOT a superset of ADMIN.');

INSERT INTO permissions (code, resource, action, description) VALUES
    ('ORGANIZATION_READ',   'ORGANIZATION', 'READ',   'View organizations and memberships'),
    ('ORGANIZATION_CREATE', 'ORGANIZATION', 'CREATE', 'Register an organization'),
    ('ORGANIZATION_UPDATE', 'ORGANIZATION', 'UPDATE', 'Update an organization'),
    ('ORGANIZATION_DELETE', 'ORGANIZATION', 'DELETE', 'Remove an organization');

INSERT INTO role_permissions (role_code, permission_code) VALUES
    ('YURIDIK_SHAXS', 'ORGANIZATION_READ'),
    ('ADMIN',         'ORGANIZATION_READ'),
    ('SUPER_ADMIN',   'ORGANIZATION_READ'),
    ('SUPER_ADMIN',   'ORGANIZATION_UPDATE');
