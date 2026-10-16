# Chat Platform

A plug-and-play, multi-tenant messaging platform: the backend for WhatsApp-style chat, sold as
SaaS or self-hosted. Every infrastructure dependency is a **connector** you pick in config or
switch live from the admin dashboard. That covers the message database, cache, event bus, media
storage and push provider. Nothing is hard-wired to a vendor.

- **Messaging:** 1:1 and group chats with per-conversation ordered sequences and idempotent sends.
  Delivered/read receipts, typing, presence and last seen. Replies, reactions, edits,
  delete-for-everyone, media and files, blocking, and offline sync.
- **Realtime:** STOMP over WebSocket on stateless nodes. Delivery fans out across the cluster over
  the event bus to whichever node holds the recipient's socket.
- **Multi-tenant SaaS:** tenants on plans with feature bundles and per-tenant overrides. Quotas cover
  users, messages per day, connections, storage, group size and send rate. Tenants can also
  bring their own database or push provider.
- **Integrations:** a server API (tenant API keys), user tokens minted by the tenant's backend,
  bring-your-own-auth (Firebase / your own JWT via JWKS or HS256), and HMAC-signed webhooks with
  retries.
- **Operations:** React admin console, audit log, Prometheus metrics, health per connector,
  cluster view, Docker/Compose/Kubernetes manifests.

| Slot | Providers | Compatible products |
|---|---|---|
| Message store | `h2`, `postgresql`, `mysql`, `supabase`, `mongodb`, `cassandra`*, `firestore`*, `memory` | Neon, Aurora, Cloud SQL, CockroachDB, YugabyteDB, MariaDB, PlanetScale, TiDB, Atlas, DocumentDB, Cosmos (Mongo API), ScyllaDB, Keyspaces |
| Cache | `memory`, `redis` | Valkey, KeyDB, Dragonfly, ElastiCache, Upstash |
| Event bus | `memory`, `redis`, `kafka` | Redpanda, MSK, Confluent Cloud, Event Hubs |
| Media storage | `local`, `s3` | MinIO, Cloudflare R2, Spaces, B2, GCS (interop) |
| Push | `none`, `fcm`, `webhook` | Firebase Cloud Messaging; anything behind an HTTP hook (APNs, OneSignal, …) |

\* Beta. Implemented against the same contract test suite, but not exercised against a live
server in CI here. Every other provider passes the full conformance suite against a real server
or an in-process equivalent.

## Quick start

Requirements: Java 21, Maven 3.9, Node 20 (for the dashboard).

```bash
# Backend: zero external services (H2 files + in-memory cache/bus + local media under ./data)
mvn spring-boot:run
# → http://localhost:8080   (Swagger UI: /swagger-ui.html)

# Dashboard (dev server with API proxy)
cd frontend && npm install && npm run dev
# → http://localhost:5173   login: admin@local / admin12345  (change it after first login)
```

Or with Docker, which serves the dashboard and API from one image on :8080:

```bash
docker compose up --build
docker compose --profile postgres --profile redis up --build   # after copying .env.example → .env and enabling the preset
docker compose --profile cluster --profile postgres --profile redis up --build   # two nodes + nginx on :8088
```

## From zero to a working chat in five calls

```bash
BASE=http://localhost:8080
ADMIN=$(curl -s -X POST $BASE/api/admin/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@local","password":"admin12345"}' | jq -r .token)

# 1. Create a tenant (returns a one-time server API key)
KEY=$(curl -s -X POST $BASE/api/admin/tenants -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"Acme","planCode":"starter"}' | jq -r .apiKey.key)

# 2. Your backend registers users and mints tokens for them
curl -s -X POST $BASE/api/v1/server/users -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"id":"alice","displayName":"Alice"}'
curl -s -X POST $BASE/api/v1/server/users -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"id":"bob","displayName":"Bob"}'
ALICE=$(curl -s -X POST $BASE/api/v1/server/tokens -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"userId":"alice"}' | jq -r .token)

# 3. The client app opens a chat and sends a message
CONV=$(curl -s -X POST $BASE/api/v1/conversations/direct -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{"userId":"bob"}' | jq -r .id)
curl -s -X POST $BASE/api/v1/conversations/$CONV/messages -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"clientMsgId":"c-1","content":"Hello Bob"}'
```

Realtime client (browser or Node), using `@stomp/stompjs`:

