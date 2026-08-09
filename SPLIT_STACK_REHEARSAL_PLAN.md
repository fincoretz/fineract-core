# Split-Stack UAT Rehearsal — Activity Plan

**Status:** DRAFT for review · **Target host:** `75.119.154.177` (UAT, single-node Docker Swarm, Ubuntu 24.04, 12 vCPU / 47 GB)
**Objective:** Replace the manually-assembled Caddy stack with the split-instance topology (Traefik + 4 Fineract roles + PgBouncer-wired + kafka-ui + secured dashboards), from freshly-built images, as a **rehearsal for production**.
**Constraints:** POs are reviewing; a ~2-day window is available and a **single planned outage is acceptable** (clean teardown + rebuild, not a parallel cutover). Most work happens *before* the box goes down; only Phase 5 is the actual outage.
**Authoritative design:** `DEPLOYMENT_REQUIREMENTS.md` (§ references throughout), `docker-compose-splitstack.yml` (validated local rig), `docker-compose-production.yml`, `config/docker/traefik/*`, `config/docker/env/fineract-*.env`.

> Legend — **[repo]** = change in git (no box impact) · **[box]** = action on the server · **[outage]** = inside the downtime window.

---

## Decisions to confirm before we start (only these are genuinely open)

| # | Decision | Recommendation | Blocks |
|---|---|---|---|
| D1 | Public TLS approach | **Per-host Let's Encrypt HTTP-01** for the four known hosts (no DNS-provider dependency; matches current Caddy). Adopt wildcard DNS-01 later for prod (§12). | Phase 1.2, 5.7 |
| D2 | Build host | **On the UAT box** (x86_64, local registry, being rebuilt anyway). Alternative: Mac with forced `linux/amd64`. | Phase 2 |
| D3 | Admin UI exposure | Admin UI currently has **no** public domain (reachable only on `:3100`). Give it a host (e.g. `admin.imarafinance.africa`) or keep internal? | Phase 1.1 |
| D4 | Postgres version | **Keep PG17** in place (17→18 is dump/restore, not worth it for a rehearsal on live PO data). | Phase 4 |
| D5 | Method-split day one | **Yes** — it's validated (§4.3). | Phase 1.1 |

Everything else in §12 (wildcard domain, managed-PG, payment-gateway, SMS provider) is parked and out of scope here.

---

## Phase 0 — Pre-flight (no box impact)
- [ ] 0.1 Confirm D1–D5 above.
- [ ] 0.2 Freeze the four source repos at known commits; record git SHAs for `fineract-core`, `fineract-neo-ui`, `admin`, `pgbouncer` config.
- [ ] 0.3 Announce the maintenance window to POs; agree exact date/time for Phase 5.
- [ ] 0.4 Confirm registry credentials are available on the build host and in Portainer (§3).

