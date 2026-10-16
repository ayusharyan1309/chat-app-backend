# Chat Platform Console

Operator console for the multi-tenant chat platform: infrastructure connectors, tenants, plans,
audit, admins and a live chat playground. It is built against the contract in
[`../docs/API.md`](../docs/API.md) (admin API, client API and the STOMP realtime protocol).

Stack: React 19, TypeScript 6, Vite 8, react-router 7, axios, @stomp/stompjs, recharts and
lucide-react. There is no component library or CSS framework: the console has its own small
component set and design tokens, with light and dark themes.

## Run it

Requirements: Node 20.19+ (or 22.12+) and the backend running on `http://localhost:8080`.

```bash
npm install
npm run dev          # http://localhost:5173
```

In development the Vite server proxies `/api`, `/ws` and `/ws-sockjs` to the backend
(WebSocket upgrades included), so the browser stays same-origin and no CORS setup is needed.
Sign in with the bootstrap admin configured on the backend.

| Script | What it does |
|---|---|
| `npm run dev` | Dev server with HMR and the backend proxy |
| `npm run build` | Type-check (`tsc -b`) and production build into `dist/` |
| `npm run preview` | Serve `dist/` locally (same proxy as dev) |
| `npm run lint` | oxlint |

## Configuration

Copy `.env.example` to `.env.local`. Every variable is optional.

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_BASE` | empty (same origin) | REST base URL used by the browser. Set it only when the console is served from a different origin than the backend; the backend must then allow that origin (CORS). |
| `VITE_WS_URL` | derived | STOMP endpoint (native WebSocket). Defaults to `ws(s)://<VITE_API_BASE host or page host>/ws`. |
| `DEV_PROXY_TARGET` | `http://localhost:8080` | Dev/preview server only: where `/api` and `/ws` are proxied. Not exposed to the browser. |

For a production deployment, serve `dist/` behind the same reverse proxy as the backend
(recommended, no variables needed) or build with `VITE_API_BASE` pointing at the backend.

## What is in it

| Area | Highlights |
|---|---|
| Sign in | Admin JWT in `localStorage`, attached as `Bearer`; any 401 ends the session and returns to the sign-in page with a notice. Viewer role is read-only throughout. |
| Overview | One row per infrastructure slot (provider, source, health, latency, last error), platform totals, and messages/deliveries per minute for the last hour. Refreshes every 10 s. |
| Infrastructure | One panel per connector slot. **Change provider** opens the editor: provider cards (name, category, maturity, compatible products, description) and a form rendered from the provider's `fields` schema. Test connection, apply (backend error shown inline), reset to bootstrap, and a confirmation before switching the message store because data is not migrated. |
| Tenants | Search and filters, new-tenant dialog with one-time API key reveal, and a detail page with tabs: overview and usage, plan and limit overrides, features, data and connector overrides, authentication, API keys, webhooks with deliveries, users, branding and settings. |
| Plans | Plan cards with limits and features, create/edit dialog, delete with the backend's 409 reason shown in the dialog. |
| Audit log | Tenant filter, expandable details, "load older" paging (`before=<id>`). |
| Admins | List, add (role), remove. |
| Chat playground | Pick a tenant and sign in as two to four end users side by side (existing users or created on the spot). Each pane mints an impersonation token and uses the client API plus STOMP like a real app. |

### Dynamic connector form

`src/components/connectors/ConnectorForm.tsx` renders any provider from its schema:
`STRING`/`URL` (text), `SECRET` (password with show/hide; `********` means "keep the stored value"),
`INTEGER` (number), `BOOLEAN` (switch), `SELECT` (options) and `TEXTAREA`. Required markers,
descriptions, placeholders and defaults come from the schema. `ConnectorEditor` adds the provider
picker, validation, test and apply; it is reused for platform slots, tenant overrides and the
tenant's external-auth settings.

### Playground behaviour

- Sends over STOMP (`/app/messages.send`) with a client-generated `clientMsgId`, shows the bubble
  immediately, and reconciles it on `message.ack` or on the sender's own `message.new`, de-duplicating
  by message `id`. While the socket is down, sends fall back to REST with the same `clientMsgId`.
  Unacknowledged sends are retried on reconnect and marked failed (with retry) after 15 s.
- Ticks: one grey tick once stored, two grey ticks when every other participant's `deliveredSeq`
  reaches the message, two blue ticks when every `readSeq` does (driven by `receipt` events and the
  participants' watermarks).
- Receipts: `delivered` when a message arrives or the inbox loads; `read` while the conversation is
  open and the browser tab is visible.
- History: newest page via `before = lastSeq + 1`, infinite scroll with `before = <first seq>`,
  scroll position kept while older messages load.
- Gap-free resync: after a reconnect every loaded conversation is pulled forward with
  `after = <last seq>`; a `message.new` that skips a seq also triggers a catch-up.
- Typing (`/app/typing`), presence (`/app/presence.watch`), reply quotes, reactions, edit and
  delete for everyone, media upload with preview, groups and direct chats. Actions follow the
  tenant's enabled features from `GET /api/v1/tenants/{slug}/config`.
- The URL keeps the tenant and the users (`/playground?tenant=acme&users=alice,bob`), so
  "Open in playground" links from a tenant's user list work.

## Structure

```
src/
  api/            Typed clients: http (axios instances, ApiError), admin, infra, plans, tenants,
                  client (end-user API), realtime (STOMP client)
  types/          Types mirroring docs/API.md
  state/          Providers: auth, theme, toast, confirm dialog, system info
  hooks/          useApi (data loading), useAction (mutations + toasts), small utilities
  components/
    ui/           Button, Input/Select/Textarea/Toggle/Checkbox/Field, Dialog, Tabs, DataTable,
                  Badge/Lamp/Callout/Skeleton/EmptyState, Card, Segmented, Avatar, Menu, Pagination,
                  SaveBar, CopyButton/SecretReveal
    layout/       App shell (sidebar, top bar), page header
    connectors/   Schema-driven form, provider picker, editor, health views, patch bay
    charts/       Throughput and usage charts (recharts)
    domain/       Tenant status and store badges
  pages/          Overview, Infrastructure, Tenants, Plans, Audit, Admins, Login
    tenant/       Tenant detail and its tabs
    playground/   Session engine (chatEngine), reducer (chatState), hook, panes and chat UI
  styles/         tokens.css (design tokens, both themes), base, components, layout, pages,
                  connectors, playground
```

Errors are normalised in `src/api/http.ts`: every failure becomes an `ApiError` whose `message` is
the backend's `message` field when present, or a plain explanation (for example when the backend
cannot be reached). Toasts and inline callouts always show that message.
