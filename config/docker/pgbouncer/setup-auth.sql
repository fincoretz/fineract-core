-- Run this ONCE against your production PostgreSQL (fineract_db) as a superuser,
-- e.g.: psql -h localhost -p 5432 -U postgres -d fineract_tenants -f setup-auth.sql
--
-- This creates a dedicated, low-privilege role that PgBouncer uses to look up
-- password hashes dynamically via auth_query. No plaintext or hardcoded hashes
-- are stored in PgBouncer config, and it keeps working as you add tenants/users.

-- 1. Create the auth lookup function (SECURITY DEFINER lets it read pg_shadow
--    even though pgbouncer_auth itself has no direct access).
CREATE OR REPLACE FUNCTION pgbouncer_auth(IN p_usename TEXT, OUT usename TEXT, OUT passwd TEXT)
RETURNS record
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT usename, passwd FROM pg_shadow WHERE usename = p_usename;
$$;

-- 2. Create the role PgBouncer will connect as to run the lookup.
--    Give it a strong password of your own choosing — replace before running.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pgbouncer_auth') THEN
        CREATE ROLE pgbouncer_auth WITH LOGIN PASSWORD 'CHANGE_ME_STRONG_PASSWORD';
    END IF;
END
$$;

REVOKE ALL ON FUNCTION pgbouncer_auth(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pgbouncer_auth(TEXT) TO pgbouncer_auth;

-- Note: this function + role need to exist in EVERY database PgBouncer will
-- route to (fineract_tenants and each fineract_<tenant> database), since
-- pg_shadow lookups are per-database-connection. If you add a new tenant DB,
-- re-run this script against that DB too (or wrap it in your tenant
-- provisioning process).
