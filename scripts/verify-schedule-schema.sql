-- ==============================================================================
-- NeoBank Schedule Service -- Live PostgreSQL Schema & Privileges Verification
-- ==============================================================================
\set ON_ERROR_STOP on

\echo '======================================================================'
-- 1. FLYWAY MIGRATION STATUS (Database: neobank_schedule)
\echo '======================================================================'
SELECT installed_rank, version, description, type, script, installed_by, installed_on, execution_time, success
FROM flyway_schema_history
ORDER BY installed_rank;

\echo '======================================================================'
-- 2. PUBLIC SCHEMA BASE TABLES
\echo '======================================================================'
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;

\echo '======================================================================'
-- 3. KEY CONSTRAINTS VERIFICATION
\echo '======================================================================'
SELECT
    conrelid::regclass AS table_name,
    conname AS constraint_name,
    CASE contype
        WHEN 'u' THEN 'UNIQUE'
        WHEN 'c' THEN 'CHECK'
        WHEN 'p' THEN 'PRIMARY KEY'
        WHEN 'f' THEN 'FOREIGN KEY'
        ELSE contype::text
    END AS constraint_type,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
  AND (conrelid IN ('scheduled_transfers'::regclass, 'scheduled_occurrences'::regclass))
ORDER BY table_name, constraint_name;

\echo '======================================================================'
-- 4. INDEXES VERIFICATION
\echo '======================================================================'
SELECT
    tablename AS table_name,
    indexname AS index_name,
    indexdef AS index_definition
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN ('scheduled_transfers', 'scheduled_occurrences')
ORDER BY tablename, indexname;

\echo '======================================================================'
-- 5. RUNTIME PRIVILEGES FOR neobank_schedule_app
\echo '======================================================================'
SELECT
    c.relname AS table_name,
    has_table_privilege('neobank_schedule_app', c.oid, 'SELECT') AS can_select,
    has_table_privilege('neobank_schedule_app', c.oid, 'INSERT') AS can_insert,
    has_table_privilege('neobank_schedule_app', c.oid, 'UPDATE') AS can_update,
    has_table_privilege('neobank_schedule_app', c.oid, 'DELETE') AS can_delete
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
ORDER BY c.relname;

\echo '======================================================================'
-- 6. SEQUENCE PRIVILEGES FOR neobank_schedule_app
\echo '======================================================================'
SELECT
    c.relname AS sequence_name,
    has_sequence_privilege('neobank_schedule_app', c.oid, 'USAGE') AS can_usage,
    has_sequence_privilege('neobank_schedule_app', c.oid, 'SELECT') AS can_select,
    has_sequence_privilege('neobank_schedule_app', c.oid, 'UPDATE') AS can_update
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'S'
ORDER BY c.relname;

\echo '======================================================================'
-- 7. SCHEMA DDL PERMISSION (neobank_schedule_app)
\echo '======================================================================'
SELECT has_schema_privilege('neobank_schedule_app', 'public', 'CREATE') AS has_schema_create_ddl;