```js
import { Client } from '@stomp/stompjs';
const client = new Client({
  brokerURL: 'ws://localhost:8080/ws',
  connectHeaders: { Authorization: `Bearer ${userToken}` },
  onConnect: () => {
    client.subscribe('/user/queue/events', (frame) => {
      const event = JSON.parse(frame.body);   // { type: 'message.new' | 'message.ack' | 'receipt' | 'typing' | ..., data }
      console.log(event.type, event.data);
    });
    client.publish({ destination: '/app/messages.send',
      body: JSON.stringify({ conversationId, clientMsgId: crypto.randomUUID(), content: 'Hi!' }) });
  },
});
client.activate();
```

The full contract (admin, server, client, realtime, webhooks) is in [docs/API.md](docs/API.md).
The design and its trade-offs are in [ARCHITECTURE.md](ARCHITECTURE.md).

## The admin console

`frontend/` is the operator dashboard:
- **Overview:** connector health, cluster nodes, live throughput.
- **Infrastructure:** pick a provider for each slot. Forms are generated from the connector's
  schema; test the connection, then apply it with no restart.
- **Tenants:** plan and limits, feature toggles, a dedicated database or push provider,
  authentication, API keys, webhooks, users, branding.
- **Plans, admins and the audit log.**
- **Chat playground:** chat in real time as any tenant user to see ticks, typing, presence,
  reactions and media working.

## Configuration

All settings are environment variables; [.env.example](.env.example) documents every one, with
presets. The essentials for production:

| Variable | Purpose |
|---|---|
| `PLATFORM_MASTER_KEY` | Encrypts connector secrets at rest and signs media URLs. 32+ random chars, same on all nodes. |
| `PLATFORM_JWT_SECRET` | Signs admin and user tokens. Same on all nodes. |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | First owner account (created only when no admin exists). |
| `CONTROL_DB_URL` (+ `_USERNAME`, `_PASSWORD`) | Control plane: PostgreSQL or MySQL in production. |
| `MESSAGE_STORE_PROVIDER`, `CACHE_PROVIDER`, `EVENT_BUS_PROVIDER`, `BLOB_STORE_PROVIDER`, `PUSH_PROVIDER` | Bootstrap connectors (the dashboard can override them at runtime). |
| `PUBLIC_URL` | Externally reachable base URL (used in media links). |

Running more than one node requires a shared control-plane DB and message store, plus `redis`
(or `kafka`) for the cache and bus. The in-memory providers are single-node only.

## Testing

```bash
mvn test        # unit, conformance and end-to-end suites (H2, in-memory, embedded Kafka, Mongo fake)
```

Optional live backends turn on more suites automatically:

| Env | Enables |
|---|---|
| `TEST_POSTGRES_URL`, `TEST_POSTGRES_USER`, `TEST_POSTGRES_PASSWORD` | Message-store conformance on PostgreSQL |
| `TEST_MYSQL_URL`, `TEST_MYSQL_USER`, `TEST_MYSQL_PASSWORD` | … on MySQL / MariaDB |
| `REDIS_URL` | Redis cache + Redis event bus conformance |
| `CASSANDRA_CONTACT_POINTS` | Cassandra / ScyllaDB conformance |
| `FIRESTORE_EMULATOR_HOST` | Firestore conformance |
| `S3_TEST_BUCKET` | S3 integration |
| `CLUSTER_NODE_A`, `CLUSTER_NODE_B` | Two-node cluster smoke test against running nodes |

## Project layout

```
src/main/java/com/ayush/chat/
├── core/        connector framework, layered config, crypto, cluster membership, errors, SSRF policy
├── store/       MessageStore SPI + memory, sql (H2/PostgreSQL/MySQL/Supabase), mongo, cassandra, firestore
├── cache/       CacheStore SPI + memory, redis
├── bus/         EventBus SPI + memory, redis, kafka
├── blob/        BlobStore SPI + local, s3
├── push/        PushSender SPI + none, fcm, webhook
├── tenant/      tenants, plans, features, limits, usage metering
├── auth/        admin login, API keys, JWTs, external token verification, security chain
├── chat/        messaging domain, realtime gateway, presence, media, webhooks, push dispatch, REST + STOMP
└── admin/       admin API (system, infrastructure, tenants, plans, audit)
frontend/        React admin console + chat playground
deploy/          nginx, Kubernetes, Postgres init
docs/            API reference and the implementation plan
```

## Roadmap

Voice/video calling, broadcast channels, full-text search, E2E key management (payloads are
already opaque, so clients can encrypt end-to-end today), billing integration (Stripe) and mobile SDKs.
