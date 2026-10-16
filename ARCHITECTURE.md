# Architecture

## 1. Shape of the system

```
          Admin console (React)                 Client apps (web / mobile)          Tenant backends
                 │  admin JWT                          │ user JWT                         │ X-API-Key
                 ▼                                     ▼                                  ▼
┌──────────────────────────────────────── Chat node (stateless, N replicas) ───────────────────────────────────────┐
│  Security chain ─ admin JWT · API keys · user JWT · external token exchange (Firebase / JWKS / HS256)              │
│  Controllers (REST · STOMP) ─ thin: map HTTP/frames to use cases, no business rules                              │
│  Application services ─ users · blocks · conversations · membership · messages · receipts · tenant admin         │
│      │ ports: MessageStore · RealtimePublisher · PresenceQuery · ActiveUserCheck                                 │
│  TenantDirectory (cached snapshots: plan ⊕ overrides → limits, features, auth) · QuotaService · UsageMeter       │
│  RealtimeGateway ─ STOMP sessions on this node ◄──── bus "delivery" (broadcast) ◄──── every node                 │
│  PresenceService · ControlChannel · MediaService · WebhookDispatcher · PushDispatcher (bus "events", work queue) │
│  PlatformConnectors ─ one hot-swappable connector per slot · TenantConnectors (per-tenant store / push)          │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
        │ control plane (JPA + Liquibase)        │ data plane connectors (SPI)
        ▼                                        ▼
  tenants, plans, features, API keys,     MessageStore · CacheStore · EventBus · BlobStore · PushSender
  connector configs (secrets encrypted),
  admins, webhooks, audit, usage
```

There are two planes:
- **Control plane:** a relational database owned by the platform. It holds tenants, plans,
  credentials, configuration and audit data.
- **Data plane:** users, conversations and messages. It lives in whatever message store each
  tenant is routed to.

## 2. The connector model ("plug and play")

Every external dependency sits behind an SPI (`MessageStore`, `CacheStore`, `EventBus`, `BlobStore`,
`PushSender`). A provider implements the SPI and registers a `ConnectorFactory` bean whose
`ConnectorDescriptor` declares:
- its id, its compatible products and its maturity;
- a **config schema**: fields with types, required flags, defaults, and whether each is secret.

The dashboard renders forms from that schema, so a new provider needs no UI work.

Configuration is layered:

1. **Bootstrap**: `platform.connectors.*` in `application.yml`, overridable through environment variables.
2. **Platform override**: set from the dashboard and stored in `platform_connector_configs`.
   Secrets are AES-256-GCM encrypted with the master key. API reads mask them as `********`;
   sending the mask back keeps the stored value.
3. **Tenant override**: the message store and push slots only. This gives a tenant a dedicated
   database or its own FCM project.

`ConnectorHolder` performs the hot swap:
- **Connect first.** The new connector is created and connected before anything changes; a
  failure returns 400 and the old connector stays active.
- **Persist only on success.** The config is saved only once the connection works.
- **Grace period.** In-flight callers finish on the old instance, which is closed after
  `platform.connector-runtime.retire-grace` (30 s).

Other nodes detect the new config version by polling the control plane every 5 s. Polling is
deliberately independent of the event bus, since the bus may be the very thing being reconfigured.

At startup:
- Cache, bus, media and push fall back to safe local providers if the configured one is
  unreachable, and the dashboard shows the error.
- The message store never falls back silently. Writing chats into the wrong database is worse
  than returning 503.

## 3. Message lifecycle

```
client ──SEND /app/messages.send {conversationId, clientMsgId, content}──► node A
node A: authN (CONNECT) → tenant usable → member? → blocked? → feature gate → rate limit → daily quota
        store.appendMessage(...)            ← atomic: seq = lastSeq+1, dedupe on clientMsgId,
                                              conversation preview updated, sender watermarks advanced
        ──► sender: message.ack {clientMsgId, message{id, seq}}     ("single tick": durably stored)
        ──► bus "delivery" {tenant, participants, message.new}       (key = tenant:conversation → ordered)
every node: deliver to recipients connected locally ──► recipient sessions (incl. sender's other devices)
        ──► bus "events" (work queue) → webhooks (HMAC, retries) · push for offline, unmuted recipients
recipient ──SEND /app/receipts {seq, kind: delivered|read}──► watermark advanced ──► receipt event to participants
```

