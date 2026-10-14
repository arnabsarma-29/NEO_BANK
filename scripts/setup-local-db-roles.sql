-- ==============================================================================
-- NeoBank Local PostgreSQL Role & Privilege Provisioning Script
-- ==============================================================================
-- Purpose:
--   Provisions dedicated schema-migration roles and least-privilege runtime
--   application roles for NeoBank services on PostgreSQL 17.11.
--
-- Databases and Roles Provisioned:
--   1. Database: neobank_ledger
--      - neobank_ledger_migrator (Migration role: owns DDL, executes Flyway)
--      - neobank_ledger_app      (Runtime role: DML only, no DDL, no DELETE)
--   2. Database: neobank_account
--      - neobank_account_migrator (Migration role: owns DDL, executes Flyway)
--      - neobank_account_app      (Runtime role: DML only, no DDL, no DELETE)
--   3. Database: neobank_transfer
--      - neobank_transfer_migrator (Migration role: owns DDL, executes Flyway)
--      - neobank_transfer_app      (Runtime role: DML only, no DDL, no DELETE)
--   4. Database: neobank_schedule
--      - neobank_schedule_migrator (Migration role: owns DDL, executes Flyway)
--      - neobank_schedule_app      (Runtime role: DML only, no DDL, no DELETE)
--
-- Safety Guarantees:
--   - Non-destructive: Does NOT drop databases, roles, tables, or existing data.
--   - Safe role creation: Uses IF NOT EXISTS blocks to avoid duplicate errors.
--   - Interactive masked password prompt: Uses PostgreSQL's native '\password'
--     command exclusively. No passwords in script, CLI arguments, or psql variables.
--   - Least-Privilege enforcement:
--     * Schema DDL (CREATE/ALTER/DROP) is restricted to the migration roles.
--     * Runtime roles receive USAGE on schema public with CREATE explicitly revoked.
--     * Default table privileges grant ONLY the minimum common permission (SELECT).
--       Default privileges strictly DO NOT grant UPDATE or INSERT or DELETE on future tables.
--     * Default sequence privileges grant USAGE, SELECT only. UPDATE on sequences
--       (required only for setval()) is strictly omitted and revoked.
--     * Each Flyway migration script explicitly grants table-specific permissions:
--       - ledger_accounts: SELECT, INSERT, UPDATE
--       - ledger_transactions: SELECT, INSERT (IMMUTABLE: no UPDATE, no DELETE)
--       - ledger_entries: SELECT, INSERT (IMMUTABLE: no UPDATE, no DELETE)
--       - outbox_events: SELECT, INSERT, UPDATE (no DELETE)
--       - flyway_schema_history: SELECT only (runtime cannot modify migration history)
--
-- Error Handling:
--   Halt execution immediately on any failure.
-- ==============================================================================

\set ON_ERROR_STOP on

\echo '======================================================================'
\echo 'NeoBank Local Database Role Provisioning'
\echo 'Affected databases and roles:'
\echo '  1. neobank_ledger   -> neobank_ledger_migrator,   neobank_ledger_app'
\echo '  2. neobank_account  -> neobank_account_migrator,  neobank_account_app'
\echo '  3. neobank_transfer -> neobank_transfer_migrator, neobank_transfer_app'
\echo '  4. neobank_schedule -> neobank_schedule_migrator, neobank_schedule_app'
\echo '======================================================================'

--------------------------------------------------------------------------------
-- 1. LEDGER SERVICE (Database: neobank_ledger)
--------------------------------------------------------------------------------
\echo '----------------------------------------------------------------------'
\echo 'Configuring roles and privileges for Ledger Service (neobank_ledger)...'
\echo '----------------------------------------------------------------------'

-- 1.1 Safe conditional role creation (Idempotent, non-destructive)
DO
$do$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'neobank_ledger_migrator') THEN
      CREATE ROLE neobank_ledger_migrator WITH LOGIN;
      RAISE NOTICE 'Created role: neobank_ledger_migrator';
   ELSE
      RAISE NOTICE 'Role already exists: neobank_ledger_migrator';
   END IF;

   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'neobank_ledger_app') THEN
      CREATE ROLE neobank_ledger_app WITH LOGIN;
      RAISE NOTICE 'Created role: neobank_ledger_app';
   ELSE
      RAISE NOTICE 'Role already exists: neobank_ledger_app';
   END IF;
