# Fineract Platform — Production Deployment Requirements

Audience: DevOps team. Scope: the multi-tenant Fineract core banking platform —
split application instances, PostgreSQL + PgBouncer, Kafka, and per-tenant web
portals routed by subdomain. Everything runs as Docker containers managed via
Portainer. The payment-gateway services are out of scope for this phase and will
be specified separately.

---

## 1. Architecture Overview

```
                          *.example.com (wildcard DNS + TLS)
                                      │
                          ┌───────────▼───────────┐
                          │  Reverse Proxy         │  nginx or Traefik
                          │  (TLS termination)     │
                          └───┬───────────────┬───┘
        tenant portals        │               │        api.example.com
   tenant-a.example.com ──────┘               │
   tenant-b.example.com                       │  GET → read instances
        ...                                   │  POST/PUT/DELETE → write instance
                                              │
              ┌───────────────┬───────────────┼───────────────┐
              ▼               ▼               ▼               ▼
        ┌──────────┐    ┌──────────┐    ┌───────────┐   ┌───────────┐
        │ read ×2  │    │ write ×1 │    │ batch-mgr │   │ batch-wkr │
        │          │    │          │    │ ×1 (never │   │ ×1..N     │
        │          │    │          │    │  scale)   │   │           │
        └────┬─────┘    └────┬─────┘    └─────┬─────┘   └─────┬─────┘
             │               │                │   job-topic   │
             │               │                └────Kafka──────┘
             └───────┬───────┘                        │
                     ▼                                ▼
              ┌────────────┐                   ┌────────────┐
              │  PgBouncer │                   │   Kafka    │  external-events
              └─────┬──────┘                   │  (KRaft)   │  + job-topic
                    ▼                          └────────────┘
              ┌────────────┐
              │ PostgreSQL │  fineract_tenants + one DB per tenant
              └────────────┘
```

Key properties:

- **One Fineract image, four roles.** The same image runs in four modes,
  selected purely by environment variables (see §5). Only the write instance
  runs Liquibase migrations; only the batch-manager schedules jobs.
- **Batch-manager must never exceed 1 replica.** Read and batch-worker scale
  horizontally; write is 1 for now (can scale later).
- **One PostgreSQL database per tenant** plus the shared `fineract_tenants`
  registry. All app→DB traffic goes through PgBouncer (transaction pooling).
- **Kafka carries two independent streams**: `job-topic` (batch-manager →
  batch-worker job dispatch) and `external-events` (business events for
  downstream consumers; 10 partitions, keyed by aggregate id).
- **Tenant portals run our own `fineract-neo-ui`** (Next.js, server-side
  rendered, port 3000), routed per tenant by subdomain.

---

## 2. Server Specifications

### Recommended: 3-node layout

| Node | Purpose | vCPU | RAM | Storage | Notes |
|---|---|---|---|---|---|
| **app-01** | fineract read ×2, write ×1, batch-manager, batch-worker ×1, PgBouncer | 8 | 32 GB | 100 GB SSD | Each Fineract JVM is capped at 2 GB heap (baked into the image); budget ~3 GB per container |
| **data-01** | PostgreSQL, Kafka | 8 | 32 GB | 500 GB **NVMe** (Postgres) + 100 GB (Kafka) | Fast disk matters most here; this node holds all state |
| **edge-01** | Reverse proxy, tenant portals, Portainer, monitoring | 4 | 8 GB | 100 GB SSD | Portals are Next.js SSR containers (~512 MB each); revisit node size beyond ~8 tenants |

### Minimum viable: single host (small production / staging)

| vCPU | RAM | Storage | Notes |
|---|---|---|---|
| 16 | 32 GB | 500 GB NVMe | Everything co-located; read replicas reduced to ×1. Acceptable to start, but a single point of failure — plan the 3-node split as tenant count grows |

### Per-container budget (for capacity planning as tenants grow)

| Container | vCPU | RAM |
|---|---|---|
| fineract-read (each) | 2 | 3 GB |
| fineract-write | 2 | 3 GB |
| fineract-batch-manager | 1 | 2.5 GB |
| fineract-batch-worker (each) | 2 | 3 GB |
| pgbouncer | 0.5 | 256 MB |
| postgres | 4 | 8–16 GB |
| kafka (single-node KRaft) | 2 | 4 GB |
| reverse proxy | 1 | 512 MB |
| tenant portal — fineract-neo-ui, Next.js SSR (each) | 0.5 | 512 MB |
| portainer | 0.5 | 512 MB |

