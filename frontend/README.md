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
| `npm run build` | Type-check (`tsc -b`) and production build into `dist/` (named vendor chunks: `react`, `charts`, `stomp`, `icons`, `vendor`) |
| `npm run preview` | Serve `dist/` locally (same proxy as dev) |
| `npm run typecheck` | `tsc -b --noEmit` over the app, the tests and the Vite/Vitest configs |
| `npm run lint` | oxlint with the `react`, `typescript`, `oxc` and `jsx-a11y` plugins; warnings fail the run, unused disable comments are reported |
| `npm test` | Unit tests once (`vitest run`); `npm run test:watch` keeps them running |
| `npm run check` | lint, typecheck and tests in one go (what CI should run, plus `build`) |

## Testing

Vitest (pinned, node environment, no DOM). Tests live next to the code as `*.test.ts`:

| File | Covers |
|---|---|
| `pages/playground/chatState.test.ts` | `mergeMessages` (append fast path, dedupe by id, out-of-order insert, in-place updates, same array when nothing changes), reducer (optimistic reconcile by `clientMsgId`, removal, failed opens, LRU thread cap, inbox reconcile, typing sweep, receipts), tick computation for direct and group chats |
| `pages/playground/chatEngine.test.ts` | The session engine with a fake realtime transport and fake client API (`EngineDeps`): reconnect resync (only loaded threads, inbox reconcile), failed/403/404 opens, deduplicated conversation fetches, receipts recorded only after success, ack timeout vs. REST fallback, typing gating and `typing:false` on stop, upload progress throttling |
| `api/realtime.test.ts` | The STOMP client keeps retrying (with backoff) when the token cannot be fetched, instead of going silently dead |
| `lib/mediaUrl.test.ts` | The URL allowlist (see below) |
| `state/toastApi.test.ts`, `api/http.test.ts`, `pages/playground/slots.test.ts` | Toast API, error-code mapping (exhaustive `switch` over `ApiErrorCode`), playground pane slots |

The backend is not needed to run them.

## Configuration

Copy `.env.example` to `.env.local`. Every variable is optional.

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_BASE` | empty (same origin) | REST base URL used by the browser. Set it only when the console is served from a different origin than the backend; the backend must then allow that origin (CORS). |
| `VITE_WS_URL` | derived | STOMP endpoint (native WebSocket). Defaults to `ws(s)://<VITE_API_BASE host or page host>/ws`. |
| `DEV_PROXY_TARGET` | `http://localhost:8080` | Dev/preview server only: where `/api` and `/ws` are proxied. Not exposed to the browser. |

For a production deployment, serve `dist/` behind the same reverse proxy as the backend
(recommended, no variables needed) or build with `VITE_API_BASE` pointing at the backend.

## Security notes

- **Admin token in `localStorage`.** The admin JWT is kept in `localStorage` so a session survives
  reloads and new tabs. The trade-off: any script running on the console's origin (an XSS bug or a
  compromised dependency) can read it. The console mitigates this by never rendering HTML from data
  (React escapes all text), validating every URL that comes from tenant or user data (next point),
  and by the CSP below. Keep admin token lifetimes short on the backend and serve the console on its
  own origin. An HttpOnly cookie session would remove this exposure but needs backend support
  (cookie auth plus CSRF protection).
- **URL allowlist** (`src/lib/mediaUrl.ts`, `safeMediaUrl`). URLs from tenant or end-user data are
  never put into `src`/`href` unchecked:
  - Avatars and logos: only `http(s)` or relative URLs (relative ones resolve against the backend).
    Anything else (`javascript:`, `data:`, URLs with credentials, unparsable values) falls back to
    initials; the tenant settings form rejects such a logo URL.
  - Message attachments: only the backend's own media endpoint, i.e. a path under `/api/v1/media/`
    on the console's origin or on the `VITE_API_BASE` origin (under its path prefix). The URL is
    parsed first, so `..` and `%2e%2e` segments cannot escape the prefix. Any other attachment is
    shown as plain text (file name and size) without a link or preview. The backend is expected to
    enforce the same rule when messages are stored; this check is defence in depth for old data.
- **Impersonation tokens.** Each playground pane mints an audited, short-lived user token. Panes are
  keyed by tenant and user, so closing one pane never remounts (and re-authenticates) the others,
  and a user can be open in only one pane at a time.
- **Recommended Content Security Policy** for the deployed console (same-origin backend):

  ```
  default-src 'self';
  script-src 'self' 'sha256-2sla1Rbng8autPQPN0h6xlKaxnE55zt05wnuwH/AewA=';
  style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;
  font-src 'self' https://fonts.gstatic.com;
  img-src 'self' https: blob:;
  media-src 'self' blob:;
  connect-src 'self' wss://console.example.com;
  object-src 'none';
  base-uri 'none';
  form-action 'self';
  frame-ancestors 'none'
  ```

  The `sha256-…` hash allows the small inline script in `index.html` that applies the theme before
  first paint; recompute it if that script changes (or move it to a file and drop the hash).
  `'unsafe-inline'` for styles covers React `style` props and the Google Fonts stylesheet; remove it
  together with the fonts link if you self-host the fonts. `img-src https:` is needed because
  tenant logos and user avatars may live on any HTTPS host. Replace `console.example.com` with the
  console's host (older browsers do not let `'self'` match `wss://`); add the API and WebSocket
  origins to `connect-src` when `VITE_API_BASE` or `VITE_WS_URL` point elsewhere, and the API origin
  to `media-src` for attachments. Also send `X-Content-Type-Options: nosniff` and
  `Referrer-Policy: same-origin`.

