# API Reference (v2)

Base URL: `http://localhost:8080`. JSON everywhere. Timestamps are ISO-8601 UTC strings.
Interactive docs: `/swagger-ui.html` (OpenAPI at `/v3/api-docs`).

**Errors** always look like:
```json
{ "status": 400, "error": "bad_request", "message": "Human readable", "path": "/api/...", "timestamp": "..." }
```
Tenant-supplied callback URLs (webhooks, push webhook, JWKS) must resolve to public addresses unless the
operator sets `PLATFORM_SECURITY_ALLOW_PRIVATE_NETWORK_CALLBACKS=true`.

Error codes: `bad_request`, `validation_failed`, `unauthorized`, `forbidden`, `not_found`, `conflict`,
`feature_disabled`, `quota_exceeded`, `rate_limited`, `unavailable`, `internal_error`.

## Credentials

| Who | Header | Obtained from |
|---|---|---|
| Platform admin (dashboard) | `Authorization: Bearer <admin JWT>` | `POST /api/admin/auth/login` |
| Tenant backend (server-to-server) | `X-API-Key: ck_...` | created per tenant in the dashboard |
| End user (client app) | `Authorization: Bearer <user JWT>` | `POST /api/v1/server/tokens` (API key), `/api/v1/auth/exchange` (Firebase / own JWT), `/api/v1/auth/dev-login` (when enabled) |

The tenant is always derived from the credential (API key or JWT claim), never from a bare header.

---------------------------------------------------------------------------------------------------

## 1. Admin API (`/api/admin/**`, admin JWT)

Roles: `OWNER`, `ADMIN` (full write), `VIEWER` (read-only; writes return 403).

### Auth
| Method | Path | Body → Response |
|---|---|---|
| POST | `/api/admin/auth/login` | `{email, password}` → `{token, expiresAt, admin: Admin}` |
| GET | `/api/admin/auth/me` | → `Admin` |
| POST | `/api/admin/auth/change-password` | `{currentPassword, newPassword}` → 204 |

`Admin = {id, email, name, role, lastLoginAt, createdAt}`

### Admin users
| GET | `/api/admin/admins` | → `Admin[]` |
|---|---|---|
| POST | `/api/admin/admins` | `{email, name, password, role}` → `Admin` |
| DELETE | `/api/admin/admins/{id}` | → 204 (cannot delete yourself / last OWNER) |

### System
`GET /api/admin/system/overview` →
```json
{
  "version": "2.0.0", "nodeId": "node-7f3a", "startedAt": "...", "uptimeSeconds": 3600,
  "components": [ ComponentStatus ],
  "totals": { "tenants": 3, "activeTenants": 2, "users": 120, "conversations": 40, "messages": 1234,
              "connections": 5, "messagesToday": 87 },
  "throughput": [ { "minute": "2026-10-16T17:05:00Z", "messages": 12, "deliveries": 20 } ]   // last 60 minutes, oldest first
}
```
`ComponentStatus = {kind, displayName, provider, providerName, source: "database"|"bootstrap", healthy, message, latencyMs, details, lastError}`
(`kind` is a slug: `message-store`, `cache`, `event-bus`, `blob-store`, `push`.)

`GET /api/admin/system/metrics?minutes=60` → same `throughput` array for the requested window (max 1440).

### Connectors & infrastructure
`GET /api/admin/connectors` → catalogue of every pluggable slot and its providers:
```json
[ { "kind": "message-store", "displayName": "Message store", "description": "...", "tenantOverridable": true,
    "providers": [ { "id": "postgresql", "displayName": "PostgreSQL", "description": "...", "category": "SQL",
                     "maturity": "GA", "compatibleWith": ["Neon", "Supabase"],
                     "fields": [ { "key": "url", "label": "Connection URL", "type": "STRING|SECRET|INTEGER|BOOLEAN|SELECT|TEXTAREA|URL",
                                   "required": true, "defaultValue": null, "description": "...",
                                   "placeholder": "...", "options": ["..."] } ] } ] } ]
```

