-- Flyway V2 for cadastral-service: the permissions THIS service enforces.
--
-- PROJECT_* and PAYMENT_* are defined here because this is where they are
-- checked. user-service has no opinion about what QURUVCHI may build, and
-- cadastral-service has none about who may delete a user account.

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
    ('PROJECT_READ',   'PROJECT', 'READ',   'View construction projects'),
    ('PROJECT_CREATE', 'PROJECT', 'CREATE', 'Register a construction project'),
    ('PROJECT_UPDATE', 'PROJECT', 'UPDATE', 'Update a construction project'),
    ('PROJECT_DELETE', 'PROJECT', 'DELETE', 'Remove a construction project'),
    ('PAYMENT_READ',   'PAYMENT', 'READ',   'View payments'),
    ('PAYMENT_CREATE', 'PAYMENT', 'CREATE', 'Record a payment'),
    ('PAYMENT_UPDATE', 'PAYMENT', 'UPDATE', 'Update a payment'),
    ('PAYMENT_DELETE', 'PAYMENT', 'DELETE', 'Remove a payment');

INSERT INTO role_permissions (role_code, permission_code) VALUES
    -- Anyone signed in through OneID may look at projects.
    ('JISMONIY_SHAXS', 'PROJECT_READ'),
    ('YURIDIK_SHAXS',  'PROJECT_READ'),

    -- The builder role: read, create, update. Note the absence of
    -- PROJECT_DELETE; removing a registered project is not a builder's call.
    ('QURUVCHI',       'PROJECT_READ'),
    ('QURUVCHI',       'PROJECT_CREATE'),
    ('QURUVCHI',       'PROJECT_UPDATE'),

    -- The bank sees money, not construction. It holds no PROJECT_* permission
    -- at all, which is what makes the 403 in the test scenarios meaningful.
    ('BANK',           'PAYMENT_READ'),
    ('BANK',           'PAYMENT_CREATE');