## What is in it

| Area | Highlights |
|---|---|
| Sign in | Admin JWT in `localStorage` (see security notes), attached as `Bearer`; any 401 ends the session and returns to the sign-in page with a notice. Viewer role is read-only throughout. Until the admin profile has loaded, the role is unknown and the console stays read-only; a failed profile load (other than 401) is retried with back-off. |
| Overview | One row per infrastructure slot (provider, source, health, latency, last error), platform totals, and messages/deliveries per minute for the last hour. The system provider is the only poller of the overview: every 60 s for the top bar, every 10 s while this page is open. |
| Infrastructure | One panel per connector slot. **Change provider** opens the editor: provider cards (name, category, maturity, compatible products, description) and a form rendered from the provider's `fields` schema. Test connection, apply (backend error shown inline), reset to bootstrap, and a confirmation before switching the message store because data is not migrated. |
| Tenants | Search and filters, new-tenant dialog with one-time API key reveal, and a detail page with tabs: overview and usage, plan and limit overrides, features, data and connector overrides, authentication, API keys, webhooks with deliveries, users, branding and settings. |
| Plans | Plan cards with limits and features, create/edit dialog, delete with the backend's 409 reason shown in the dialog. |
| Audit log | Tenant filter, expandable details, "load older" paging (`before=<id>`). Changing the filter aborts in-flight requests, so an older response can never replace or extend the newer list. |
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
  Unacknowledged socket sends are retried on reconnect and marked failed (with retry) after 15 s; a
  REST send is never failed by that timer while its request is still in flight.
- Ticks: one grey tick once stored, two grey ticks when every other participant's `deliveredSeq`
  reaches the message, two blue ticks when every `readSeq` does (driven by `receipt` events and the
  participants' watermarks). Only with the `read-receipts` feature; without it own messages show a
  single tick.
- Receipts: `delivered` when a message arrives or the inbox loads; `read` while the conversation is
  open and the browser tab is visible. A receipt counts as sent only after it was published on the
  socket or the REST call succeeded, so failures are retried by the next trigger.
- History: newest page via `before = lastSeq + 1`, infinite scroll with `before = <first seq>`,
  scroll position kept while older messages load. A failed open shows an error with **Retry**.
- Memory: full message state is kept for the six most recently opened conversations; older ones
  drop back to "not loaded" and reload when opened again.
- Resync after a reconnect: the inbox is refreshed and reconciled (a conversation missing from the
  newest page although it is newer than the page's oldest entry was removed while offline and is
  dropped), and every thread still in memory is pulled forward with `after = <last seq>`; a
  `message.new` that skips a seq also triggers a catch-up. A 404 while opening or syncing (or a 403
  when the conversation itself is gone) removes the conversation locally.
- Typing (`/app/typing`, only with `typing-indicators`; `typing:false` is sent when a pane closes),
  presence (`/app/presence.watch`), reply quotes, reactions, edit and delete for everyone, media
  upload with preview, groups and direct chats. Actions follow the tenant's enabled features from
  `GET /api/v1/tenants/{slug}/config` (typed as `FeatureId`).
- The URL keeps the tenant and the users (`/playground?tenant=acme&users=alice,bob`; empty entries
  are user pickers, duplicates are dropped), so "Open in playground" links from a tenant's user
  list work.

## Structure

```
src/
  api/            Typed clients: http (axios instances, ApiError), admin, infra, plans, tenants,
                  client (end-user API), realtime (STOMP client)
  types/          Types mirroring docs/API.md (incl. FeatureId and ApiErrorCode unions)
  state/          Providers: auth, theme, toast (+ toastApi), confirm dialog, system overview poller
  hooks/          useApi (data loading), usePagedApi (cursor paging), useAction (mutations + toasts),
                  useDebounced, useClipboard, useNow, useDocumentTitle, useMediaQuery
  lib/            config, format, util, features, validation, mediaUrl (URL allowlist)
  components/
    ui/           Button, Input/Select/Textarea/Toggle/Checkbox/Field, Dialog, Tabs, DataTable,
                  Badge/Lamp/Callout/Skeleton/EmptyState, Card, Segmented, Avatar, Menu, Pagination,
                  SaveBar, CopyButton/SecretReveal
    layout/       App shell (sidebar/drawer, top bar), page header
    connectors/   Schema-driven form, provider picker, editor, health views, patch bay
    charts/       Throughput and usage charts (recharts)
    domain/       Tenant status and store badges
  pages/          Overview, Infrastructure, Tenants, Plans, Audit, Admins, Login
    tenant/       Tenant detail, its tabs and tenantTab.ts (shared tab props and links)
    playground/   Session engine (chatEngine), reducer (chatState), hook, panes, slots and chat UI
  styles/         tokens.css (design tokens, both themes), base, components, layout, pages,
                  connectors, playground
```

Errors are normalised in `src/api/http.ts`: every failure becomes an `ApiError` whose `message` is
the backend's `message` field when present, or a plain explanation (for example when the backend
cannot be reached), and whose `code` is a closed `ApiErrorCode` union (the raw wire value stays in
`rawCode`). Toasts use `toast.error(title, description?)` for messages you write and
`toast.fromError(err, title?)` for thrown values; inline callouts show the same message.