OS: Ubuntu 22.04/24.04 LTS (or equivalent), Docker Engine ≥ 24, all nodes in
one private network / VPC with Portainer agents on each node.

---

## 3. Container Images

| Component | Image | Source |
|---|---|---|
| Fineract (all four roles) | `75.119.154.177:5000/fineract:latest` | Built and pushed from this repo via `./gradlew :fineract-provider:jib` |
| PgBouncer | `75.119.154.177:5000/pgbouncer:latest` | Built from `config/docker/pgbouncer/Dockerfile` (config baked in) |
| PostgreSQL | `postgres:18.x` (official) | Docker Hub |
| Kafka | `apache/kafka:3.9.x` (KRaft mode, no ZooKeeper) | Docker Hub |
| Tenant portal | `75.119.154.177:5000/fineract-neo-ui:<version>` | Built and pushed from the `fineract-neo-ui` repo (`npm run docker:build-push`) — pin the version tag, not `latest`, in production |
| Reverse proxy | `traefik:v3` or `nginx:stable` | Docker Hub — Traefik recommended (native Docker label-based routing + automatic Let's Encrypt) |

The private registry (`75.119.154.177:5000`) is HTTP with basic auth — it must
be added to Portainer's registries and to each node's Docker
`insecure-registries` config. Credentials will be shared separately (not in
this document).

Compose files already in this repo: `docker-compose-production.yml` (the four
Fineract roles + PgBouncer), `docker-compose-pgbouncer.yml` (standalone
PgBouncer validation), env files under `config/docker/env/fineract-*.env`.
Note: `deploy.replicas` in compose only takes effect in Swarm mode — under
plain Compose/Portainer stacks, duplicate the service block per replica.

---

## 4. Networking, DNS, TLS

1. **Wildcard DNS**: `*.example.com` → edge node public IP.
2. **Wildcard TLS certificate**: Let's Encrypt via DNS-01 challenge (required
   for wildcards) — Traefik handles issuance/renewal natively; if nginx, use
   certbot with the DNS provider's plugin.
3. **Routing rules** at the proxy:
   - `{tenant}.example.com` → that tenant's portal container.
   - `api.example.com` → Fineract: HTTP method-based split — `GET`/`HEAD` to
     the read instances (round-robin), everything else to the write instance.
     If method-based routing is more complexity than wanted on day one, route
     everything to write and add the split later — the read instances can sit
     idle until then.
   - Batch-manager and batch-worker are **never** exposed through the proxy.
   - Validated locally with Traefik (file provider): the split is two routers
     on the same host — one matching `Method(GET) || Method(HEAD)` → read, and
     a method-less catch-all → write. **The read router must have the higher
     priority**, or Traefik's catch-all (longer/more-specific rules win by
     default, but a bare `Host()` can otherwise tie) sends GETs to write too;
     set `priority` explicitly rather than relying on rule length. nginx does
     the same with a `limit_except`/`if ($request_method …)` split. Proven
     end-to-end (browser → proxy → correct instance) by killing the read
     container and confirming GETs 502 while writes stayed up.
4. **TLS terminates at the proxy.** Fineract containers run plain HTTP
   internally (`FINERACT_SERVER_SSL_ENABLED=false`, `FINERACT_SERVER_PORT=8080`
   — set both explicitly; do not rely on image defaults). The proxy must set
   `X-Forwarded-Proto`/`X-Forwarded-For` (Fineract is configured to honor
   forwarded headers).
5. **Firewall**: only 80/443 on the edge node are public. Postgres (5432),
   PgBouncer (5432 internal), Kafka (9092), Portainer (9443) are private-network
   only. Docker networks: one shared overlay/bridge network (`fineract-net`)
   across the stack. Outbound: **batch-manager only** needs egress to the SMS
   gateway provider (§8) — plain HTTP, no other role calls it.
6. **CORS**: Fineract's allowed origins must include the portal subdomains
   (`FINERACT_SECURITY_CORS` settings) — wildcard `https://*.example.com` or an
   explicit list.

---

## 5. Fineract Instance Configuration

All four roles share `config/docker/env/fineract-production.env` (DB via
PgBouncer, pool sizing, master password) plus one role file each:

| Role | Env file | Key flags |
|---|---|---|
| read | `fineract-read.env` | `FINERACT_MODE_READ_ENABLED=true`, all others false |
| write | `fineract-write.env` | `FINERACT_MODE_WRITE_ENABLED=true`, all others false |
| batch-manager | `fineract-batch-manager.env` | `FINERACT_MODE_BATCH_MANAGER_ENABLED=true`, all others false |
| batch-worker | `fineract-batch-worker.env` | `FINERACT_MODE_BATCH_WORKER_ENABLED=true`, all others false |