END
$do$;

-- 1.2 Set passwords interactively via native masked prompt
\echo 'Prompting masked password for neobank_ledger_migrator (schema migration role):'
\password neobank_ledger_migrator

\echo 'Prompting masked password for neobank_ledger_app (runtime application role):'
\password neobank_ledger_app

-- 1.3 Database connection grants
GRANT CONNECT ON DATABASE neobank_ledger TO neobank_ledger_migrator;
GRANT CONNECT ON DATABASE neobank_ledger TO neobank_ledger_app;

-- 1.4 Switch to neobank_ledger database to configure schema and object privileges
\c neobank_ledger

-- Schema privileges:
-- Migration Role: full DDL permissions within public schema
GRANT USAGE, CREATE ON SCHEMA public TO neobank_ledger_migrator;

-- Runtime Application Role: USAGE only on schema public (NO CREATE privilege = NO DDL permissions)
GRANT USAGE ON SCHEMA public TO neobank_ledger_app;
REVOKE CREATE ON SCHEMA public FROM neobank_ledger_app;

-- 1.5 Configure Default Privileges for future tables and sequences
-- Explicitly revoke broad/mutation permissions from default table privileges:
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_ledger_migrator IN SCHEMA public
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM neobank_ledger_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM neobank_ledger_app;

-- Revoke UPDATE from default sequence privileges (setval() not permitted for runtime role):
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_ledger_migrator IN SCHEMA public
    REVOKE UPDATE ON SEQUENCES FROM neobank_ledger_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE UPDATE ON SEQUENCES FROM neobank_ledger_app;

-- Minimum common permissions by default:
-- Tables: SELECT only (mutations established explicitly per table by Flyway migrations)
-- Sequences: USAGE, SELECT only for ID generation (nextval, currval)
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_ledger_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO neobank_ledger_app;
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_ledger_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO neobank_ledger_app;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT ON TABLES TO neobank_ledger_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO neobank_ledger_app;

-- 1.6 Apply least-privilege, table-specific permissions to existing tables (if already migrated)
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM neobank_ledger_app;

DO
$do$
BEGIN
   -- 1. ledger_accounts: SELECT, INSERT, UPDATE (balance mutations, provisioning; NO DELETE)
   IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'ledger_accounts') THEN
      EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE ledger_accounts TO neobank_ledger_app';
      RAISE NOTICE 'Table ledger_accounts: Granted SELECT, INSERT, UPDATE (NO DELETE)';
   END IF;

   -- 2. ledger_transactions: SELECT, INSERT only (IMMUTABLE - NO UPDATE, NO DELETE)
   IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'ledger_transactions') THEN
      EXECUTE 'GRANT SELECT, INSERT ON TABLE ledger_transactions TO neobank_ledger_app';
      RAISE NOTICE 'Table ledger_transactions: Granted SELECT, INSERT (IMMUTABLE - NO UPDATE, NO DELETE)';
   END IF;

   -- 3. ledger_entries: SELECT, INSERT only (IMMUTABLE - NO UPDATE, NO DELETE)
   IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'ledger_entries') THEN
      EXECUTE 'GRANT SELECT, INSERT ON TABLE ledger_entries TO neobank_ledger_app';
      RAISE NOTICE 'Table ledger_entries: Granted SELECT, INSERT (IMMUTABLE - NO UPDATE, NO DELETE)';
   END IF;

   -- 4. outbox_events: SELECT, INSERT, UPDATE (polling, insertion, status updates; NO DELETE)
   IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'outbox_events') THEN
      EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE outbox_events TO neobank_ledger_app';
      RAISE NOTICE 'Table outbox_events: Granted SELECT, INSERT, UPDATE (NO DELETE)';
   END IF;

   -- 5. flyway_schema_history: Strictly read-only for runtime role (NO INSERT, NO UPDATE, NO DELETE)
   IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'flyway_schema_history') THEN
      EXECUTE 'REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE flyway_schema_history FROM neobank_ledger_app';
      EXECUTE 'GRANT SELECT ON TABLE flyway_schema_history TO neobank_ledger_app';
      RAISE NOTICE 'Table flyway_schema_history: Ensured read-only (SELECT only) for neobank_ledger_app';
   END IF;
