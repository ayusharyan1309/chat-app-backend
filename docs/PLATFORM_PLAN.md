# Chat Platform v2 — Plan

> Historical: the plan this version was built from. Since then the code was restructured (for
> example `ChatService` is now six focused services); the current design is in
> [ARCHITECTURE.md](../ARCHITECTURE.md) and the changes in [ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md).

Goal: a plug-and-play, multi-tenant messaging platform (WhatsApp-class design) where **every
infrastructure dependency is a pluggable connector** selected by configuration or from the admin
dashboard at runtime — no code changes, no redeploys.

## 1. Audit of v1 (what was there, what was broken)

| Area | State in v1 | Problem |
|---|---|---|
| Boot | `STDBConfig` + `DynamicDataSourceConfig` both declare `@Primary` DataSource; `kafka.*` props undefined; `FirebaseAuth` bean never defined | **App does not start** (`contextLoads` fails) |
| Chat path | WS → Kafka → JPA entities (`User/Message/Conversation`), Firebase-only auth | Hard-wired to MySQL+Kafka+Firebase; never uses the storage SPI |
| Storage SPI | SQL / Mongo / Firebase / Supabase providers | SQL uses H2-only `MERGE … KEY`; Mongo `.filter()` overwrites the conversation filter; Supabase parses JSON by string-splitting; 1:1 only, no ordering/idempotency |
| Tenancy | Tenant entity + interceptor | Tenant DB always in-memory H2 regardless of config; header-based tenant = spoofable |
| Feature flags | In-memory map | Not persisted; keyed by id in one place and identifier in another, so checks never match |
| Security | `@PreAuthorize` annotations only | No filter chain, no admin login, no API keys, WS destinations not tenant-isolated |
| Redis / media / push / webhooks / quotas / metrics / deploy | Declared in docs | Not implemented |

## 2. Target architecture

```
                 ┌────────────── Admin Dashboard (React) ──────────────┐
                 │ infra connectors · tenants · plans · keys · audit   │
                 └──────────────────────────┬──────────────────────────┘
 Client apps ── REST /api/v1 + STOMP /ws ──►│
 Tenant backends ── X-API-Key /api/v1/server│
                                            ▼
┌─────────────────────────────── Chat Node (stateless, N replicas) ─────────────────────────────┐
│ Security: admin JWT · tenant API keys · user JWT (platform / Firebase / external JWT exchange)│
│ Tenant context (from credentials, never from a bare header) · quotas · rate limits · flags     │
│ ChatService: direct+group conversations, per-conversation seq, idempotent send, receipts,     │
│              edits/deletes/reactions/replies, blocks, typing, presence, media, push, webhooks │
│ DeliveryRouter ──► EventBus (fan-out to the node holding the socket) ──► local STOMP sessions │
├──────────────────────────────── Connector SPI (hot-swappable) ─────────────────────────────────┤
│ MESSAGE STORE  memory · h2 · postgresql(+Supabase/Neon/Cockroach/Yugabyte) · mysql(+MariaDB/  │
│                TiDB) · mongodb(+DocumentDB/Cosmos) · cassandra(+ScyllaDB) · firestore         │
│ CACHE          memory · redis(+Valkey/KeyDB/Dragonfly/ElastiCache)                            │
│ EVENT BUS      memory · kafka(+Redpanda/MSK/Confluent) · redis pub/sub                        │
│ BLOB STORE     local filesystem · s3(+MinIO/R2/GCS-interop)                                   │
│ PUSH           none · fcm · webhook                                                           │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
 Control plane DB (JPA + Liquibase; H2 file default, Postgres/MySQL in prod):
 tenants · plans · features · component configs (secrets AES-GCM encrypted) · API keys ·
 admin users · webhooks + deliveries · audit log
```

### Key design decisions
- **Connector descriptors.** Each provider declares its config schema (fields, types, secrets,
  defaults). The dashboard renders forms from that schema, so adding a new database means
  implementing one factory class. Nothing changes in the UI.
- **Layered config.** `application.yml`/env (bootstrap) → platform config in control-plane DB
  (dashboard) → per-tenant override (message store, auth, push). Nodes poll a config version so
  a change on one node propagates to all, independent of the broker being reconfigured.
- **Zero-dependency boot.** Defaults are H2-file + in-memory cache/bus + local blobs. Every
  external system is opt-in.
- **Ordering & sync (WhatsApp-style).** Dense per-conversation `seq` allocated atomically in the
  store (SQL row lock / Mongo `$inc` / Cassandra LWT). Clients sync with `after=seq`; unread =
  `lastSeq − readSeq`; read/delivered are per-participant watermarks rather than per-message
  flags, which keeps group receipts O(1).
- **Idempotent send.** Client supplies `clientMsgId`; a unique index makes retries safe; the
  server acks with `{messageId, seq}`.
- **Persist-then-fan-out.** The ack means durably stored (single tick). Fan-out goes over the
  event bus to whichever node holds the recipient's socket. Offline recipients get push and sync
  on reconnect.
- **Tenant isolation.** Tenant comes from credentials (API key / signed JWT claim). WS user
  destinations are namespaced `tenant::user`. Every store row carries `tenant_id`, and tenants may
  bring their own database.
- **Plug-in auth.** Platform-minted tokens (tenant backend calls `/server/tokens` with its API
  key), Firebase ID tokens, or the tenant's own HS256/RS256 JWT (JWKS). External tokens are
  exchanged for a platform token and users are provisioned just-in-time.

## 3. Work breakdown

1. **Foundation**: fix boot; Java 21; remove legacy JPA/Firebase/Kafka chat path; control-plane
   Liquibase schema; secret encryption; connector framework + registry + config service.
2. **Message store SPI v2** (users, conversations, participants, messages, receipts, blocks,
   stats) + providers: memory, SQL (H2/PG/MySQL dialects), Mongo, Cassandra, Firestore.
   A shared conformance test suite runs against every provider.
3. **Cache, Event bus, Blob, Push** SPIs + providers + tests (embedded Kafka, local Redis).
4. **Security**: admin login (bootstrap admin), tenant API keys (hashed), user JWT, external
   token exchange, Spring Security chain, STOMP CONNECT auth.
5. **Chat domain**: REST `/api/v1` + STOMP; groups; receipts; typing; presence; media; push;
   webhooks (HMAC-signed, retries); quotas (users, msgs/day, connections, storage); rate limits;
   feature gating with plan bundles + tenant overrides.
6. **Admin API**: system overview/health, infra connectors (get/test/apply), tenants (CRUD,
   storage override, features, limits, auth, keys, webhooks, users), plans, audit, metrics.
7. **Observability**: actuator health per connector, Prometheus metrics, live throughput series.
8. **Dashboard (React)**: login, overview, infrastructure, tenants (detail tabs), plans, audit,
   chat playground (real-time, two-user test).
9. **Ops**: Dockerfile, docker-compose with profiles for every backend, k8s manifests,
   `.env.example`, README/ARCHITECTURE rewrite, API reference.
10. **Verification**: unit + conformance + full-context integration tests (multi-tenant isolation,
    WS round trip, receipts, quotas, feature gating), plus a manual dashboard run.

## 4. Explicitly out of scope (documented as roadmap)
Voice/video calls, E2E encryption key management (the payload is opaque, so clients may send
ciphertext), full-text search, AI modules, billing provider integration (Stripe), mobile SDKs.
