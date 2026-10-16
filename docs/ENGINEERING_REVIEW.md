# Engineering review (v2.0)

A design and code-quality review of the whole repository: backend, connectors and operator console.
This file records what was found, what changed, and what is deliberately deferred, so the reasoning
survives the diff.

## Principles the code now follows

| Principle | How it shows up |
|---|---|
| **Hexagonal core** | Messaging services depend on ports (`MessageStore`, `RealtimePublisher`, `PresenceQuery`, `ActiveUserCheck`), never on STOMP, the bus or a vendor SDK. Adapters live at the edges. |
| **One reason to change per class** | The 1,000-line `ChatService` became `UserDirectoryService`, `BlockService`, `ConversationService`, `MembershipService`, `MessageService` and `ReceiptService` over small shared collaborators (`TenantScope`, `ConversationAccess`, `ChatViews`, `ConversationNotifier`). The 400-line tenant admin controller became five controllers over one application service. |
| **Errors are domain values** | `DomainException` + `ErrorCode`; HTTP status is decided in one place (`ErrorHttpMapping`). Backend outages are `InfrastructureUnavailableException` → 503 with a vendor-neutral message. |
| **Typed everything** | No `Map<String, Object>` API responses; enums instead of magic strings (`ConfigScope`, `ConfigSource`, `Maturity`, `ControlType`, `BusTopic`, `ExternalAuthType`, `FeatureSource`, `AuditAction`); JSON columns mapped with JPA converters instead of hand-parsed strings. |
| **Configuration, not constants** | Every TTL, pool size, retry policy and interval is a `platform.*` property with a documented default (`PlatformProperties`, `application.yml`). |
| **Transactions and side effects** | Application services own transactions; cache invalidations and cluster broadcasts run **after commit** (`AfterCommit`, `@TransactionalEventListener`), so no node can reload pre-commit state. |
| **Least knowledge between modules** | `auth` no longer depends on `chat` (port `ActiveUserCheck`); `audit` no longer depends on `auth` (`AuditActor`); no `ObjectProvider` cycle-breaking workarounds remain. |
| **Keys per purpose** | `KeyRing` derives independent keys for secret encryption, media URL signing and JWTs from the master key. |

## Findings and fixes

### Architecture

| # | Finding | Fix |
|---|---|---|
| A1 | God class: `ChatService` mixed users, blocks, conversations, membership, messages, receipts, caching and notification (1,015 lines). | Split into six services plus shared collaborators in `chat.application`. |
| A2 | Application code called STOMP/bus classes directly; impossible to unit-test the domain. | Ports `RealtimePublisher` and `PresenceQuery`; `MessagingServicesTest` runs the domain on an in-memory store with recording fakes. |
| A3 | Circular bean graph (security chain ↔ admin service ↔ password encoder; STOMP config ↔ chat ↔ messaging template) papered over with `ObjectProvider`. | `PasswordConfig`, `AdminDirectory`, `ActiveUserCheck` port; cycles removed. |
| A4 | Admin controllers held business logic, transactions and auditing (create tenant + key was two transactions). | `TenantAdminService` (one transaction per operator action, audited inside), `TenantQueryService` (read models). |
| A5 | `UsageService` mixed quota enforcement with billing metering and raw JDBC. | `QuotaService` (cluster-wide enforcement), `UsageMeter` (batched metering), `UsageRepository` (SQL). |
| A6 | Untyped bus payloads and control messages (`"tenant.changed"` strings everywhere). | `ClusterBus` with `BusTopic`; `ControlMessage` with `ControlType`, tolerant of unknown types from newer nodes. |
| A7 | Webhooks: management, dispatch, caching and HMAC in one class on a 4-thread pool. | `WebhookAdminService`, `WebhookDispatcher` (virtual threads, shared `WebhookSignature`), `WebhookTargets`. |
| A8 | Nested repository interfaces required `considerNestedRepositories`. | All repositories are top-level. |

### Correctness

