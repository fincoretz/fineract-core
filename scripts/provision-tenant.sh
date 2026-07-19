#!/usr/bin/env bash
#
# Bootstraps a Fineract tenant outside the running application - specifically the one-time "platform"
# admin tenant needed before the internal tenant-provisioning API (POST /v1/internal/tenants) can be used
# at all, since that API requires an authenticated platform-admin user to already exist.
#
# For every tenant after that, use the internal tenant-provisioning API instead - it runs the equivalent
# of this script's steps in-process, without requiring an application restart. This script's step 4 does
# require a restart (see below), since there is no running application to call into yet.
#
# Steps:
#   1. Creates the tenant's PostgreSQL database
#   2. Runs the pgbouncer_auth setup (so PgBouncer can route to it)
#   3. Registers the tenant in fineract_tenants (tenant_server_connections + tenants), with the schema
#      password encrypted and the master-password hash set - both required by Fineract before the
#      connection can be used (see DatabasePasswordEncryptor in the Java codebase)
#   4. Prints a reminder to restart Fineract, which is what actually runs Liquibase for a brand-new
#      tenant registered while offline (Fineract only auto-migrates tenants known at startup)
#
# Usage:
#   ./provision-tenant.sh <tenant_identifier> <tenant_name> <timezone>
#
# Example:
#   ./provision-tenant.sh platform "Platform Admin Tenant" "UTC"
#
# Requires: psql, openssl >= 3 (for PBKDF2 + AES-256-CBC), python3 with the bcrypt module
#   (pip3 install bcrypt) for the master-password hash.

set -euo pipefail

TENANT_ID="${1:?Usage: $0 <tenant_identifier> <tenant_name> <timezone>}"
TENANT_NAME="${2:?Usage: $0 <tenant_identifier> <tenant_name> <timezone>}"
TENANT_TZ="${3:-UTC}"

# ── Config — adjust to your environment ──────────────────────────────────────
PG_HOST="${PG_HOST:-fineract_db}"
PG_PORT="${PG_PORT:-5432}"
PG_SUPERUSER="${PG_SUPERUSER:-postgres}"
PG_SUPERUSER_PASSWORD="${PG_SUPERUSER_PASSWORD:?Set PG_SUPERUSER_PASSWORD}"

FINERACT_DB_USER="${FINERACT_DB_USER:-fineract}"
FINERACT_DB_PASSWORD="${FINERACT_DB_PASSWORD:?Set FINERACT_DB_PASSWORD}"

PGBOUNCER_AUTH_PASSWORD="${PGBOUNCER_AUTH_PASSWORD:?Set PGBOUNCER_AUTH_PASSWORD}"

# Must match fineract.tenant.master-password (FINERACT_DEFAULT_TENANTDB_MASTER_PASSWORD) on the
# application instance(s) that will serve this tenant - this is what encrypts/decrypts schema_password.
FINERACT_TENANT_MASTER_PASSWORD="${FINERACT_TENANT_MASTER_PASSWORD:?Set FINERACT_TENANT_MASTER_PASSWORD}"

DB_NAME="fineract_${TENANT_ID}"

export PGPASSWORD="$PG_SUPERUSER_PASSWORD"

echo "==> [1/4] Creating database ${DB_NAME}"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_SUPERUSER" -d postgres -tc \
  "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'" | grep -q 1 || \
  psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_SUPERUSER" -d postgres -c "CREATE DATABASE ${DB_NAME} OWNER ${FINERACT_DB_USER};"

echo "==> [2/4] Setting up pgbouncer_auth in ${DB_NAME}"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_SUPERUSER" -d "$DB_NAME" <<SQL
CREATE OR REPLACE FUNCTION pgbouncer_auth(IN p_usename TEXT, OUT usename TEXT, OUT passwd TEXT)
RETURNS record
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog
AS \$\$
    SELECT usename, passwd FROM pg_shadow WHERE usename = p_usename;
\$\$;

DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pgbouncer_auth') THEN
        CREATE ROLE pgbouncer_auth WITH LOGIN PASSWORD '${PGBOUNCER_AUTH_PASSWORD}';
    END IF;
END
\$\$;

REVOKE ALL ON FUNCTION pgbouncer_auth(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pgbouncer_auth(TEXT) TO pgbouncer_auth;
SQL

echo "==> [3/4] Registering tenant '${TENANT_ID}' in fineract_tenants"

# Fineract stores schema_password encrypted (AES-256-CBC via PBKDF2WithHmacSHA1, 65536 iterations,
# base64(iv[16] + salt[16] + ciphertext)) and validates a bcrypt hash of the master password before
# using the connection at all - see DatabasePasswordEncryptor / EncryptionUtil in the Java codebase.
# We replicate both here with openssl + python3, so a newly-registered tenant is usable immediately
# without any manual DB patching.

SALT_HEX="$(openssl rand -hex 16)"
IV_HEX="$(openssl rand -hex 16)"
CIPHERTEXT_B64="$(printf '%s' "$FINERACT_DB_PASSWORD" | openssl enc -aes-256-cbc -pbkdf2 -iter 65536 \
  -md sha1 -S "$SALT_HEX" -iv "$IV_HEX" -pass "pass:${FINERACT_TENANT_MASTER_PASSWORD}" | base64 | tr -d '\n')"
ENCRYPTED_SCHEMA_PASSWORD="$(python3 -c "
import base64
iv = bytes.fromhex('${IV_HEX}')
salt = bytes.fromhex('${SALT_HEX}')
ciphertext = base64.b64decode('${CIPHERTEXT_B64}')
print(base64.b64encode(iv + salt + ciphertext).decode())
")"

MASTER_PASSWORD_HASH="$(python3 -c "
import bcrypt
print(bcrypt.hashpw('${FINERACT_TENANT_MASTER_PASSWORD}'.encode(), bcrypt.gensalt()).decode())
")"

psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_SUPERUSER" -d fineract_tenants \
  -v encrypted_schema_password="'${ENCRYPTED_SCHEMA_PASSWORD}'" \
  -v master_password_hash="'${MASTER_PASSWORD_HASH}'" <<SQL
INSERT INTO tenant_server_connections
    (schema_server, schema_name, schema_server_port, schema_username, schema_password, master_password_hash, auto_update)
VALUES
    ('${PG_HOST}', '${DB_NAME}', '${PG_PORT}', '${FINERACT_DB_USER}', :encrypted_schema_password, :master_password_hash, 1)
RETURNING id \gset conn_

INSERT INTO tenants
    (identifier, name, timezone_id, oltp_id, report_id, created_date, lastmodified_date)
VALUES
    ('${TENANT_ID}', '${TENANT_NAME}', '${TENANT_TZ}', :conn_id, :conn_id, now(), now());
SQL

echo "==> [4/4] Tenant registered. Restart Fineract now to run its Liquibase migration and seed data."
echo "    (Fineract only auto-migrates tenants it knows about at startup - see TenantDatabaseUpgradeService.)"
echo "    After restarting, log in with: imara.admin / <seed password>  +  Fineract-Platform-TenantId: ${TENANT_ID}"