`GET /api/admin/infrastructure` → `ConnectorState[]` (one per kind)
`GET /api/admin/infrastructure/{kind}` → `ConnectorState`
```json
{ "kind": "cache", "provider": "redis", "settings": { "uri": "********", "keyPrefix": "chat:" },
  "source": "database", "version": 3, "updatedAt": "...", "updatedBy": "admin@local",
  "health": { "healthy": true, "message": "PONG", "latencyMs": 2, "details": {} }, "lastError": null }
```
Secret fields are always returned as `"********"`. When saving, sending `"********"` back keeps the stored value.

| Method | Path | Body → Response |
|---|---|---|
| PUT | `/api/admin/infrastructure/{kind}` | `{provider, settings}` → `ConnectorState`. Connects first; on failure returns 400 and nothing changes. Hot-swapped on every node. |
| POST | `/api/admin/infrastructure/{kind}/test` | `{provider, settings}` → `{healthy, message, latencyMs, details}` (nothing saved) |
| DELETE | `/api/admin/infrastructure/{kind}` | → `ConnectorState` (reverts to bootstrap config from application.yml/env) |

Switching the platform message store does **not** migrate data; existing tenants on the shared store will
see the new (empty) store. The UI must warn about this.

### Features & plans
`GET /api/admin/features` → `[{id, name, description, category, status: "CORE"|"AVAILABLE"|"PLANNED"}]`

`GET /api/admin/plans` → `Plan[]`
```json
{ "code": "pro", "name": "Pro", "description": "...", "priceMonthly": 299, "priceYearly": 2990, "currency": "USD",
  "limits": { "maxUsers": 250000, "maxMessagesPerDay": 5000000, "maxConnections": 50000, "maxStorageMb": 512000,
              "maxGroupSize": 1024, "messagesPerSecond": 20 },          // null = unlimited
  "features": ["group-chat", "webhooks"], "isDefault": false, "active": true, "sortOrder": 2, "tenantCount": 4 }
```
| POST | `/api/admin/plans` | `Plan` (without tenantCount) → `Plan` |
|---|---|---|
| PUT | `/api/admin/plans/{code}` | `Plan` → `Plan` |
| DELETE | `/api/admin/plans/{code}` | → 204 (409 if tenants use it or it is the default) |

### Tenants
`GET /api/admin/tenants?q=&status=` → `TenantSummary[]`
```json
{ "slug": "acme", "name": "Acme Inc", "contactEmail": "ops@acme.io", "domain": "acme.io",
  "status": "ACTIVE|TRIAL|SUSPENDED|DELETED", "planCode": "pro", "planName": "Pro",
  "storeProvider": null,            // null = shared platform store, otherwise dedicated provider id
  "messagesToday": 120, "connections": 3, "createdAt": "...", "trialEndsAt": null }
```
`POST /api/admin/tenants` `{name, slug?, contactEmail, domain?, planCode?, trialDays?}` →
`{ tenant: TenantDetail, apiKey: ApiKeyCreated }` (an initial server API key is generated; its secret is shown only here).

`GET /api/admin/tenants/{slug}` → `TenantDetail`
```json
{ ...TenantSummary,
  "branding": { "displayName": "Acme Chat", "logoUrl": null, "primaryColor": "#0B6E4F", "accentColor": "#F2A541" },
  "settings": { "maxMessageLength": 4096, "editWindowMinutes": 15, "maxFileSizeMb": 25, "allowUserSearch": true },
  "limits":   { "maxUsers": 250000, "maxMessagesPerDay": 5000000, "maxConnections": 50000, "maxStorageMb": 512000, "maxGroupSize": 1024, "messagesPerSecond": 20 },
  "limitOverrides": { "maxUsers": null, ... },          // null = inherit plan
  "features": [ { "id": "group-chat", "name": "Group chat", "category": "Messaging", "status": "AVAILABLE",
                  "enabled": true, "source": "core|plan|override|planned" } ],
  "stats": { "users": 120, "conversations": 40, "messages": 1234, "messagesToday": 87, "connections": 3, "storageBytes": 1048576 },
  "updatedAt": "..." }
```
| Method | Path | Body → Response |
|---|---|---|
| PUT | `/api/admin/tenants/{slug}` | `{name, contactEmail, domain, planCode, branding, settings, limitOverrides}` (all optional) → `TenantDetail` |
| POST | `/api/admin/tenants/{slug}/suspend` | → `TenantDetail` (suspended tenants get 403 on every API, sockets are closed) |
| POST | `/api/admin/tenants/{slug}/activate` | → `TenantDetail` |
| DELETE | `/api/admin/tenants/{slug}` | → 204 (soft delete) |
| PUT | `/api/admin/tenants/{slug}/features` | `{overrides: {"webhooks": true, "group-chat": false}}` (full replacement; omitted = inherit plan) → `TenantDetail.features` |
| GET | `/api/admin/tenants/{slug}/usage?days=30` | → `[{day: "2026-10-16", messages, mediaBytes, apiCalls}]` oldest first, zero-filled |