END
$do$;

-- Sequences: USAGE and SELECT only for ID generation (nextval, currval); no UPDATE (setval prohibited)
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM neobank_ledger_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO neobank_ledger_app;

\echo 'Ledger Service roles and privileges successfully configured.'

--------------------------------------------------------------------------------
-- 2. ACCOUNT SERVICE (Database: neobank_account)
--------------------------------------------------------------------------------
\echo '----------------------------------------------------------------------'
\echo 'Configuring roles and privileges for Account Service (neobank_account)...'
\echo '----------------------------------------------------------------------'

DO
$do$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'neobank_account_migrator') THEN
      CREATE ROLE neobank_account_migrator WITH LOGIN;
      RAISE NOTICE 'Created role: neobank_account_migrator';
   ELSE
      RAISE NOTICE 'Role already exists: neobank_account_migrator';
   END IF;

   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'neobank_account_app') THEN
      CREATE ROLE neobank_account_app WITH LOGIN;
      RAISE NOTICE 'Created role: neobank_account_app';
   ELSE
      RAISE NOTICE 'Role already exists: neobank_account_app';
   END IF;
END
$do$;

\echo 'Prompting masked password for neobank_account_migrator (schema migration role):'
\password neobank_account_migrator

\echo 'Prompting masked password for neobank_account_app (runtime application role):'
\password neobank_account_app

GRANT CONNECT ON DATABASE neobank_account TO neobank_account_migrator;
GRANT CONNECT ON DATABASE neobank_account TO neobank_account_app;

\c neobank_account

GRANT USAGE, CREATE ON SCHEMA public TO neobank_account_migrator;
GRANT USAGE ON SCHEMA public TO neobank_account_app;
REVOKE CREATE ON SCHEMA public FROM neobank_account_app;

-- Default privileges: minimum common permissions (SELECT on tables, USAGE/SELECT on sequences)
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_account_migrator IN SCHEMA public
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM neobank_account_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM neobank_account_app;

ALTER DEFAULT PRIVILEGES FOR ROLE neobank_account_migrator IN SCHEMA public
    REVOKE UPDATE ON SEQUENCES FROM neobank_account_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE UPDATE ON SEQUENCES FROM neobank_account_app;

ALTER DEFAULT PRIVILEGES FOR ROLE neobank_account_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO neobank_account_app;
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_account_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO neobank_account_app;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT ON TABLES TO neobank_account_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO neobank_account_app;

-- Existing tables and sequences:
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM neobank_account_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO neobank_account_app;

REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM neobank_account_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO neobank_account_app;

\echo 'Account Service roles and privileges successfully configured.'

--------------------------------------------------------------------------------
-- 3. TRANSFER SERVICE (Database: neobank_transfer)
--------------------------------------------------------------------------------
\echo '----------------------------------------------------------------------'
\echo 'Configuring roles and privileges for Transfer Service (neobank_transfer)...'
\echo '----------------------------------------------------------------------'

DO
$do$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'neobank_transfer_migrator') THEN
      CREATE ROLE neobank_transfer_migrator WITH LOGIN;
      RAISE NOTICE 'Created role: neobank_transfer_migrator';
   ELSE
      RAISE NOTICE 'Role already exists: neobank_transfer_migrator';
   END IF;

   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'neobank_transfer_app') THEN
      CREATE ROLE neobank_transfer_app WITH LOGIN;
      RAISE NOTICE 'Created role: neobank_transfer_app';
   ELSE
      RAISE NOTICE 'Role already exists: neobank_transfer_app';
   END IF;
END
$do$;

\echo 'Prompting masked password for neobank_transfer_migrator (schema migration role):'
\password neobank_transfer_migrator

\echo 'Prompting masked password for neobank_transfer_app (runtime application role):'
\password neobank_transfer_app