**Ordering.** Each conversation has a dense, strictly increasing `seq`, allocated inside the
store:
- SQL: the conversation row lock inside a transaction.
- MongoDB: `findOneAndUpdate $inc`.
- Cassandra: an LWT compare-and-set loop.
- Firestore: a transaction.

Clients order by `seq`, page history with `before=seq`, and catch up after a reconnect with
`after=lastSeenSeq`. That makes gaps detectable, which wall-clock ordering can't do.

**Idempotency.** `clientMsgId` is unique per (tenant, conversation, sender). A retried send returns
the original message with `duplicate=true` and is never charged against the quota twice.

**Receipts are watermarks.** Each participant stores `deliveredSeq` and `readSeq`. A message is
read by a user when `seq ≤ readSeq`. That makes group receipts O(1) per reader rather than
O(messages × members), and unread count is `lastSeq − readSeq`. Sending advances the sender's own
watermarks.

**Membership history.** A member who joins later gets `joinedSeq = lastSeq` and does not see
earlier history, as in WhatsApp groups. System messages record membership changes.

## 4. Realtime and scale-out

- Nodes are stateless. Each WebSocket lives on one node, so a load balancer needs no sticky sessions.
- Every realtime event is published once on the bus, keyed by `tenant:conversation`, which keeps
  per-conversation order. Each node delivers it to the recipients that have a session on that
  node; the STOMP user registry tells it who is local.
- STOMP user names are `tenant::userId`, so user destinations can never cross tenants. Clients may
  subscribe only to `/user/queue/events`; subscriptions to anything else are rejected.
- **Presence.** A node holding a user's sessions keeps `presence:<tenant>:<user>` alive in the
  shared cache, with a 60 s TTL refreshed every 20 s. Clients *watch* the users they care about
  (for example the open chat) and get `presence` events. Nothing is broadcast to all contacts.
- **Cluster membership.** Each node claims a slot through an atomic counter and heartbeats its
  connection counts to `cluster:node:<slot>` with a TTL. Any node can then aggregate cluster-wide
  numbers to enforce `maxConnections` and render the dashboard, with no coordinator. The
  numbers are eventually consistent, within about 10 s.
- **Control channel.** Administrative changes (tenant updated or suspended, plan edited, user
  deactivated, membership, blocks, webhooks) are broadcast as typed `ControlMessage`s **after the
  transaction commits**. Every node then drops its cached copies and closes affected sockets.
  Unknown message types from a newer node are ignored, so rolling upgrades are safe. Node-local
  caches also expire on their own (`platform.caches.*`, 10–30 s) as a backstop.
- **Migrations.** Nodes that start together against a fresh control plane retry Liquibase, since
  its lock table cannot protect its own creation.

Verified: `ClusterSmokeIT` runs two nodes sharing PostgreSQL and Redis. It checks cross-node
delivery, receipts, presence, cluster connection counts, and suspension closing sockets on the
other node.

**Scaling further.**
- **Bus:** Kafka partitions `delivery` and `events` by key. For very large fleets, replace
  broadcast delivery with node-addressed routing (a session registry in the cache, one topic or
  channel per node).
- **Store:** use Cassandra/ScyllaDB for message history at very large scale (partitions are
  bucketed by `seq`), or shard PostgreSQL by tenant using dedicated stores.
- **Push and webhooks:** these are work-queue consumers and scale with the number of nodes.

## 5. Multi-tenancy and security

- **Tenant from credentials.** The tenant always comes from the credential (API key or a signed
  `tid` JWT claim), never from a client-supplied header.
- **Shared-store isolation.** Every data-plane row is keyed by `tenant_id`. Tenants can also be
  routed to a dedicated store.
- **Feature gates.** Effective features are the core set, plus the plan bundle, plus or minus the
  tenant's overrides. `PLANNED` features are visible but cannot be enabled.
- **Quotas.** Enforced cluster-wide with cache counters: users, messages per day, connections,
  storage, group size, and messages per second per user. Usage is metered locally and flushed
  every 30 s to `platform_usage_daily`, so the control plane never sees per-message writes.
- **Credentials.**
  - Tenant API keys are stored as SHA-256 hashes; the full key is shown once.
  - Admin roles are `OWNER`, `ADMIN` and `VIEWER` (read-only).
  - Admin login is throttled by email and IP.
  - Admin JWTs and user JWTs have different audiences, so one can't be replayed as the other.
