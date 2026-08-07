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
# Requires: psql, python3 with the bcrypt and cryptography modules
#   (pip3 install bcrypt cryptography).

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
# We replicate this in python3 (via the `cryptography` package), NOT the openssl CLI: openssl's
# `enc -S` flag silently truncates a 16-byte salt to its legacy 8-byte PKCS5_SALT_LEN buffer (visible
# as a "hex string is too long, ignoring excess" warning), which derives a different key than Java's
# PBKDF2WithHmacSHA1 uses against the full 16-byte salt actually stored - every tenant provisioned
# that way fails to decrypt at Fineract boot with BadPaddingException. python3 -m pip install cryptography.
ENCRYPTED_SCHEMA_PASSWORD="$(python3 -c "
import os, base64
from cryptography.hazmat.primitives import hashes, padding as sympadding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

master_password = '${FINERACT_TENANT_MASTER_PASSWORD}'.encode()
data = '${FINERACT_DB_PASSWORD}'.encode()

salt = os.urandom(16)
iv = os.urandom(16)
key = PBKDF2HMAC(algorithm=hashes.SHA1(), length=32, salt=salt, iterations=65536).derive(master_password)

padder = sympadding.PKCS7(128).padder()
padded_data = padder.update(data) + padder.finalize()
encryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
ciphertext = encryptor.update(padded_data) + encryptor.finalize()

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