Additional required env (all roles unless noted):

```
# Database (via PgBouncer service name)
FINERACT_HIKARI_JDBC_URL=jdbc:postgresql://pgbouncer:5432/fineract_tenants
FINERACT_HIKARI_USERNAME / FINERACT_HIKARI_PASSWORD
FINERACT_DEFAULT_TENANTDB_HOSTNAME=pgbouncer
FINERACT_DEFAULT_TENANTDB_UID / FINERACT_DEFAULT_TENANTDB_PWD
FINERACT_DEFAULT_TENANTDB_MASTER_PASSWORD          # encrypts tenant DB creds — same value on every instance

# Server
FINERACT_SERVER_PORT=8080
FINERACT_SERVER_SSL_ENABLED=false

# Kafka — batch job dispatch (batch-manager + batch-worker only)
FINERACT_REMOTE_JOB_MESSAGE_HANDLER_KAFKA_ENABLED=true
FINERACT_REMOTE_JOB_MESSAGE_HANDLER_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
FINERACT_REMOTE_JOB_MESSAGE_HANDLER_KAFKA_TOPIC_NAME=job-topic
FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SPRING_EVENTS_ENABLED=false

# Kafka — business events (write instance; enable on others only if they emit)
FINERACT_EXTERNAL_EVENTS_ENABLED=true
FINERACT_EXTERNAL_EVENTS_KAFKA_ENABLED=true
FINERACT_EXTERNAL_EVENTS_KAFKA_BOOTSTRAP_SERVERS=kafka:9092

# Tenant provisioning API (write instance only)
FINERACT_PLATFORM_ADMIN_TENANT_ID=platform
FINERACT_TENANT_PROVISIONING_SUPERUSER_USERNAME    # Postgres role with CREATEDB (see §6)
FINERACT_TENANT_PROVISIONING_SUPERUSER_PASSWORD
FINERACT_TENANT_PROVISIONING_SUPERUSER_JDBC_URL=jdbc:postgresql://postgres:5432/postgres
FINERACT_TENANT_PROVISIONING_PGBOUNCER_AUTH_PASSWORD
FINERACT_TENANT_PROVISIONING_SELF_API_BASE_URL=http://fineract-write:8080/fineract-provider

# Documents (write + read)
FINERACT_CONTENT_FILESYSTEM_ROOT_FOLDER=/var/lib/fineract/documents   # persistent volume, shared or per-instance
```

Secrets (DB passwords, master password, provisioning credentials) go in
Portainer stack environment / a secrets manager — **never** committed to git.
All current default/dev passwords (`postgres/postgres`, master password
`fineract`) must be rotated before go-live.

Health checks for orchestration:
`GET /fineract-provider/actuator/health/liveness` and `/readiness` on port
8080. Allow a **start period of at least 120 s** — the write instance runs
Liquibase migrations for every tenant at boot, and startup time grows with
tenant count.

---

## 6. PostgreSQL

- Version 16+ (currently tested against the `postgres:18` image family).
- Databases: `fineract_tenants` (registry) + `fineract_<tenant>` per tenant
  (created automatically by the tenant-provisioning API).
- Roles:
  - App role (referenced by `FINERACT_HIKARI_USERNAME`) — owns tenant DBs.
  - `fineract_provisioner` — `CREATEDB` only, used exclusively by the
    tenant-provisioning API (§5). Not a superuser.
  - `pgbouncer_auth` — lookup role for PgBouncer's `auth_query`; created per
    database by `config/docker/pgbouncer/setup-auth.sql`. The
    tenant-provisioning API runs this automatically for new tenant DBs;
    pre-existing DBs need it run once manually.
- Server tuning (32 GB node, adjust proportionally):
  `max_connections=200`, `shared_buffers=8GB`, `effective_cache_size=24GB`,
  `work_mem=64MB`, `maintenance_work_mem=512MB`, `wal_level=replica`.
- **Backups**: nightly logical dump of every `fineract_*` database
  (`pg_dump -Fc`) **plus** continuous WAL archiving (e.g. WAL-G/pgBackRest) to
  off-node object storage. Retention ≥ 30 days. A restore test is part of
  acceptance — an untested backup does not count.
- PgBouncer: transaction pooling mode, `default_pool_size=10` per DB,
  `max_client_conn=1000`; config is baked into the registry image.

---

## 7. Kafka

