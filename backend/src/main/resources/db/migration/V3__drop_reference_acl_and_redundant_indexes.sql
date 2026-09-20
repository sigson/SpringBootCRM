-- ============================================================================
-- V3: Schema optimization
--   (1) Drop the per-instance ACL (own_access) from catalogs and registers.
--       Value-level ACLs remain ONLY on the subjects of the access system
--       (users, access_roles). Catalogs are "just data"; their access is
--       governed at the type level (@TypeId defaultRepoAccess + typeFlags grants),
--       so a fat own_access VARCHAR(4000) column on every row is redundant.
--       This matches the aggregate hierarchy: AbstractReferenceAggregate and
--       Deal now extend AbstractAuditedNoAclAggregate (no ownAccess).
--
--   (2) Drop the redundant code indexes that duplicate the backing index of the
--       UNIQUE constraint (UNIQUE already creates one; a second just takes space
--       and slows writes down).
--
-- IMPORTANT: with hibernate.ddl-auto=validate this migration must be applied TOGETHER
-- with the entity mapping change (own_access is no longer mapped on catalogs), otherwise
-- schema validation will fail.
-- ============================================================================

-- ---- (1) DROP own_access on catalogs and registers ----------------------------
ALTER TABLE customers DROP COLUMN IF EXISTS own_access;
ALTER TABLE products      DROP COLUMN IF EXISTS own_access;
ALTER TABLE lead_sources         DROP COLUMN IF EXISTS own_access;
ALTER TABLE deal_stages      DROP COLUMN IF EXISTS own_access;
ALTER TABLE discounts         DROP COLUMN IF EXISTS own_access;
ALTER TABLE deals         DROP COLUMN IF EXISTS own_access;
ALTER TABLE interface_layouts    DROP COLUMN IF EXISTS own_access;
-- users / access_roles KEEP own_access (they are subjects of the access system).

-- ---- (2) DROP redundant indexes (duplicates of UNIQUE constraints) ------------
DROP INDEX IF EXISTS idx_customers_code;  -- duplicates uk_customers_code
DROP INDEX IF EXISTS idx_products_code;       -- duplicates uk_products_code
DROP INDEX IF EXISTS idx_access_roles_code;          -- duplicates UNIQUE(code) on access_roles
DROP INDEX IF EXISTS idx_users_code;                 -- duplicates UNIQUE(code) on users
DROP INDEX IF EXISTS idx_users_username;             -- duplicates UNIQUE(username) on users
DROP INDEX IF EXISTS idx_interface_layouts_code;     -- duplicates UNIQUE(code) on interface_layouts
-- The useful indexes remain: idx_users_role_id (FK), idx_deals_* (FK),
-- idx_activities_*, idx_exchange_rates_*, idx_user_discounts_*, idx_outbox_*.
