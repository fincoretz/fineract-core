#!/usr/bin/env bash
#
# Resets a seeded Fineract admin user's password on the LOCAL split-stack
# (docker-compose-splitstack.yml).
#
# The `default` tenant's `imara.admin` user is seeded by Liquibase with a
# password hash whose plaintext nobody knows, so a fresh split-stack has no
# way to log in until it's reset once. This does that directly against the
# tenant's own database - there's no "forgot password" API path, since that
# would itself require already being authenticated.
#
# Usage:
#   ./scripts/reset-local-admin-password.sh [tenant_db] [username] [password]
#
# Defaults match what this repo's local docs assume:
#   tenant_db=fineract_default  username=imara.admin  password=LocalTest@123
#
# Requires: the `fineract-db` container running (docker-compose-splitstack.yml
#   up), and python3 with the bcrypt module (pip3 install bcrypt) - the same
#   dependency provision-tenant.sh uses for the same reason.

set -euo pipefail

TENANT_DB="${1:-fineract_default}"
USERNAME="${2:-imara.admin}"
PASSWORD="${3:-LocalTest@123}"

DB_CONTAINER="${DB_CONTAINER:-fineract-db}"
PG_USER="${PG_USER:-postgres}"

if ! docker exec "$DB_CONTAINER" true 2>/dev/null; then
    echo "==> ERROR: container '$DB_CONTAINER' isn't running. Start the split-stack first:" >&2
    echo "    docker compose -f docker-compose-splitstack.yml up -d" >&2
    exit 1
fi

if ! python3 -c "import bcrypt" 2>/dev/null; then
    echo "==> ERROR: python3 'bcrypt' module not found. Install it with: pip3 install bcrypt" >&2
    exit 1
fi

echo "==> [1/2] Hashing password with bcrypt..."
BCRYPT_HASH="$(python3 -c "
import bcrypt
print(bcrypt.hashpw('${PASSWORD}'.encode(), bcrypt.gensalt()).decode())
")"

echo "==> [2/2] Updating ${USERNAME} in ${TENANT_DB}..."
docker exec -i "$DB_CONTAINER" psql -U "$PG_USER" -d "$TENANT_DB" -v ON_ERROR_STOP=1 \
    -v username="'${USERNAME}'" \
    -v password="'{bcrypt}${BCRYPT_HASH}'" <<'SQL'
UPDATE m_appuser
SET password = :password,
    firsttime_login_remaining = false,
    password_reset_required = false,
    nonexpired = true,
    enabled = true,
    last_time_password_updated = now()
WHERE username = :username;
SQL

echo "==> Done. Log in with: ${USERNAME} / ${PASSWORD}  (tenant db: ${TENANT_DB})"