#### Tenant connector overrides (`kind` = `message-store` or `push`)
| GET | `/api/admin/tenants/{slug}/connectors/{kind}` | → `{inherited: true}` or `{inherited: false, provider, settings (masked), health}` |
|---|---|---|
| PUT | `/api/admin/tenants/{slug}/connectors/{kind}` | `{provider, settings}` → same (connects first; 400 on failure) |
| POST | `/api/admin/tenants/{slug}/connectors/{kind}/test` | `{provider, settings}` → health |
| DELETE | `/api/admin/tenants/{slug}/connectors/{kind}` | → `{inherited: true}` |

#### Tenant auth
`GET/PUT /api/admin/tenants/{slug}/auth`
```json
{ "devLoginEnabled": true,
  "tokenTtlMinutes": 1440,
  "external": { "type": "none|firebase|jwt",
                "firebaseProjectId": "...",
                "jwtIssuer": "...", "jwtAudience": "...", "jwksUrl": "...", "jwtSecret": "********",
                "userIdClaim": "sub", "nameClaim": "name", "emailClaim": "email" } }
```
`external.type != none` requires the `external-auth` feature.

#### API keys
| GET | `/api/admin/tenants/{slug}/api-keys` | → `[{id, name, prefix, createdAt, createdBy, lastUsedAt, revokedAt}]` |
|---|---|---|
| POST | `/api/admin/tenants/{slug}/api-keys` | `{name}` → `ApiKeyCreated = {...ApiKey, key: "ck_live_..."}` (full key shown once) |
| DELETE | `/api/admin/tenants/{slug}/api-keys/{id}` | → 204 (revoke) |

#### Webhooks (requires `webhooks` feature)
`Webhook = {id, url, events: ["message.created", ...], description, active, secret: "********", createdAt, updatedAt}`
Events: `message.created`, `message.updated`, `message.deleted`, `conversation.created`, `conversation.member_added`, `conversation.member_removed`.
| GET | `/api/admin/tenants/{slug}/webhooks` | → `Webhook[]` |
|---|---|---|
| POST | `/api/admin/tenants/{slug}/webhooks` | `{url, events, description, active}` → `Webhook` with the real `secret` (shown once) |
| PUT | `/api/admin/tenants/{slug}/webhooks/{id}` | same body → `Webhook` |
| DELETE | `/api/admin/tenants/{slug}/webhooks/{id}` | → 204 |
| POST | `/api/admin/tenants/{slug}/webhooks/{id}/test` | → `WebhookDelivery` (sends `webhook.ping`) |
| GET | `/api/admin/tenants/{slug}/webhooks/{id}/deliveries` | → `[{id, eventId, eventType, status: SUCCESS|FAILED|PENDING, attempts, responseCode, error, durationMs, createdAt}]` newest first (max 100) |

#### Tenant end users (data plane)
| GET | `/api/admin/tenants/{slug}/users?q=&offset=0&limit=50` | → `{items: User[], total}` |
|---|---|---|
| POST | `/api/admin/tenants/{slug}/users` | `{id, displayName, email?, phone?, avatarUrl?}` → `User` (upsert) |
| POST | `/api/admin/tenants/{slug}/users/{userId}/token` | → `{token, expiresAt, user}` — impersonation token for testing (audited) |

### Audit log
`GET /api/admin/audit?tenant=&limit=100&before=<id>` → `[{id, at, actor, actorType: ADMIN|API_KEY|SYSTEM, action, targetType, targetId, tenant, details, ip}]` newest first.

---------------------------------------------------------------------------------------------------

## 2. Client API (`/api/v1/**`)

