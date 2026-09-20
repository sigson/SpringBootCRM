-- ============================================================================
-- V5: Composite (sort_col, id) indexes for index-only deep paging of lists.
--
--   PROBLEM (deep offset paging).
--   Catalog lists are sorted by default with `ORDER BY code ASC, id ASC`
--   (id is a stable tiebreaker, always added by SqlRowFilter, otherwise
--   offset paging is non-deterministic). Only an index on `code` existed
--   (backing the UNIQUE constraint) OR on `name` - WITHOUT a trailing `id`.
--
--   Without the trailing `id` the database (H2, embedded in the JVM) cannot
--   produce the `code, id` order from the index alone: it SORTS the result. On
--   the "last page" of a million-row table (`LIMIT 500 OFFSET ~999500`)
--   that means materializing and sorting WIDE rows (all columns) on the
--   JVM heap - hence the RSS spike (~500MB->1GB) and the CPU burst while
--   loading the last page.
--
--   SOLUTION.
--   A composite B-Tree index `(sort_col, id)` matches EXACTLY the order
--   `ORDER BY sort_col, id`. H2 walks the index in that order,
--   skips `OFFSET` entries and takes `LIMIT` - with NO sort and no
--   materialization of full rows before the offset (heavy rows are fetched only for
--   the final <=500). Memory and CPU stop depending on page depth.
--
--   THE SAME INDEX serves KEYSET (seek) paging: the predicate
--   `(sort_col, id) > (afterValue, afterId)` is an index range scan from the anchor,
--   at constant cost for any depth (see ObjectRowQueryService#paged,
--   SqlRowFilter#keysetSpec). Sequential paging through millions of rows uses seek;
--   a "cold" jump to an arbitrary point uses an offset over the same index.
--
--   COVERAGE. All types whose lists sort by code/name (the catalogs).
--   Registers (exchange_rates, user_discounts) and activities have no
--   code/name - their default order is id (the PK, already indexed), so
--   they are not touched here.
--
--   COMPATIBILITY. These are pure indexes: the set of tables/columns/types is unchanged,
--   so hibernate.ddl-auto=validate is unaffected. Filters, quick search
--   (per column and global) and arbitrary multi-column sorting
--   work as before - the index only speeds up the MOST COMMON orders.
--
--   NOTE. The older single-column name indexes (V4) are kept: they are
--   useful for equality/prefix predicates `name = ?` / `name LIKE 'x%'`.
--   A substring `LOWER(col) LIKE '%x%'` is not helped by a B-Tree at all
--   (that is a property of the operator, not the schema) - but such queries return few
--   rows and are not a deep-paging scenario.
-- ============================================================================

-- ---- Catalogs: (code, id) - the main default list order ---------------------
CREATE INDEX IF NOT EXISTS idx_customers_code_id ON customers (code, id);
CREATE INDEX IF NOT EXISTS idx_products_code_id      ON products (code, id);
CREATE INDEX IF NOT EXISTS idx_lead_sources_code_id         ON lead_sources (code, id);
CREATE INDEX IF NOT EXISTS idx_deal_stages_code_id      ON deal_stages (code, id);
CREATE INDEX IF NOT EXISTS idx_discounts_code_id         ON discounts (code, id);
CREATE INDEX IF NOT EXISTS idx_deals_code_id         ON deals (code, id);
CREATE INDEX IF NOT EXISTS idx_access_roles_code_id         ON access_roles (code, id);
CREATE INDEX IF NOT EXISTS idx_interface_layouts_code_id    ON interface_layouts (code, id);
CREATE INDEX IF NOT EXISTS idx_users_code_id                ON users (code, id);

-- ---- Catalogs: (name, id) - a common alternative order (sort by name) -------
CREATE INDEX IF NOT EXISTS idx_customers_name_id ON customers (name, id);
CREATE INDEX IF NOT EXISTS idx_products_name_id      ON products (name, id);
CREATE INDEX IF NOT EXISTS idx_lead_sources_name_id         ON lead_sources (name, id);
CREATE INDEX IF NOT EXISTS idx_deal_stages_name_id      ON deal_stages (name, id);
CREATE INDEX IF NOT EXISTS idx_discounts_name_id         ON discounts (name, id);
CREATE INDEX IF NOT EXISTS idx_access_roles_name_id         ON access_roles (name, id);
CREATE INDEX IF NOT EXISTS idx_interface_layouts_name_id    ON interface_layouts (name, id);
CREATE INDEX IF NOT EXISTS idx_users_name_id                ON users (name, id);