GRANT CONNECT ON DATABASE neobank_transfer TO neobank_transfer_migrator;
GRANT CONNECT ON DATABASE neobank_transfer TO neobank_transfer_app;

\c neobank_transfer

GRANT USAGE, CREATE ON SCHEMA public TO neobank_transfer_migrator;
GRANT USAGE ON SCHEMA public TO neobank_transfer_app;
REVOKE CREATE ON SCHEMA public FROM neobank_transfer_app;

-- Default privileges: minimum common permissions (SELECT on tables, USAGE/SELECT on sequences)
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_transfer_migrator IN SCHEMA public
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM neobank_transfer_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM neobank_transfer_app;

ALTER DEFAULT PRIVILEGES FOR ROLE neobank_transfer_migrator IN SCHEMA public
    REVOKE UPDATE ON SEQUENCES FROM neobank_transfer_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE UPDATE ON SEQUENCES FROM neobank_transfer_app;

ALTER DEFAULT PRIVILEGES FOR ROLE neobank_transfer_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO neobank_transfer_app;
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_transfer_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO neobank_transfer_app;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT ON TABLES TO neobank_transfer_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO neobank_transfer_app;

-- Existing tables and sequences:
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM neobank_transfer_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO neobank_transfer_app;

REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM neobank_transfer_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO neobank_transfer_app;

\echo 'Transfer Service roles and privileges successfully configured.'

--------------------------------------------------------------------------------
-- 4. SCHEDULE SERVICE (Database: neobank_schedule)
--------------------------------------------------------------------------------
\echo '----------------------------------------------------------------------'
\echo 'Configuring roles and privileges for Schedule Service (neobank_schedule)...'
\echo '----------------------------------------------------------------------'

DO
$do$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'neobank_schedule_migrator') THEN
      CREATE ROLE neobank_schedule_migrator WITH LOGIN;
      RAISE NOTICE 'Created role: neobank_schedule_migrator';
   ELSE
      RAISE NOTICE 'Role already exists: neobank_schedule_migrator';
   END IF;

   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'neobank_schedule_app') THEN
      CREATE ROLE neobank_schedule_app WITH LOGIN;
      RAISE NOTICE 'Created role: neobank_schedule_app';
   ELSE
      RAISE NOTICE 'Role already exists: neobank_schedule_app';
   END IF;
END
$do$;

\echo 'Prompting masked password for neobank_schedule_migrator (schema migration role):'
\password neobank_schedule_migrator

\echo 'Prompting masked password for neobank_schedule_app (runtime application role):'
\password neobank_schedule_app

GRANT CONNECT ON DATABASE neobank_schedule TO neobank_schedule_migrator;
GRANT CONNECT ON DATABASE neobank_schedule TO neobank_schedule_app;

\c neobank_schedule

GRANT USAGE, CREATE ON SCHEMA public TO neobank_schedule_migrator;
GRANT USAGE ON SCHEMA public TO neobank_schedule_app;
REVOKE CREATE ON SCHEMA public FROM neobank_schedule_app;

-- Default privileges: minimum common permissions (SELECT on tables, USAGE/SELECT on sequences)
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_schedule_migrator IN SCHEMA public
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM neobank_schedule_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM neobank_schedule_app;

ALTER DEFAULT PRIVILEGES FOR ROLE neobank_schedule_migrator IN SCHEMA public
    REVOKE UPDATE ON SEQUENCES FROM neobank_schedule_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE UPDATE ON SEQUENCES FROM neobank_schedule_app;

ALTER DEFAULT PRIVILEGES FOR ROLE neobank_schedule_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO neobank_schedule_app;
ALTER DEFAULT PRIVILEGES FOR ROLE neobank_schedule_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO neobank_schedule_app;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT ON TABLES TO neobank_schedule_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO neobank_schedule_app;

-- Existing tables and sequences:
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM neobank_schedule_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO neobank_schedule_app;

REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM neobank_schedule_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO neobank_schedule_app;

\echo 'Schedule Service roles and privileges successfully configured.'
\echo '======================================================================'
\echo 'All NeoBank database roles and privileges provisioned successfully.'
\echo '======================================================================'