- **External auth.**
  - Firebase ID tokens are verified against Google's JWKS, with the issuer and audience pinned to
    the project.
  - Tenant JWTs are verified with either a JWKS URL (RS/ES) or an HS256 secret of at least 32
    characters.
  - Users are provisioned just-in-time.
- **SSRF guard.** Tenant-supplied URLs (webhooks, the push webhook, JWKS) may not resolve to
  loopback, private, link-local (cloud metadata), CGNAT or reserved addresses unless the operator
  allows it. Tenant webhooks and the push webhook are checked at save time and again before every
  request, since DNS can change in between.
- **Keys per purpose.** `KeyRing` derives separate 256-bit keys from the master key for secret
  encryption (AES-GCM), media URL signing and (when no JWT secret is configured) JWT signing, so a
  key exposed in one role is useless in another.
- **Media.** Files get unguessable ids and HMAC-signed capability URLs that work in `<img src>`. The
  URLs don't expire, like attachment links in consumer messengers; revoke a file by deleting the
  blob. Only raster images, video and audio are served inline. Everything else (SVG, HTML, PDF, …)
  is a download, and every media response carries `Content-Security-Policy: sandbox`, so an
  upload can never run script on the API/dashboard origin. When S3 is configured, downloads
  redirect to presigned URLs on the storage origin.
- **Realtime lockdown.** Clients may `SEND` only to `/app/**` and `SUBSCRIBE` only to
  `/user/queue/events`. Without the first rule, a client could address another user's queue
  directly.
- **Revocation.** Every request re-checks, from short caches invalidated cluster-wide, that the
  tenant is usable, the end user is active and the admin still exists. Admin roles come from the
  database, not the token.
- **Forwarded headers.** `X-Forwarded-For` is trusted only from internal proxies (Tomcat
  RemoteIpValve). Admin login is throttled per account+IP and per account.
- **Audit.** Every admin mutation is written to `platform_audit_log` by the application service
  that performs it, inside the same transaction, so a failed action leaves no entry. This includes
  impersonation tokens issued from the dashboard and user deactivations from the server API.
  High-volume server-API calls (user upserts, token minting) are metered rather than audited.
- **Token lifetimes.** Every end-user token, whoever mints it, goes through `UserTokenService`
  and must lie within `platform.security.min/max-user-token-ttl` (5 min – 30 days by default).

## 6. Data model (message store)

| Entity | Key | Notes |
|---|---|---|
| user | (tenant, id) | `id` is the tenant's own user id; profile, push tokens, last seen, deactivated |
| block | (tenant, blocker, blocked) | |
| conversation | (tenant, id) | DIRECT has a unique `directKey = min(a,b)|max(a,b)`; `lastSeq`, last-message preview, activity time |
| participant | (tenant, conversation, user) | role (OWNER/ADMIN/MEMBER), `joinedSeq`, `deliveredSeq`, `readSeq`, muted/archived/pinned, `leftAt` |
| message | (tenant, conversation, seq) | unique id; unique (sender, clientMsgId); type, content, attachment, replyTo, reactions, edit/delete state, metadata |

SQL stores timestamps as epoch milliseconds (`BIGINT`) to avoid timezone drift between H2,
PostgreSQL and MySQL. The schema is created on connect, with an optional table prefix so several
deployments can share one schema.

## 7. Concurrency and consistency notes

- **Shared-record updates.** Edits, reactions and deletes on a message, and membership or settings
  changes on a conversation, run under a short cluster-wide lock in the cache. The store contract
  also makes `upsertParticipant` merge receipt watermarks with `max()`, so no write moves a receipt
  backwards.
- **Node-local caches** (tenant snapshots, membership, block state, webhook targets, user status)
  are invalidated on every node through the control channel. Their TTLs (10 to 30 s) only bound
  staleness if a broadcast is missed.
- **Webhook delivery** is at-least-once while the dispatching node is running. Requests run on
  virtual threads. Retries (`platform.webhooks.*`: 5 attempts, exponential backoff from 10 s) are
  scheduled in memory, so a node restart drops pending retries. With the
  Kafka bus, an event that hasn't been dispatched yet is redelivered to another member of the
  group.
- **Inbox paging** uses the activity timestamp as its cursor, so conversations that share the exact
  boundary millisecond can be skipped by `before=`. A compound cursor is on the roadmap.