## Phase 1 — Repo parity work **[repo]** (peer-reviewed PR before touching the box)
Close the gap between the committed configs (still `*.localhost`) and the real production edge.
**Approach note:** the local-validated `traefik.yml`/`dynamic/routes.yml` are left untouched (the reproducible local rig depends on them). Production gets **parallel files** instead: `traefik.prod.yml` + `dynamic-prod/routes.yml`.
- [x] 1.1 `config/docker/traefik/dynamic-prod/routes.yml` (NEW) — **Caddy-parity** routers:
  - `bedrock` → fineract-neo-ui · `imarishamaisha` → imarisha-ui · `sandbox` → sandbox-ui · `bongobing` → bongobing-ui.
  - Portainer host (replaces Caddy's `:443` catch-all) + kafka-ui host, both behind `admin-auth`.
  - `api` read/write split, **both** public (`api.imarafinance.africa`, TLS) and internal (`api` alias, :8000 for portal SSR); auth'd dashboard.
  - Basic-auth via mounted **`usersFile`** htpasswd (not inline, not committed) — replaces the throwaway `admin/admin`.
  - Admin UI router left commented per D3 (internal-only).
- [x] 1.2 `config/docker/traefik/traefik.prod.yml` (NEW) — production TLS:
  - `web` :80 (global http→https redirect; ACME challenge still served), `websecure` :443, and a **separate `internal` :8000** for portal SSR so the redirect never catches on-network calls (§4/§9 caveat).
  - `certificatesResolvers.le` via **HTTP-01** (per D1); `tls: {certResolver: le}` on every public router.
- [x] 1.3 Extended `docker-compose-production.yml`: added Traefik (edge, replaces Caddy) + kafka-ui + `traefik_letsencrypt` volume; write-only tenant-provisioning env (§5). UIs / Postgres / Kafka / Portainer stay their own stacks on `fineract-net` (Traefik file-provider routes to them). Swarm `deploy.replicas` honored.
- [x] 1.4 Secrets hygiene (§5): provisioning + auth secrets are `${...}` refs (Portainer stack env) or mounted files; `.gitignore` now blocks `config/docker/traefik/secrets/`.
- [~] 1.5 `daemon.json` json-file log rotation — **deferred to Phase 7** (box-side change, not a repo artifact).
- [x] 1.6 Verified: read/manager/worker env all set `FINERACT_MODE_WRITE_ENABLED=false` → only write runs Liquibase; `depends_on` gates the rest behind write healthy (§1, §10.4). No change needed.
- [ ] 1.7 PR review + merge (prod replay depends on these being correct). ← **your review**

## Phase 2 — Build & publish images **[box]** (amd64, versioned tags)
- [ ] 2.1 Prep build host per D2 (JDK/Gradle for Jib, Node for neo-ui, docker for pgbouncer/admin).
- [ ] 2.2 Build & push, tag `<version>-<gitsha>` (never `:latest`), record digests:
  - `fineract` — `./gradlew :fineract-provider:jibDockerBuild -Djib.to.image=75.119.154.177:5000/fineract:<tag>` (Java changed: `5e8174472`, `bd71d3868`).
  - `fineract-neo-ui` — from its repo (changed today).
  - `fineract-pgbouncer` — `docker build config/docker/pgbouncer` (live image predates `0df9c8b44`/`bed2d97b5`).
  - `fineract-admin` — if changed.
- [ ] 2.3 Verify each image pulls cleanly on the box; pin the tags in the Phase 1.3 stack.

## Phase 3 — Backups & safety net **[box]** (gate before any teardown)
**Done 2026-08-09 → `/root/backups/pre-splitstack-20260809/` (161 MB, 8 files).** Kept **on the box** by decision (UAT; covers the real risk — a bad migration/cutover — but NOT loss of the VM itself; that's what 3.2 covers).
- [x] 3.1 `pg_dump -Fc` of all 5 DBs + `pg_dumpall --globals-only`:
  `fineract_default` 37M · `fineract_tenant_b` 30M · `fineract_tenant_bongobing` 7.9M · `fineract_tenant_sandbox` 7.0M · `fineract_tenants` 28K · globals 4K.
- [ ] 3.2 Provider **VM snapshot** — **OUTSTANDING, owner: Dennis** (no provider-console access from here). The only cover for total VM loss.
- [x] 3.3 Archived `/root/fineract/documents` (80M) and `/root/caddy` (config+certs, rollback).
- [x] 3.4 **Restore-test PASSED** — scratch `postgres:17-alpine`, globals + `fineract_tenants` + `fineract_default` restored with **0 warnings**; row counts identical to live (office 15 · client 38 · loan 19 · appuser 13 · savings 0) and all 4 tenants present (`default`, `tenant_b`, `tenant_bongobing`, `tenant_sandbox`). Scratch container removed.
- **Note:** no `platform` tenant exists yet → confirms the §10.5 bootstrap in Phase 5.4 is required.

## Phase 4 — Postgres prep **[box]** (in place, PG17 kept per D4) — **DONE 2026-08-09**
- [x] 4.1 Tuning applied via `ALTER SYSTEM`: `effective_cache_size=18GB`, `work_mem=32MB`, `maintenance_work_mem=512MB` live now; `max_connections=200` + `shared_buffers=8GB` **pending restart → applied at Phase 5 cutover**. `wal_level=replica` already set.
  *Deviation from §6 (which assumes a dedicated data node): `work_mem` 32MB not 64MB, `effective_cache_size` 18GB not 24GB — this box also runs 4 JVMs, Kafka and the UIs.* `max_connections=200` stays above PgBouncer's `max_db_connections=180`. ✔
- [x] 4.2 Roles created: `pgbouncer_auth` (+ `pgbouncer_auth()` function in **all 5** DBs) and `fineract_provisioner` (LOGIN, CREATEDB, **not** superuser). Passwords generated **on the box**, stored root-only in `/root/secrets/{pgbouncer_auth,fineract_provisioner}.pwd` (never committed, never transmitted).
- [x] 4.3 `fineract_tenants` + `fineract_default` confirmed present with `pgbouncer_auth`. ✔
- [x] 4.4 **PgBouncer rebuilt and PROVEN WORKING** (`fineract-pgbouncer:uat-20260809`, built natively on the box). Verified end-to-end: through PgBouncer → `fineract_tenants` returns 4 tenants, → `fineract_default` returns 38 clients, no errors.

### ⚠ Findings from Phase 4 that change Phase 5
1. **PgBouncer had never worked.** `pgbouncer_auth` did not exist in any database; the service only looked "healthy" because its `pg_isready` probe merely pings the port. Now fixed and verified.
2. **Dockerfile bug (fixed, committed):** `COPY userlist.txt` without `--chown` — the image runs as uid 70 but the file was root-owned `600`, so PgBouncer logged `could not open auth_file … Permission denied` and failed every auth. It only ever worked because a git checkout happens to be `644`. Now `COPY --chown=70:70`.
3. **The `fineract` role the env files expect does not exist** — the platform authenticates as the Postgres superuser instead. Per decision we keep the existing role and override the repo env at deploy time (values on the box only).
4. **The live tenant master password is Fineract's shipped default, NOT the value in the committed env file.** It encrypts the per-tenant DB passwords, so we keep the live value — booting with the repo value would leave every tenant unable to decrypt its DB password. Rotate before prod (§5).
5. **`tenant_server_connections` pins all 4 tenants to `fineract_db:5432` as `postgres`.** Fineract reads per-tenant connections from this table, **not** from env — so env changes alone will NOT route tenants through PgBouncer. Must be UPDATEd at cutover (Phase 5.2b), while the app is stopped.

### Required deploy-time env at cutover (overrides the committed defaults; **values are NOT recorded here**)
The live values are on the box in `/root/stack/config/docker/env/uat-overrides.env` (root-only, gitignored) and
`/root/secrets/*.pwd`. The **keys** that must be overridden:
```
FINERACT_HIKARI_USERNAME / FINERACT_HIKARI_PASSWORD
FINERACT_DEFAULT_TENANTDB_UID / FINERACT_DEFAULT_TENANTDB_PWD
FINERACT_DEFAULT_TENANTDB_MASTER_PASSWORD   # MUST match what encrypted the existing tenant rows
FINERACT_DEFAULT_MASTER_PASSWORD
FINERACT_DEFAULT_TENANTDB_RO_*              # see the Liquibase checksum note
PROVISIONER_DB_PASSWORD                     # /root/secrets/fineract_provisioner.pwd
PGBOUNCER_AUTH_PASSWORD                     # /root/secrets/pgbouncer_auth.pwd
```
**Tech debt for prod (not this rehearsal):** the app authenticates as a Postgres **superuser**, and the tenant master
password is still Fineract's **shipped default**. Both must be rotated before go-live (§5) — and rotating the master
password requires re-encrypting every stored tenant DB password, so it is a planned task, not a config edit.

## Phase 5 — Cutover **[outage]** (the downtime window; target ~2–4 h)

> **PREREQUISITE — RESOLVED 2026-08-09.** The node had no registry credentials (`no basic auth
> credentials`, no `/root/.docker/config.json`) because the existing services were deployed through
> Portainer, which holds the credential — the Docker CLI on the node never had it. Fixed by running
> `docker login 75.119.154.177:5000 -u lman` on the box. *(Gotcha: the port is part of the registry
> identity — `docker login 75.119.154.177` without `:5000` authenticates a different registry and does
> not help.)*
>
> **Images verified present on the node, all `linux/amd64`:**
> `fineract:b6625dd37` · `fineract-neo-ui:0.4.8` · `pgbouncer:uat-20260809`.
> The registry's `pgbouncer:b6625dd37` is the **pre-fix** image (root-owned auth file + dev
> credentials) — do **not** use it; the compose pins `uat-20260809`.

**EXECUTED 2026-08-09 — core cutover COMPLETE and validated.** Outage began 17:05:57.

### Result
| Service | State |
|---|---|
| `fineract_fineract-write` | 1/1 healthy (Liquibase completed on all 4 tenants) |
| `fineract_fineract-read` | 2/2 healthy |
| `fineract_fineract-batch-manager` | 1/1 healthy (never >1) |
| `fineract_fineract-batch-worker` | 2/2 healthy |
| `fineract_pgbouncer` | 1/1 healthy — **app now actually routes through it** |
| `fineract_traefik` | 1/1 — replaced Caddy, LE certs issued |
| `fineract_kafka-ui` | 1/1 (route pending DNS) |
| 4 tenant portals | 1/1 each, **neo-ui 0.4.8**, HTTPS 200 + valid LE cert |

### Verified
- **Method split works in production** (Traefik access log): `GET …/offices` → `api-read-internal@file` → `fineract-read:8080`; `POST …/offices` → `api-write-internal@file` → `fineract-write:8080`.
- Read instance correctly rejects writes: `405 error.msg.invalid.instance.type`.
- **All 4 tenants resolve** (401 auth, not 500) → master-password decryption + per-tenant PgBouncer routing confirmed. Invalid tenant → 400, so validation is real.
- Postgres restarted into `max_connections=200`, `shared_buffers=8GB`.
- 0 PgBouncer auth failures after the fix below.

### Two failures hit during cutover (both fixed — the value of rehearsing)
1. **Liquibase checksum drift** — `0009_set_and_encrypt_ro_if_not_exists.xml` failed validation (`was 9:061bea70… now 9:49de3496…`), crash-looping `fineract-write`. Cause: the changeset substitutes `${fineract.tenant.read-only-*}` and **Liquibase folds resolved property values into the checksum**; the old deployment set `FINERACT_DEFAULT_TENANTDB_RO_*`, the new env files did not. Fix: added the RO_* vars to the override env **and** NULLed that one `md5sum` to re-baseline (changeset stays `EXECUTED`, does not re-run).
2. **Two PgBouncers on one network** — the old `pgbouncer` stack was still running and shares the `pgbouncer` DNS alias on `fineract-net`, so Swarm round-robined ~50% of connections to the **pre-fix image** → `password authentication failed for user "pgbouncer_auth" (server_login_retry)` → `read.1` crash-looped while `read.2` was fine. Fix: `docker stack rm pgbouncer`; alias now resolves to one address.

### Still outstanding
- [ ] 5.4 Bootstrap the `platform` tenant (`scripts/provision-tenant.sh`, §10.5) — no `platform` tenant exists yet, so the tenant-provisioning API is not yet usable.
- [ ] 5.5 Per-tenant SMS/external-event setup (§10.6).
- [x] **`api.` + `traefik.` ENABLED 2026-08-09** (DNS created). LE certs issued on first request; verified:
  `GET api.imarafinance.africa/...` → `api-read-public@file` → read; `POST` → `api-write-public@file` → write (HTTP/2, valid TLS).
  Dashboard at `traefik.imarafinance.africa` → 401 with bad creds, 200 with correct — basic-auth enforced.
  **Admin credential: user `admin`, password in `/root/secrets/traefik_admin.pwd` on the box (root-only, never committed).**
- [ ] `kafka.` + `portainer.` routers — still awaiting DNS A records → 75.119.154.177. Both blocks are staged (commented) in `dynamic-prod/routes.uat.yml`; uncomment and Traefik hot-reloads, no redeploy. Portainer is already attached to `fineract-net`.

---
### Original step list
- [x] 5.1 Confirm backups (Phase 3) green; announce start. Stop the Caddy stack + old all-in-one `fineract-write`.
- [ ] 5.2 Deploy PgBouncer (`fineract-pgbouncer:uat-20260809`) → verify `psql` through it to `fineract_tenants`; restart it once if it cached a pre-existing login failure (§10.2).
- [ ] 5.2b **UPDATE the tenant registry to route via PgBouncer** (app must be stopped). Without this the tenants keep connecting straight to Postgres and the pooling work is inert:
  ```sql
  UPDATE tenant_server_connections SET schema_server = 'pgbouncer', schema_server_port = '5432';
  -- readonly_* columns too if populated; verify with:
  -- SELECT id, schema_server, schema_server_port, schema_name FROM tenant_server_connections;
  ```
  Rollback: set `schema_server` back to `fineract_db`.
- [ ] 5.3 **Deploy `fineract-write` alone** → watch Liquibase migrate every tenant → `Started ServerApplication`; readiness green (allow ≥120 s start period, §5).
- [ ] 5.4 One-time: bootstrap the `platform` tenant (`scripts/provision-tenant.sh`, §10.5); verify `POST …/internal/tenants` → 202 for a test tenant, then remove it.
- [ ] 5.5 Per-tenant notification setup (§10.6): SMS stub acceptable for UAT; enable the external-event types actually needed (all ship disabled).
- [ ] 5.6 Deploy `fineract-read`×2, `fineract-batch-manager`×1 (**never >1**), `fineract-batch-worker`×2.
- [ ] 5.7 Deploy Traefik + the 5 UIs + kafka-ui; LE issues per-host certs (D1); DNS already resolves to this host.
- [ ] 5.8 Repoint each UI: `API_URL` → internal `api` alias, `NEXT_PUBLIC_API_URL` → public `api.` host (§9); confirm `TENANT_ID` per portal.

## Phase 6 — Validation / acceptance — **executed 2026-08-09**

### 🔴 Pre-existing defect found: batch job dispatch had NEVER worked
`kafka-consumer-groups --list` was empty and there was no `__consumer_offsets` topic, while the broker looped forever on
`Sent auto-creation request for Set(__consumer_offsets) to the active controller`.

**Cause:** the Kafka service (deployed ~3 months ago) sets no replication-factor env, so the broker default
`offsets.topic.replication.factor = 3` applied to a **single-node** cluster. `__consumer_offsets` can never be created
→ no consumer group can ever form → **`fineract-batch-manager` → `fineract-batch-worker` dispatch over `job-topic`
silently did nothing**. (Producing to `external-events` still worked — producers don't need the offsets topic.)
This is exactly why `docker-compose-splitstack.yml` sets `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1` and it worked locally.

**Fix applied** to the `kafka_kafka` service: `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1`,
`KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1`, `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1`.
**Verified after restart:** `__consumer_offsets` exists; group `fineract-consumer-group-id` is active with the worker
assigned to `job-topic` partition 0 (`HOST /10.0.3.13`).

### Consumer group — pinned and verified balanced (2026-08-09)
The group id was falling back to Fineract's default `fineract-consumer-group-id`. Left unpinned, a change in that
upstream default would make the workers join a **brand-new group with no committed offsets** and — depending on
`auto.offset.reset` — silently skip or reprocess queued jobs. Now pinned in `fineract-production.env`:
`FINERACT_REMOTE_JOB_MESSAGE_HANDLER_KAFKA_CONSUMER_GROUPID=fineract-worker` (the value used pre-split).
Changed while `LOG-END-OFFSET=0`, so no committed offsets were abandoned.

**Verified after redeploy — `job-topic` has 10 partitions and BOTH workers consume:**
| Consumer | Partitions |
|---|---|
| `consumer-fineract-worker-1-66b931a3…` | 0, 1, 2, 3, 4 |
| `consumer-fineract-worker-1-c1590e2c…` | 5, 6, 7, 8, 9 |

> **Correction:** an earlier note in this document claimed `job-topic` had only 1 partition and that the second worker
> was an idle standby. That was wrong — it was inferred from a single truncated `--describe` row taken while the group
> was mid-rebalance. The topic has **`PartitionCount: 10`** and COB work is split evenly across both workers. No
> partition change is needed; scaling `batch-worker` up to 10 replicas is supported by the current topic.

Also fixed while here: `docker-compose-uat.yml` still carried `replicas: 0` for read/manager/worker from the staged
cutover — re-running `stack deploy` would have **scaled them to zero**. Now set to the steady-state 2 / 1 / 2.

### ✅ Batch dispatch PROVEN end-to-end (2026-08-09, Loan COB on `default`)
Backup taken first (`/root/backups/pre-cob-20260809-1953/fineract_default.dump`, 37 MB) and job 34's original state
saved to `/root/backups/job34-original.txt`. Loan COB was temporarily enabled with a 2-minute cron, then **reverted to
its exact original state** (`is_active=false`, `cron 0 0 0 * * ?`).

**Manager side:** `LoanCOBPartitioner found 18 loans to be processed as part of COB. 1 partitions were created` → job `COMPLETED`.
**Worker side (the proof):**
```
17:56:01 ApplyCommonLockTasklet: Apply locks for [6,17,1,18,3,13,19,15,16,4,11,5,8,10,14,9,7,12]
         by owner LOAN_COB_CHUNK_PROCESSING
17:56:02 Step: [loanCOBWorkerStep:partition_0] executed in 1s474ms          ← thread [ntainer#0-0-C-1]
```
The 18 loan ids match the manager's partition, the timing matches the manager's run, and the executing thread is a
**KafkaMessageListenerContainer** — i.e. the work arrived over `job-topic`, not in-process.

**Kafka offsets confirm the round trip** (2 COB runs → 2 messages, consumed by *both* workers, zero lag):
| Partition | Current | Log-end | Lag | Consumer |
|---|---|---|---|---|
| 1 | 1 | 1 | 0 | worker A `…66b931a3` |
| 8 | 1 | 1 | 0 | worker B `…c1590e2c` |

**Data impact — negligible:** loan statuses unchanged (`100=3 200=2 300=13 500=1`, identical to baseline);
**0** loan transactions and **0** journal entries created; 0 stuck loan locks. The only change is
`last_closed_business_date` stamped `2026-08-09` on 18 loans (the point of COB). No money moved.

> **Tooling gotcha:** `kafka.tools.GetOffsetShell` **does not exist in Kafka 4.x** (moved to `org.apache.kafka.tools.GetOffsetShell`).
> Invoking the old class fails with `ClassNotFoundException`; if the output is piped into an aggregator it silently
> reports **0 messages** and looks like "nothing was dispatched". Use
> `kafka-consumer-groups.sh --describe --group <g>` (CURRENT-OFFSET / LOG-END-OFFSET / LAG) as the authoritative source.

### Business date — enabled and VERIFIED WORKING (2026-08-09, `default` tenant only)
The feature is fully implemented, not a stub: config gate `enable-business-date` (+ `enable-automatic-cob-date-adjustment`),
types `BUSINESS_DATE`/`COB_DATE` in `m_business_date`, REST API `/v1/businessdate`, per-request `BusinessDateFilter`,
and job 33 "Increase COB Date by 1 day" to advance it.

Behaviour of the gate (`BusinessDateReadPlatformServiceImpl`): both dates are seeded with the tenant's current date and
**only overridden from `m_business_date` when the config is enabled**. `BusinessDateWritePlatformServiceImpl.adjustDate()`
throws `business.date.is.not.enabled` if you try to set a date while disabled — so the config must be on first.

**Set on `default`** (Dennis enabled the config; dates seeded directly in `m_business_date`, since the API needs
credentials we don't have). Values follow the standard relationship `COB_DATE = BUSINESS_DATE - 1`:
| type | date |
|---|---|
| `BUSINESS_DATE` | 2026-08-09 (today — avoids backdating new PO transactions) |
| `COB_DATE` | 2026-08-08 |

`type` is `@Enumerated(EnumType.STRING)`, so the column holds the enum **name**; `created_by`/`last_modified_by` are
NOT NULL with an FK to `m_appuser`.

**Proof it is honoured** — a clean discriminating test. With the feature off, COB fell back to today and reported
`found 18 loans to be processed as part of COB`. After seeding the dates it reports:
```
found 0 loans to be processed as part of COB. 1 partitions
```
because those 18 loans carry `last_closed_business_date = 2026-08-09`, which is **not** earlier than `COB_DATE = 2026-08-08`
and so is correctly excluded. Data impact across all 7 COB runs remained nil: loan statuses identical to baseline,
**0** transactions, **0** journal entries, **0** stuck locks. Job 34 restored to `0 0 0 * * ?` / `is_active=false`.

### COB enabled for daily operation (2026-08-09, `default` tenant) — verified live
| Job | State | Why |
|---|---|---|
| 32 Increase Business Date by 1 day | **enabled** | advances `BUSINESS_DATE`; with `enable-automatic-cob-date-adjustment=t` this **also** derives `COB_DATE = BUSINESS_DATE - 1` |
| 34 Loan COB | **enabled** | processes loans up to `COB_DATE` |
| 33 Increase COB Date by 1 day | **left disabled** | `increaseDateByTypeByOneDay()` → `adjustDate()` already moves `COB_DATE` via job 32. Enabling **both** advances `COB_DATE` twice per night, breaking the `COB = BUSINESS - 1` invariant and letting COB close a day that has not ended. Only enable this if auto-adjustment is turned **off**. |
| 42 Working Capital Loan COB | left disabled | 0 working-capital loans on this tenant |

**Observed at the 18:30 UTC boundary** (= midnight in the tenant's UTC+5:30 timezone):
```
18:30:00.001  job 32 -> success   BUSINESS_DATE 2026-08-09 -> 2026-08-10
18:30:00.002  job 34 -> success   COB_DATE      2026-08-08 -> 2026-08-09   (advanced exactly ONCE)
```
Job 32 fired before job 34 — the date advance lands before COB reads it, which is the order you want. COB reported
`found 0 loans` this cycle, correctly: the 18 loans carry `last_closed_business_date = 2026-08-09`, which is not
earlier than `COB_DATE = 2026-08-09`. Data still unchanged (statuses at baseline, 0 transactions).

> **Next cycle is the first with real effect.** At the next 18:30 UTC run `COB_DATE` becomes 2026-08-10, the 18 loans
> (`last_closed = 2026-08-09`) fall inside the window and **will** be processed — accruals and penalties will post for
> the first time. That is intended, but it is the point at which PO-visible financial data starts changing.
> Backup taken beforehand: `/root/backups/pre-cob-enable-20260809-2024/`.
>
> `enable-business-date` remains **false** on `tenant_b`, `tenant_bongobing` and `tenant_sandbox` — tenants are now
> deliberately inconsistent; align them when those tenants need COB.

### Original checklist
- [ ] 6.1 Routing: GET→read, POST→write; kill a read container → GETs 502 while writes stay up (§4.3).
- [ ] 6.2 Read-via-POST caveat: `POST /collectionsheet` and `POST /external-asset-owners/search` → 405 on read, succeed via the split→write (§4.3).
- [ ] 6.3 Batch: trigger a job → manager partitions → worker executes over `job-topic`; confirm consumer group in kafka-ui.
- [ ] 6.4 External events reach `external-events` after enabling types.
- [ ] 6.5 All four PO domains load + login; admin UI reachable (per D3).
- [ ] 6.6 kafka-ui + Traefik dashboard both require auth **and** serve over TLS.
- [ ] 6.7 Portal SSR sends correct `X-Forwarded-Proto`/CORS; login round-trip works end-to-end.

## Phase 7 — Hardening & ops — **executed 2026-08-09**
- [x] 7.1 **`ufw` active** (default deny incoming; 22/80/443 allowed) and **enabled at boot**. Enabled behind a dead-man switch (auto-disable timer, cancelled after a fresh SSH connection verified) — the safe way to turn on a firewall over SSH.
      Verified externally: **2377 + 7946 (swarm control plane) now blocked** — these were the most dangerous exposures.
- [x] 7.2 Log rotation written to `/etc/docker/daemon.json` (`max-size=50m`, `max-file=3`) — **applies to containers created from now on; needs a daemon restart to be global** (deferred: restarting dockerd bounces every container).
      Oversized logs truncated: `/var/lib/docker/containers` **4.8 GB → 41 MB**. Pruned stale containers+images: **~15 GB reclaimed**. Disk **56 GB → 20 GB used (4%)**.
- [x] 7.2b **UI host ports 3000–3003 unpublished** — Traefik fronts them over the overlay, so they no longer need to be internet-facing. Portals verified still serving after removal.
- [x] 7.4 **Nightly backup cron** installed (`02:15`, `/root/build/nightly-backup.sh` → `/var/log/fineract-backup.log`), 7-day retention, dumps every `fineract_*` DB + globals + documents. **Dry-run passed: 162 MB**, and it now picks up `fineract_platform` automatically.
- [x] 7.5 Pending kernel reboot — **already cleared** by the 2026-08-09 11:45 restart.

### ✅ Exposure CLOSED 2026-08-09 (after `portainer.`/`registry.`/`admin.` DNS was created)
`ufw` alone does not cover Docker: swarm ingress traverses `DOCKER-INGRESS`/`DOCKER-USER` *before* ufw's INPUT chain, so `ufw deny <port>` is a no-op for a published port. Fixed by routing everything through Traefik and unpublishing the host ports:

| Was exposed | Now |
|---|---|
| `:3000-3003` UI ports | **unpublished** — served via the 4 portal hosts |
| `:3100` admin-ui | **unpublished** — `https://admin.imarafinance.africa` (TLS) |
| `:9000` Portainer | **unpublished** — `https://portainer.imarafinance.africa` (TLS + `admin-auth` on top of Portainer's own login) |
| `:5000` registry (plaintext basic-auth) | **blocked externally** via `DOCKER-USER`/`DOCKER-INGRESS`; external access is now `https://registry.imarafinance.africa` with real TLS. Kept published locally because every service's image ref is `75.119.154.177:5000/...` — **verified the box can still pull** after the block (locally-originated traffic does not traverse DOCKER-INGRESS). |

**External port audit — only `22`, `80`, `443` reachable.** Everything else (2377, 3000-3003, 3100, 5000, 7946, 9000) blocked.

Rules persist across reboot via `fineract-fw.service` (systemd oneshot, `After=docker.service`, idempotent `-C` then `-I`). A plain `iptables-persistent` restore would fail — the DOCKER-* chains do not exist until dockerd creates them.

**Gotcha recorded:** `docker service update --publish-rm N` matches the **target** port, not the published one. `--publish-rm 3001` on a `3001->3000` mapping silently succeeds and removes nothing; the correct call is `--publish-rm 3000`. Always verify with `docker service ls --format '{{.Ports}}'`.

Registry route deliberately has **no** Traefik basic-auth — the registry runs its own htpasswd and Docker uses a `WWW-Authenticate` handshake that a proxy-level basic-auth would break. Verified the challenge header survives the proxy.

### ❌ Blocked — needs Dennis
- [ ] 7.3a **SSH key-only auth cannot be enabled yet**: `/root/.ssh/authorized_keys` is **empty (0 bytes)** while `passwordauthentication=yes` and `permitrootlogin=yes`. Disabling password auth now would lock **everyone** out permanently. Add a public key first, verify key login, *then* set `PasswordAuthentication no`.
- [ ] 7.3b **Rotate the root password** — it was shared in plaintext at the start of the session and must be considered compromised.
- [ ] 7.4b Alerting (§11: health, PG disk >80%, `job-topic` lag, backup failure) — not configured.
- [ ] Off-box backup copy — backups are on-box only by decision; a VM snapshot is still the only cover for total VM loss.

## Post-cutover cleanup (2026-08-09)
Removed everything belonging to the pre-split deployment:
- **Stacks** `caddy`, `fineract-write` (old all-in-one), `pgbouncer` (old, broken image) — removed at cutover.
- **Networks** `caddy-net`, `caddy_net` (overlay) and `kafka_fineract-net` (a bridge network left by an old non-Swarm
  compose run, 0 containers). Portainer and the registry were first detached from the caddy networks — both are reached
  over `fineract-net` via Traefik now, so the extra attachments were dead weight. **Only `fineract-net` remains** besides
  Docker's own `bridge`/`docker_gwbridge`/`host`/`none`/`ingress`.
- **Volumes**: the 858 MB Postgres dir from the Phase-3 restore test, `caddy_caddy_{data,config}`, an empty `registry_data`,
  and five empty anonymous volumes. 21 exited containers and unused images pruned.
- Disk **56 GB → 19 GB used (4%)**.

**Left deliberately:** an 86 MB Postgres volume dated **2026-01-11** — dangling and almost certainly abandoned, but not
created during this work, so deleting someone else's database was not ours to do.

**Rollback material verified intact:** the registry's `fineract:latest` still resolves to
`sha256:345142846c…`, exactly the pre-cutover image, so a redeploy-to-previous is still possible.

**Portainer's own stack list is separate.** Portainer keeps stack metadata in its internal BoltDB, so `docker stack rm`
leaves orphaned rows behind — `fineract-write`, `fineract-read`, `fineract-worker` may still be listed there. (`fineract-read`
and `fineract-worker` were never live Swarm stacks at all.) They must be deleted from the Portainer UI
(Stacks → select → Remove); they are database rows only and removing them cannot affect anything running.

**Verified after cleanup** — 16 services at desired replicas and every endpoint responding:
`bedrock/imarishamaisha/sandbox/bongobing/admin/registry` → 200, `traefik/kafka/portainer` → 401 (auth enforced),
registry alias still resolving on `fineract-net`.

## Phase 8 — Rollback plan
- **Routing/app failure:** redeploy the Caddy stack + old `fineract-write` (prior image still in registry, tag pinned) — restores the pre-cutover state.
- **Schema migrated + need to go back:** Liquibase is forward-only (§10 rollback note) → restore the Phase 3 `pg_dump` (or VM snapshot) before redeploying the old image.
- **Total loss:** provider snapshot restore.
- Decision owner + comms path named before Phase 5 starts.

---

## Timeline against the 2-day window
- **Before the window (no outage):** Phases 0–4 (repo PR, image builds, backups + restore test, PG role prep).
- **The outage (Phase 5):** target 2–4 h — teardown, staged bring-up per §10, UI repoint.
- **In/after the window:** Phases 6–7 (validation, hardening). Phase 8 on standby throughout.

## Out of scope (parked, §12)
Payment-gateway services · wildcard DNS-01 / final prod domain · managed-Postgres option · SMS provider selection.
