-- ============================================================================
-- V2: Generated data log (the "Data generation" admin utility).
--
-- Makes the purge reliable and type-independent: every record created by
-- the generator is logged as a (type_id, record_id) pair. Unlike a
-- marker in the name, this also works for registers without string fields
-- (e.g. Deal has only a code, ExchangeRate only a short currency).
--
-- The table is NOT a business aggregate: no @TypeId, no UI metadata, outside
-- the data model. A plain technical table managed by Flyway.
-- ============================================================================
CREATE TABLE data_gen_log (
    id          UUID         NOT NULL,
    type_id     BIGINT       NOT NULL,
    record_id   VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_data_gen_log PRIMARY KEY (id)
);

-- Fast selection/grouping by type during the purge.
CREATE INDEX ix_data_gen_log_type ON data_gen_log (type_id);