## 8. Quality gates

- `MessageStoreConformanceTest`: 26 behavioural tests, including 120 concurrent appends that must
  produce contiguous, unique seqs, concurrent direct-chat creation and concurrent first upserts.
  They run against every store: in-memory, H2, PostgreSQL 16, MySQL 9, and MongoDB (wire-protocol
  fake). Cassandra and Firestore run when their servers are available.
- `CacheStoreConformanceTest` and `EventBusConformanceTest` cover memory, Redis and embedded Kafka;
  S3, FCM and the push webhook run their real client libraries against protocol-level fakes.
- `MessagingServicesTest`: the messaging rules (ordering, idempotency, blocks, quotas, roles,
  history visibility, receipts) on an in-memory store with recording fakes — no Spring, no broker.
- `PlatformIT`: 17 end-to-end scenarios over real HTTP and STOMP; on H2 by default and on
  PostgreSQL + Redis in CI.
- `ClusterSmokeIT`: two live nodes. `LoadSmokeIT`: throughput and latency against a running node.
- Console: 79 Vitest tests (state reducer, chat engine, realtime backoff, URL safety), lint with
  `jsx-a11y`, strict type-check.

## 9. Performance baseline

`LoadSmokeIT` ran against one node on a developer laptop, with PostgreSQL 16, Redis and the load
generator (up to 100 STOMP sockets) **all on the same machine**. These are a floor for capacity
planning, not a benchmark.

| Offered load | Throughput | send→delivery p50 | p99 |
|---|---|---|---|
| ~1.25k msg/s (50 sockets) | 1.1k msg/s | 2.4 ms | 90 ms (cold JVM) |
| ~2.5k msg/s (100 sockets) | 2.3k msg/s | 3.4 ms | 54 ms |
| ~5k msg/s (100 sockets) | 3.9k msg/s (saturated) | queueing | queueing |

Each accepted message costs one fresh membership read plus a single append transaction (four
statements; three on PostgreSQL thanks to `RETURNING`). Hot reads (user status, block state,
conversation identity, tenant snapshot) are served from short-lived local caches. Throughput
scales out with nodes; the message store's write capacity then becomes the limit. Past that
point, give large tenants dedicated stores or move to Cassandra/ScyllaDB.

Sends from one socket are processed in receive order, so a sender's messages always get
increasing seqs. Events to one socket are published in order.

## 10. Adding a provider

1. Implement the SPI (for example `MessageStore`) in a new package.
2. Add a `@Component` implementing `ConnectorFactory<T>` with a descriptor and its config fields.
3. Subclass the conformance test for your provider, and make it pass.

It then appears in the dashboard automatically, and can be selected through config or at runtime.
[CONTRIBUTING.md](CONTRIBUTING.md) has the details (error handling, maturity, tests).

## 11. Code organisation

The backend is a modular monolith with hexagonal boundaries:

| Layer | Lives in | Rules |
|---|---|---|
| **Adapters in** | `chat.api`, `chat.ws`, `admin.*` controllers | Translate HTTP / STOMP to a use case and back. No business rules, no transactions, typed request and response records. |
| **Application services** | `chat.application`, `admin.tenant.TenantAdminService`, `tenant.*Service`, `auth.*Service` | One use case per method. Own the transaction, enforce feature gates and quotas, record audit entries, publish side effects after commit. |
| **Domain model** | `store.model`, `tenant` value objects (`TenantSnapshot`, `TenantLimits`, `TenantConfig`) | Plain data with small invariants; no framework types. |
| **Ports** | `store.MessageStore` and the other SPIs, `chat.port.*`, `auth.ActiveUserCheck` | Interfaces owned by the code that needs them. |
| **Adapters out** | `store.*`, `cache.*`, `bus.*`, `blob.*`, `push.*`, `chat.realtime` | Implement ports; translate vendor failures into `InfrastructureUnavailableException` (503). |
| **Core** | `core.*` | Connector runtime, configuration, crypto, errors, cluster helpers. Depends on nothing above it. |

Errors are `DomainException`s carrying an `ErrorCode`; only `ErrorHttpMapping` knows HTTP
statuses. Every tunable is a `platform.*` property. See
[docs/ENGINEERING_REVIEW.md](docs/ENGINEERING_REVIEW.md) for the review behind this structure.