- Single-node KRaft (no ZooKeeper) is sufficient initially; 100 GB volume,
  7-day retention default.
- Topics (auto-created by Fineract, but pre-creating with explicit settings is
  preferred):
  - `job-topic` — batch dispatch, consumer group `fineract-worker`.
  - `external-events` — 10 partitions, business events (Avro-encoded).
- No schema registry is used — consumers decode Avro single-object encoding
  directly. Nothing to deploy for this beyond the broker.

---

## 8. SMS Gateway (Notifications)

- Fineract sends SMS (client campaigns, arrears reminders, etc.) through a
  **per-tenant, runtime-configured HTTP gateway** — this is not an env var or
  a compose service, it's application data set via API:
  `PUT /fineract-provider/api/v1/externalservice/SMS` with body
  `host_name`, `port_number`, `end_point`, `tenant_app_key` (the app key is
  sent as a header on every outbound call to the gateway, alongside the
  tenant identifier). `GET` the same path to check current config. Verified
  locally: the round-trip works and every tenant starts with a stub default
  (`localhost:9191`, `/`, no app key) until explicitly configured.
- **Must be set per tenant before go-live** — including `default` and every
  tenant provisioned later via the tenant-provisioning API (§5) — or SMS
  sends just fail against nothing on `localhost`. Add to the same per-tenant
  onboarding checklist as external-event configuration (§10 step 6, and see
  the note there: event types are also disabled per tenant by default).
- **Plain HTTP only** — `SmsConfigUtils` hardcodes the request scheme, there
  is no TLS option at this layer. If the gateway is off-node or off-network,
  terminate TLS in front of it (reverse proxy / private VPN path) rather than
  relying on Fineract to encrypt this leg.
- Delivery is asynchronous via three scheduled jobs. Confirmed locally these
  are plain `SimpleJob`s that run **entirely on batch-manager** — same
  pattern as the external-events flush job, not partitioned/dispatched to
  batch-worker like Loan COB is:
  - `Send Messages to SMS Gateway` — POSTs queued outbound messages.
  - `Update SMS Outbound with Campaign Message` — expands SMS campaign
    templates into individual outbound messages.
  - `Get Delivery Reports from SMS Gateway` — polls the gateway for status.
- Needs outbound network egress from **batch-manager specifically** to the
  SMS provider (§4 firewall rules) — the other three roles never call it.
- A sibling `SMTP` (email) and `NOTIFICATION` (push) config exist under the
  same `/externalservice/{SMS|SMTP|NOTIFICATION}` API pattern if those
  channels are needed later — not covered here since they weren't in scope.

---

## 9. Tenant Portals (`fineract-neo-ui`)

- Image: `75.119.154.177:5000/fineract-neo-ui:<version>` — our own Next.js
  app, server-side rendered, listens on port 3000. One container per tenant.