### Public
`GET /api/v1/tenants/{slug}/config` → `{tenant, name, branding, features: [enabled ids], auth: {devLoginEnabled, externalType}, settings}`

### Auth
| POST | `/api/v1/auth/dev-login` | `{tenant, userId, displayName?}` → `{token, expiresAt, user}` (only if tenant `devLoginEnabled`) |
|---|---|---|
| POST | `/api/v1/auth/exchange` | `{tenant, token}` (Firebase ID token or tenant JWT) → `{token, expiresAt, user}`; user provisioned just-in-time |

### Server API (`X-API-Key`)
| POST | `/api/v1/server/users` | `{id, displayName, email?, phone?, avatarUrl?, about?, metadata?}` → `User` (upsert, counts toward `maxUsers`) |
|---|---|---|
| GET | `/api/v1/server/users?q=&offset=&limit=` | → `{items: User[], total}` |
| GET | `/api/v1/server/users/{id}` | → `User` |
| DELETE | `/api/v1/server/users/{id}` | → 204 (deactivate; tokens for the user stop working) |
| POST | `/api/v1/server/tokens` | `{userId, ttlMinutes?}` → `{token, expiresAt}` |
| POST | `/api/v1/server/conversations/{id}/messages` | `{senderId, type, content, clientMsgId?, metadata?}` → `Message` (bots / system notices) |
| GET | `/api/v1/server/stats` | → `{users, conversations, messages, messagesToday, connections, usage: [...last 30 days]}` (requires `analytics`) |

### User API (user JWT)
`User = {id, displayName, email, phone, avatarUrl, about, metadata, deactivated, createdAt, lastSeenAt, online}` (`online`/`lastSeenAt` only when `presence` is enabled).

| Method | Path | Body → Response |
|---|---|---|
| GET | `/api/v1/me` | → `User` |
| PATCH | `/api/v1/me` | `{displayName?, avatarUrl?, about?}` → `User` |
| POST | `/api/v1/me/devices` | `{token}` → 204 (push token) |
| DELETE | `/api/v1/me/devices/{token}` | → 204 |
| GET | `/api/v1/users?q=&limit=20` | → `User[]` |
| GET | `/api/v1/users/{id}` | → `User` |
| GET | `/api/v1/blocks` | → `string[]` |
| PUT / DELETE | `/api/v1/blocks/{userId}` | → 204 |
| GET | `/api/v1/conversations?limit=50&before=<ISO>` | → `Conversation[]` (inbox, newest activity first) |
| POST | `/api/v1/conversations/direct` | `{userId}` → `Conversation` (get or create) |
| POST | `/api/v1/conversations/group` | `{title, members: [userId], description?, avatarUrl?}` → `Conversation` (`group-chat`) |
| GET | `/api/v1/conversations/{id}` | → `Conversation` with `participants` |
| PATCH | `/api/v1/conversations/{id}` | `{title?, description?, avatarUrl?}` → `Conversation` (group admins) |
| PATCH | `/api/v1/conversations/{id}/settings` | `{muted?, archived?, pinned?}` → `Conversation` |
| POST | `/api/v1/conversations/{id}/members` | `{userIds}` → `Conversation` (admins) |
| PATCH | `/api/v1/conversations/{id}/members/{userId}` | `{role: ADMIN|MEMBER}` → `Conversation` (admins) |
| DELETE | `/api/v1/conversations/{id}/members/{userId}` | → 204 (admins remove; anyone may remove themself = leave) |
| GET | `/api/v1/conversations/{id}/messages?after=&before=&limit=50` | → `Message[]` ascending |
| POST | `/api/v1/conversations/{id}/messages` | `{clientMsgId, type?, content?, attachment?, replyToId?, forwarded?, metadata?}` → `Message` |
| PATCH | `/api/v1/conversations/{id}/messages/{messageId}` | `{content}` → `Message` (`message-editing`, own messages, edit window) |
| DELETE | `/api/v1/conversations/{id}/messages/{messageId}` | → `Message` tombstone (`message-deletion`, own messages or group admins) |
| PUT | `/api/v1/conversations/{id}/messages/{messageId}/reactions` | `{emoji}` → `Message` (`message-reactions`) |
| DELETE | `/api/v1/conversations/{id}/messages/{messageId}/reactions` | → `Message` |
| POST | `/api/v1/conversations/{id}/read` | `{seq}` → 204 |
| POST | `/api/v1/conversations/{id}/delivered` | `{seq}` → 204 |
| POST | `/api/v1/conversations/{id}/typing` | `{typing: true}` → 204 |
| POST | `/api/v1/media` | multipart `file` → `{mediaId, url, mimeType, size, fileName}` (`media-sharing` for image/video/audio, `file-sharing` otherwise) |
| GET | `/api/v1/media/{tenant}/{mediaId}?sig=` | → file bytes, or a 302 to a presigned S3 URL. Capability URL: unguessable id + HMAC signature, no auth header needed so it works in `<img src>` |

