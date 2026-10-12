# ADR-028 — React Frontend Foundation & Authentication Shell

Status: Accepted (Phase 3N, 2026-10-07).

## Context

The backend is complete through Phase 3M (56 REST endpoints, JWT login,
WebSocket snapshots, full test suite green). No frontend exists
(`frontend/` is empty). The first UI phase must establish an architecture
that later customer/staff/manager/admin workflows can build on — without
building every screen, and without weakening the backend.

Four questions are genuinely new: what the browser stack is, where the
bearer token lives, how the browser reaches the API during development
(CORS was explicitly deferred), and what "authorization" means in the
frontend.

## Decision

1. **React 18 + TypeScript (strict) + Vite + React Router 6, no store
   library.** Local React state plus one auth context is sufficient for a
   login page, a shell, and placeholder routes. No Redux/Zustand, no
   component framework, no Axios — `fetch` behind one small client.
   Dependencies stay minimal and interview-defensible.
2. **Package-by-feature, mirroring the backend:** `auth/` (api, context,
   hooks, types, utils), `app/` (routes, providers), `layout/`
   (shell + role-aware navigation), `pages/` (login, dashboard,
   unauthorized, not-found), `shared/` (api client, primitives, types).
   API calls never live in UI components; JWT parsing never leaves
   `auth/utils/token.ts`; role checks never scatter — they read the
   single `AuthProvider`.
3. **Session in `localStorage`, stated honestly.** The backend issues
   short-lived bearer JWTs with no refresh-token or HttpOnly-cookie
   infrastructure, and Phase 3N does not invent any. To survive a refresh
   the session (`accessToken` + derived identity) persists under one
   namespaced key (`smartqueue.auth.session`); passwords are never stored,
   tokens never enter URLs, tokens are never logged. This is NOT
   enterprise-grade session security — it is the correct client for the
   bearer-token backend that exists today. Expiry is checked client-side
   from the `exp` claim so dead tokens are dropped before use; every real
   decision is still re-enforced by the backend per request.
4. **No `/me` endpoint.** Identity comes from the login response plus the
   token's documented claims (`sub`, `org`, `email`, `roles`, `exp`).
   Client-side JWT parsing verifies nothing (the browser holds no secret)
   — it only restores identity and expiry. A forged token yields 401s.
5. **Minimal CORS bridge, backend posture unchanged.** `infrastructure/cors`
   allow-lists exact origins on `/api/**` only (local default is the Vite
   dev server `http://localhost:5173`; production sets
   `app.cors.allowed-origins` explicitly, never `*`). No credentials mode
   (bearer headers, not cookies), `Location` exposed for `201` flows. The
   security filter chain, JWT validation, and authorization policy are
   untouched.
6. **Frontend authorization is UX only.** Navigation and `RequireRoles`
   route gates hide or redirect; the backend remains the security
   boundary. The dashboard shows no metrics — nothing is fetched yet, and
   fake KPIs are forbidden. Placeholder routes render honest empty states.

## Alternatives considered

- **HttpOnly-cookie + refresh rotation:** the secure option, but it
  requires backend session infrastructure that does not exist; inventing
  it here would redesign authentication to suit the frontend (forbidden
  by the phase scope).
- **`sessionStorage` or memory-only tokens:** memory-only breaks refresh
  restoration (an explicit phase requirement); `sessionStorage` survives
  refresh per-tab but complicates multi-tab use for zero security gain
  over `localStorage` against XSS — same origin readability either way.
- **Next.js / component framework / state library:** unjustified weight
  for a login page plus a shell; deferred to a phase with real data
  screens if ever needed.

## Consequences

- `frontend/` builds, tests (33 Vitest cases), and serves against the
  existing backend with one documented env var (`VITE_API_BASE_URL`).
- Backend diff is three files (CORS config + properties + one property in
  `application.yml`, plus a stale comment corrected): 452 backend tests
  stay green.
- Known limitation: token theft via XSS is mitigated only by short expiry
  and hygiene, not by architecture — recorded here and in the development
  guide rather than oversold.
