-- ============================================================================
-- V4: Indexes on the catalog `name` column - for sorting and equality/
--     prefix filters in object lists.
--
--   Previously only `code` was indexed (implicitly via UNIQUE). Sorting by
--   `name` and predicates name = ? / name LIKE 'x%' caused full scans.
--
--   The B-Tree indexes below are valid for H2 (the current runtime). No expression,
--   functional or vendor-specific indexes are used here.
--
--   Note: case-insensitive substring search (LOWER(name) LIKE '%x%')
--   is not helped by a B-Tree at all; the main paging win comes from skipping
--   COUNT(*) on later pages (see AggregateRepository#findPageContent).
--
--   hibernate.ddl-auto=validate is unaffected: indexes do not change the set of
--   tables, columns or types.
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_customers_name ON customers (name);
CREATE INDEX IF NOT EXISTS idx_lead_sources_name         ON lead_sources (name);
CREATE INDEX IF NOT EXISTS idx_deal_stages_name      ON deal_stages (name);
CREATE INDEX IF NOT EXISTS idx_discounts_name         ON discounts (name);
-- products.name is already indexed through the uk_products_name UNIQUE constraint.