```json
Conversation = { "id", "type": "DIRECT|GROUP", "title", "description", "avatarUrl", "createdBy", "createdAt",
  "lastSeq", "lastMessage": { "id", "preview", "senderId", "at" } | null,
  "unreadCount", "muted", "archived", "pinned", "role": "OWNER|ADMIN|MEMBER",
  "peer": User | null,                       // DIRECT only: the other participant
  "participants": [ { "userId", "role", "displayName", "avatarUrl", "deliveredSeq", "readSeq", "joinedAt" } ] }  // detail view only

Message = { "id", "conversationId", "seq", "senderId", "clientMsgId", "type": "TEXT|IMAGE|VIDEO|AUDIO|FILE|LOCATION|CONTACT|STICKER|SYSTEM",
  "content", "attachment": { "mediaId", "url", "mimeType", "size", "fileName", "width", "height", "durationMs", "thumbnailUrl" } | null,
  "replyToId", "forwarded", "reactions": { "👍": ["alice"] }, "edited", "editedAt", "deleted", "createdAt", "metadata" }
```

---------------------------------------------------------------------------------------------------

## 3. Realtime (STOMP over WebSocket)

Endpoints: `ws://host/ws` (native WebSocket) and `http://host/ws-sockjs` (SockJS fallback).
CONNECT headers: `Authorization: Bearer <user JWT>`.

Client → server (`SEND`):
| Destination | Body |
|---|---|
| `/app/messages.send` | `{conversationId, clientMsgId, type?, content?, attachment?, replyToId?, forwarded?, metadata?}` |
| `/app/typing` | `{conversationId, typing}` |
| `/app/receipts` | `{conversationId, seq, kind: "delivered"|"read"}` |
| `/app/presence.watch` | `{userIds: [..]}` (replaces the watch list for this session; replies with a presence event per user) |

Server → client: subscribe to `/user/queue/events`. Every frame is an envelope:
```json
{ "type": "message.new|message.ack|message.updated|receipt|typing|presence|conversation.updated|conversation.removed|error",
  "conversationId": "...", "at": "...", "data": { ... } }
```
| type | data |
|---|---|
| `message.new` | `Message` — sent to every session of every participant, including the sender's (keeps other devices in sync; de-duplicate by `id`, reconcile optimistic bubbles by `clientMsgId`) |
| `message.ack` | `{clientMsgId, message: Message, duplicate}` (to the sender: the message is durably stored) |
| `message.updated` | `Message` (edit, delete, reaction) |
| `receipt` | `{userId, deliveredSeq, readSeq}` |
| `typing` | `{userId, typing}` |
| `presence` | `{userId, online, lastSeenAt}` |
| `conversation.updated` | `Conversation` without `participants` (created, renamed, membership or settings changed — from the recipient's point of view; sent to every participant including the actor) |
| `conversation.removed` | `{conversationId}` (you were removed / left) |
| `error` | `{code, message, clientMsgId?}` |

---------------------------------------------------------------------------------------------------

## 4. Webhooks (outbound)

`POST <your url>` with headers `X-Chat-Event`, `X-Chat-Delivery` (unique id), `X-Chat-Timestamp` (unix seconds) and
`X-Chat-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + body)>`. Body:
```json
{ "id": "evt_...", "type": "message.created", "tenant": "acme", "createdAt": "...", "data": { ... } }
```
Delivered at-least-once with up to 5 attempts (exponential backoff). Respond 2xx within 10 s.