| # | Finding | Fix |
|---|---|---|
| C1 | Plan edits invalidated tenant snapshots on **one** node only. | `PlanChangedEvent` → after-commit `plans.changed` control message to every node. |
| C2 | Tenant and webhook invalidations were published before commit (other nodes could reload old rows). | Published after commit. |
| C3 | Tenant detail ran one plan query per feature (23+ queries) and list ran per-row plan lookups. | Loaded once per request; batch override-provider lookup; plan tenant counts in one `GROUP BY`. |
| C4 | Three different user-token lifetime rules (server API 1 min–30 d, tenant 5 min–30 d, impersonation hard-coded 8 h). | `UserTokenService`: one policy from `platform.security.*-user-token-ttl`. |
| C5 | Bootstrap owner created in `@PostConstruct` outside a transaction; two nodes starting together could both try. | `BootstrapOwner` `ApplicationRunner` in a transaction; the unique constraint decides the race. |
| C6 | "Messages today" on the overview summed up to 1,440 per-minute cache keys. | One daily counter maintained at flush time. |
| C7 | `IllegalArgumentException` anywhere became a 400 with an internal message. | Only domain errors map to 4xx; anything else is a logged 500. |
| C8 | Adapters leaked vendor exceptions (500s with driver messages) and used the default locale for case folding. | 503 `unavailable` with a neutral component name; `Locale.ROOT` everywhere. |
| C9 | Push-webhook URL checked against the SSRF policy only when the connector was created. | Re-checked before every send, like tenant webhooks. |
| C10 | Redis conformance tests fell back to `redis://localhost:6379` and run `SCRIPT FLUSH` — on a developer machine that is their own Redis. | Opt-in only via `REDIS_URL`. |

### Security

| # | Finding | Fix |
|---|---|---|
| S1 | The same digest of the master key signed media URLs, JWTs and encrypted secrets. | `KeyRing` purpose-separated derivation (existing data and URLs stay valid). |
| S2 | Push webhook signed only the body (replayable). | `sha256=HMAC(secret, timestamp + "." + body)` like tenant webhooks — **receivers must update**. |
| S3 | Audit entries written outside the action's transaction (failed actions could be audited, or audits lost). | Recorded inside the service transaction. |

### Console (frontend)

Reconnect could hang on a token failure; message ids were looked up globally; error toasts, abort
handling, memoisation, thread memory, accessibility (`ul > li > button`, `inert` drawer, live
region), media URL allow-listing (`safeMediaUrl`) and lint strictness were all addressed — see
[frontend/README.md](../frontend/README.md). 79 Vitest tests now cover the reducer, the chat engine,
realtime backoff and URL safety.

### Build and delivery

- CI (GitHub Actions): backend `mvn verify` with PostgreSQL, MySQL and Redis service containers;
  console lint, type-check, tests and build; container image build.
- Unit/conformance tests run in `mvn test` (Surefire); end-to-end `*IT` suites in `mvn verify`
  (Failsafe).
- Maven Enforcer pins Java ≥ 21 and Maven ≥ 3.9; `mvnw` is executable; build info gives the
  version shown in the console.
- Dockerfile builds with the wrapper, uses Spring Boot layered extraction and Node 22 LTS.

## Deferred, with reasons

| Item | Why not now | Suggested approach |
|---|---|---|
| **Data-plane schema versioning** (D5) | Stores create their schema on connect. Changing it safely needs a migration story per store type (SQL, Mongo, Cassandra, Firestore). | A `schema_version` record per store and ordered, idempotent migrations run by the connector on connect. |
| **SQL adapter on `JdbcTemplate`** (D6) | The adapter is correct and conformance-tested on three databases; a rewrite is churn without a functional gain today. | Port statement by statement behind the existing conformance suite. |
| **Maven multi-module split** | Package boundaries are now clean (no cycles between `auth`, `audit`, `tenant`, `chat`). Splitting modules is mechanical once needed. | `platform-core`, `connectors-*`, `platform-app`, so deployments can drop unused vendor SDKs. |
| **`ddl-auto: validate`** | JSON columns are `CLOB` (H2), `TEXT` (PostgreSQL) and `LONGTEXT` (MySQL); Hibernate's validator has no mapping that passes on all three. The live-database integration runs exercise every mapping instead. | Normalise JSON columns in a changeset (e.g. native `json`/`jsonb`), then enable validation. |
| **Persistent webhook retries** | Retries are scheduled in memory; a node restart drops pending ones. | An outbox table polled by the dispatcher. |
| **Compound inbox cursor** | Conversations sharing the boundary millisecond can be skipped by `before=`. | `(activityAt, id)` cursor. |
| **vitest 4** | `npm audit` lists three dev-only advisories in the test runner, fixed only in vitest 4, whose peer-dependency resolution crashes npm 10.8/10.9. Nothing ships in the bundle. | Upgrade once npm resolves it. |