- Per-tenant config is env-driven, read server-side at request time (no
  per-tenant rebuild needed — the same image serves every tenant):
  - `TENANT_ID` — sets the `Fineract-Platform-TenantId` header sent on every
    API call for that container (defaults to `default` if unset — **must** be
    set explicitly per tenant in production).
  - `APP_NAME` — per-tenant branding (page title etc.), optional.
  - `NEXT_PUBLIC_API_URL` — the **public/browser-facing** Fineract API base URL
    (baked into the client bundle, same for all tenants, typically
    `https://api.example.com`).
  - `API_URL` — the **server-only** base URL the SSR proxy uses (never shipped
    to the browser). The app's proxy route (`src/app/api/proxy/[...path]/
    route.ts`) resolves `process.env.API_URL ?? process.env.NEXT_PUBLIC_API_URL`,
    so if `API_URL` is unset, server-side calls fall back to the public URL and
    loop out through the edge and back. Set `API_URL` to the reverse proxy's
    **internal** address on the private network (e.g. an internal alias / the
    proxy's service name) so SSR calls stay on-network yet still hit the same
    routing rules — the read/write split then applies to portal traffic for
    free, with no public round-trip. Validated locally: `API_URL` → internal
    proxy alias, `NEXT_PUBLIC_API_URL` → public host; a portal SSR `GET` landed
    on the read instance and a `POST` on write, proven by killing read and
    watching only the GETs fail.
- Because the app proxies API calls through its own Next.js server side (see
  `next.config.ts` rewrite of `/fineract-provider/api/*`), the value `API_URL`
  points at must be reachable from the portal container on the private network
  — point it at the proxy (above), not directly at a single Fineract instance:
  a single instance can't serve both reads and writes correctly (a read node
  405s on writes; the write node would serve reads but defeats read scaling).
  The tenant identifier travels as the `Fineract-Platform-TenantId` header
  (from `TENANT_ID`), not a subdomain, so one `API_URL` works for every tenant.
- Proxy routes `{tenant}.example.com` → that tenant's portal container.
- Adding a tenant = one API call to the tenant-provisioning endpoint (creates
  DB + registers tenant, no restart) **plus** one new portal container (image
  unchanged, only `TENANT_ID`/`APP_NAME` differ) + one proxy routing rule;
  DNS is already covered by the wildcard. Good candidate for a Portainer
  stack template — the only per-tenant variable is two env values.

---

## 10. First-Deployment Order & One-Time Bootstrap

1. Postgres (with tuned config, roles from §6) → healthy. Also pre-create the
   `fineract_tenants` (registry) and `fineract_default` databases, owned by
   the app role, with `pgbouncer_auth` set up in each (§6/setup-auth.sql) —
   **required**, not optional: Fineract's `TenantDatabaseUpgradeService`
   unconditionally tries to migrate a `default` tenant on every boot (there is
   no env flag to disable this), and if `fineract_default` doesn't exist yet
   this throws during `afterPropertiesSet()` and fails the whole Spring
   context — fineract-write will not start at all, not just skip that tenant.
2. PgBouncer → verify `psql` through it to `fineract_tenants`. If Postgres
   rejected an earlier connection attempt to a not-yet-existing database,
   restart PgBouncer before continuing — it caches that failure
   (`server_login_retry`) and will keep returning it even after the database
   is created.
3. Kafka → healthy.
4. **fineract-write alone first** — it runs all migrations; watch logs until
   `Started ServerApplication`.
5. **One-time: bootstrap the `platform` tenant** (holds the platform-admin
   user for the tenant-provisioning API). Run the documented bootstrap
   (`scripts/provision-tenant.sh` or the manual SQL + trigger sequence) once
   against production. Verify
   `POST /fineract-provider/api/v1/internal/tenants` returns `202` for a test
   tenant, then remove the test tenant.
6. **Per-tenant notification setup** (repeat for `default`, `platform`, and
   every tenant provisioned after): `PUT /externalservice/SMS` with the real
   SMS gateway host/port/app-key (§8 — defaults to a `localhost:9191` stub
   that silently fails until set), and `PUT /externalevents/configuration`
   to enable whichever business event types (client, loan, savings, …) are
   actually needed on `external-events` (§7 — every event type ships
   disabled by default; verified locally that client-create/activate and
   loan-create/approve/disburse only reach Kafka after being explicitly
   turned on here).
7. read ×2, batch-manager ×1, batch-worker ×1.
8. Reverse proxy + portals + DNS/TLS.
9. Rotate every default credential; change the seeded per-tenant admin
   password on first login.

**Rollback**: previous image tags remain in the registry — redeploy the stack
pinned to the prior tag. Database migrations are forward-only (Liquibase);
image rollbacks across a release that migrated the schema require a
backup-restore decision, so snapshot the DB before any image upgrade.

---

## 11. Monitoring & Operations

- Scrape `/fineract-provider/actuator/health` from the proxy or a monitoring
  stack; this repo ships an optional Prometheus/Grafana compose
  (`config/docker/compose/observability.yml`).
- Log rotation on all nodes (`json-file` driver with `max-size`/`max-file`, or
  a log shipper).
- Alerts at minimum: instance health-check failing, Postgres disk > 80 %,
  Kafka consumer lag on `job-topic`, backup job failure, SMS gateway
  unreachable (batch-manager logs on `Send Messages to SMS Gateway` /
  `Get Delivery Reports from SMS Gateway` job failures — §8).
- The `documents` volume (client document uploads) is stateful — include it in
  the backup plan.

## 12. Open Items (decisions needed from us, not DevOps)

- Final domain name(s) and DNS provider (needed for wildcard DNS-01 issuance).
- Whether read/write method-split routing ships day one or later (§4.3).
- Managed Postgres vs self-hosted container — this spec assumes self-hosted;
  a managed offering changes §6 but nothing else.
- Payment-gateway services (repayments/disbursements/reference sync) — parked;
  will arrive as a separate spec with their own DBs and Kafka consumer groups.
- SMS gateway provider selection and credentials (§8) — the endpoint/app-key
  are per-tenant runtime config, not infra, but we need to pick a provider
  and confirm its API is compatible with Fineract's plain-HTTP outbound call
  shape before go-live.
