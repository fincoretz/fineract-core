#!/bin/bash
# First-boot bootstrap for the local split-instance dev stack
# (docker-compose-splitstack.yml). Runs once, on an empty data dir.
#
# Reproduces the manual bootstrap proven during setup:
#   - the app role (`fineract`, with CREATEDB so the tenant-provisioning API
#     can create per-tenant DBs later)
#   - the `fineract_tenants` registry DB and the `fineract_default` tenant DB
#     (the latter is REQUIRED before any Fineract instance boots — see
#     DEPLOYMENT_REQUIREMENTS.md §10 step 1)
#   - the `pgbouncer_auth` role + lookup function in EVERY DB PgBouncer routes
#     to, so transaction-pooled auth_query works (config/docker/pgbouncer)
set -e

: "${FINERACT_DB_USER:?}" "${FINERACT_DB_PASSWORD:?}" "${PGBOUNCER_AUTH_PASSWORD:?}"
: "${FINERACT_TENANTS_DB:=fineract_tenants}" "${FINERACT_DEFAULT_DB:=fineract_default}"

# Roles are cluster-global; create them once. Databases owned by the app role.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	CREATE ROLE ${FINERACT_DB_USER} WITH LOGIN CREATEDB PASSWORD '${FINERACT_DB_PASSWORD}';
	CREATE ROLE pgbouncer_auth WITH LOGIN PASSWORD '${PGBOUNCER_AUTH_PASSWORD}';
	CREATE DATABASE ${FINERACT_TENANTS_DB} OWNER ${FINERACT_DB_USER};
	CREATE DATABASE ${FINERACT_DEFAULT_DB} OWNER ${FINERACT_DB_USER};
EOSQL

# The auth lookup function is per-database (pg_shadow lookups are per-connection
# DB), so install it in each DB PgBouncer will proxy to.
for db in "$FINERACT_TENANTS_DB" "$FINERACT_DEFAULT_DB"; do
	psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-EOSQL
		CREATE OR REPLACE FUNCTION pgbouncer_auth(IN p_usename TEXT, OUT usename TEXT, OUT passwd TEXT)
		RETURNS record LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog
		AS 'SELECT usename, passwd FROM pg_shadow WHERE usename = p_usename';
		REVOKE ALL ON FUNCTION pgbouncer_auth(TEXT) FROM PUBLIC;
		GRANT EXECUTE ON FUNCTION pgbouncer_auth(TEXT) TO pgbouncer_auth;
		GRANT ALL ON SCHEMA public TO ${FINERACT_DB_USER};
	EOSQL
done
