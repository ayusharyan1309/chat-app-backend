# Contributing

How the code is organised, the conventions it follows, and how to extend it. The design itself is
in [ARCHITECTURE.md](ARCHITECTURE.md); the reasoning behind the current structure is in
[docs/ENGINEERING_REVIEW.md](docs/ENGINEERING_REVIEW.md).

## Build and test

```bash
./mvnw test                      # unit + connector conformance (seconds to a minute)
./mvnw verify                    # + end-to-end *IT suites over HTTP and STOMP
cd frontend && npm ci && npm run check && npm run build
```

Requirements: JDK 21 (the enforcer rejects older), Node 22 LTS or 20.19+. Use the Maven wrapper.

Suites that need a live backend are **opt-in** through environment variables (see the table in
[README.md](README.md#testing)). Only ever point them at throwaway instances: they create and drop
data, and the Redis suites flush the server's Lua script cache.

## Where code goes

| You are adding… | Put it in | Notes |
|---|---|---|
| A REST endpoint | `chat.api` (clients, server API) or `admin.*` (operators) | Controllers only map requests to a service call. Request/response types are records; never return `Map` or JPA entities. |
| A messaging rule or use case | `chat.application` | One public method per use case; resolve the tenant with `TenantScopes.of(slug)` first. |
| Something the domain needs from outside | a port interface next to its user (`chat.port`, `auth.ActiveUserCheck`) | Implement it in an adapter; never import STOMP, Redis, Kafka or a vendor SDK into application code. |
| A new infrastructure provider | `store`/`cache`/`bus`/`blob`/`push` sub-package | See [Adding a connector](#adding-a-connector). |
| A tunable (TTL, pool size, interval, limit) | `core.config.PlatformProperties` + `application.yml` | Never a literal in code. Document the default in `application.yml`. |
| A cache or cluster key | `cache.CacheKeys` | So the namespace stays visible in one place. |
| A bus topic | `core.cluster.BusTopic` | Publish and subscribe through `ClusterBus` (typed JSON). |
| A cluster-wide invalidation | `chat.event.ControlType` + a handler in `ControlChannel` | Publish **after commit**. |
| An audited operator action | the application service performing it | `AuditService.record(AuditAction.X, …)` inside the same transaction. Add the action to `AuditAction`. |

## Conventions

- **Errors.** Throw `DomainException` with an `ErrorCode` (use the static factories:
  `badRequest`, `notFound`, `forbidden`, `conflict`, `featureDisabled`, `quotaExceeded`, …).
  Adapters wrap backend failures in `InfrastructureUnavailableException` (503) with a
  vendor-neutral component name. Do not throw `IllegalArgumentException` from request paths —
  anything that is not a `DomainException` becomes a logged 500.
- **Not found vs forbidden.** Resources a caller must not know about (conversations they are not
  in) are 404, never 403.
- **Validation.** Domain rules live in the service (`InputValidator` for messaging), with limits
  from `FieldLimits`, so REST, STOMP and the server API behave identically.
- **Transactions.** Application services own them. Side effects that other nodes observe
  (control messages, cache invalidation, connector swaps) run after commit: use
  `@TransactionalEventListener` or `AfterCommit.run(...)`.
- **Tenancy.** The tenant always comes from the credential. Every data-plane call passes the tenant
  slug; every store query filters by it.
- **Secrets.** Encrypt with `SecretCipher`; derive new keys with `KeyRing` (add a `Purpose`, never
  reuse one); sign with `HmacSigner`; compare signatures with `HmacSigner.matches`.
- **Outbound URLs** supplied by tenants go through `OutboundUrlPolicy.check` when saved **and**
  when called.
- **Text.** `toLowerCase(Locale.ROOT)` / `toUpperCase(Locale.ROOT)`; never the default locale.
- **Style.** Constructor injection (`@RequiredArgsConstructor` or an explicit constructor), final
  fields, no field injection, no `ObjectProvider` to dodge a cycle — a cycle means a missing port.
  Imports explicit (no wildcards), no inline fully-qualified names, Javadoc on every public type
  that is not self-explanatory. Comments explain *why*, not *what*.

## Adding a connector

1. Implement the SPI (`MessageStore`, `CacheStore`, `EventBus`, `BlobStore` or `PushSender`) in a
   new package, e.g. `store/dynamo`.
2. Add a `@Component` implementing `ConnectorFactory<T>`. Build its `ConnectorDescriptor` once
   (`static final`): id, display name, compatible products, `Maturity`, and the config fields
   (`ConfigField` — mark secrets `.secret()`, give defaults). The console renders the form from it.
3. Translate backend failures into `InfrastructureUnavailableException` and implement `health()`
   cheaply; `close()` must release every resource.
4. Subclass the conformance suite (`MessageStoreConformanceTest`, `CacheStoreConformanceTest`,
   `EventBusConformanceTest`), gate it on an environment variable, and make it pass.
5. Mark it `GA` only once the suite runs against the real client library in CI (a live server or
   a protocol-level fake); otherwise `BETA`.

## Adding a messaging feature

1. Add the feature to `tenant.Feature` (as `PLANNED` until it works, then `AVAILABLE`).
2. Gate the use case with `scope.tenant().require(Feature.X)`.
3. Emit realtime events through `ConversationNotifier` / `RealtimePublisher` and, if integrators
   need it, a `DomainEvent` (add the type to `DomainEvent.WEBHOOK_TYPES`).
4. Cover the rule in `MessagingServicesTest` and the wire behaviour in `PlatformIT`.
5. Document it in [docs/API.md](docs/API.md).

## Commits and pull requests

- One logical change per commit, message in the imperative ("Split webhook dispatch from admin").
- CI must be green: backend `verify` with PostgreSQL, MySQL and Redis; console lint, type-check,
  tests and build; container image build.
- Update the docs in the same change when behaviour, configuration or the API changes.
