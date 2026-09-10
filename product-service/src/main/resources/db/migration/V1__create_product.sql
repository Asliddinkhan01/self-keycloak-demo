-- Flyway migration V1 for product-service.
--
-- This table lives in the product_service schema and is owned by the
-- product_service role. user-service has no privileges on it at all.

CREATE TABLE product (
    id          BIGSERIAL      PRIMARY KEY,
    name        VARCHAR(120)   NOT NULL,
    price       NUMERIC(10, 2) NOT NULL CHECK (price > 0),

    -- Who created it, taken from the token's preferred_username claim.
    -- A username, not a foreign key: product_service cannot see the app_user
    -- table, and in a microservice system it should not want to.
    created_by  VARCHAR(60)    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

INSERT INTO product (name, price, created_by) VALUES
    ('Keyboard',   49.90, 'system'),
    ('Monitor',   219.00, 'system'),
    ('Coffee mug',  9.50, 'system');
