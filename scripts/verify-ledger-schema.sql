-- ==============================================================================
-- NeoBank Ledger Service -- Live PostgreSQL Schema & Privileges Verification
-- ==============================================================================
\set ON_ERROR_STOP on

\echo '======================================================================'
\echo '1. FLYWAY MIGRATION STATUS (Database: neobank_ledger)'
\echo '======================================================================'
SELECT installed_rank, version, description, type, script, installed_by, installed_on, execution_time, success
FROM flyway_schema_history
ORDER BY installed_rank;

\echo '======================================================================'
\echo '2. PUBLIC SCHEMA BASE TABLES'
\echo '======================================================================'
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;

\echo '======================================================================'
\echo '3. KEY FINANCIAL CONSTRAINTS VERIFICATION'
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
  AND (conname IN ('uk_ledger_tx_transfer_id', 'chk_ledger_account_balance')
       OR conrelid = 'ledger_executions'::regclass
       OR (conrelid = 'ledger_entries'::regclass AND contype = 'f'))
ORDER BY table_name, constraint_name;

\echo '======================================================================'
\echo '4. RUNTIME PRIVILEGES FOR neobank_ledger_app'
\echo '======================================================================'
SELECT
    c.relname AS table_name,
    has_table_privilege('neobank_ledger_app', c.oid, 'SELECT') AS can_select,
    has_table_privilege('neobank_ledger_app', c.oid, 'INSERT') AS can_insert,
    has_table_privilege('neobank_ledger_app', c.oid, 'UPDATE') AS can_update,
    has_table_privilege('neobank_ledger_app', c.oid, 'DELETE') AS can_delete
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
ORDER BY c.relname;

\echo '======================================================================'
\echo '5. SCHEMA DDL PERMISSION (neobank_ledger_app)'
\echo '======================================================================'
SELECT has_schema_privilege('neobank_ledger_app', 'public', 'CREATE') AS has_schema_create_ddl;
