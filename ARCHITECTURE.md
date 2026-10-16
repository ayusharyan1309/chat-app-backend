# Architecture

## 1. Shape of the system

```
          Admin console (React)                 Client apps (web / mobile)          Tenant backends
                 │  admin JWT                          │ user JWT                         │ X-API-Key
                 ▼                                     ▼                                  ▼
┌──────────────────────────────────────── Chat node (stateless, N replicas) ───────────────────────────────────────┐
│  Security chain ─ admin JWT · API keys · user JWT · external token exchange (Firebase / JWKS / HS256)              │
│  TenantDirectory (cached snapshots: plan ⊕ overrides → limits, features, auth)                                   │
│  ChatService ─ conversations · groups · messages · receipts · typing · blocks · quotas · feature gates            │
│  RealtimeGateway ─ STOMP sessions on this node ◄──── event bus "delivery" (broadcast) ◄──── every node            │
│  PresenceService · MediaService · WebhookService · PushDispatcher (event bus "events", work queue)                │
│  InfrastructureService ─ one hot-swappable connector per slot · TenantConnectors (per-tenant store / push)        │
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
- **Grace period.** In-flight callers finish on the old instance, which is closed after 30 s.

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
- **Control channel.** Suspending a tenant or deactivating a user is broadcast on the bus. Every
  node then invalidates its caches and closes the affected sockets. Snapshot caches also expire
  after 15 s as a backstop.
- **Migrations.** Nodes that start together against a fresh control plane retry Liquibase, since
  its lock table cannot protect its own creation.

Verified: `ClusterSmokeTest` runs two nodes sharing PostgreSQL and Redis. It checks cross-node
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
  allows it. Webhook targets are checked at save time and again at send time.
- **Media.** Files get unguessable ids and HMAC-signed capability URLs that work in `<img src>`.
  When S3 is configured, downloads redirect to presigned URLs.
- **Audit.** Every admin and server-API mutation is written to `platform_audit_log`, including
  impersonation tokens issued from the dashboard.

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

## 7. Quality gates

- `MessageStoreConformanceTest`: 21 behavioural tests, including 120 concurrent appends that must
  produce contiguous, unique seqs. They run against every store: in-memory, H2, PostgreSQL 16,
  MySQL 9.5, and MongoDB (wire-protocol fake). Cassandra and Firestore run when their servers are
  available.
- `CacheStoreConformanceTest` and `EventBusConformanceTest` cover memory, Redis and embedded Kafka.
- `PlatformIntegrationTest`: 11 end-to-end scenarios over real HTTP and STOMP.
- `ClusterSmokeTest`: two live nodes.

## 8. Adding a provider

1. Implement the SPI (for example `MessageStore`) in a new package.
2. Add a `@Component` implementing `ConnectorFactory<T>` with a descriptor and its config fields.
3. Subclass the conformance test for your provider, and make it pass.

It then appears in the dashboard automatically, and can be selected through config or at runtime.
