# Project Status — Single Source of Truth

> Last updated: 2026-10-09 (Phase 4U — Postman API Handover & Verification;
> documentation, API verification, and developer experience only — no product
> features, no UI changes, no backend business logic refactoring).
> This file must match reality. Verify against `pom.xml`, migrations, code,
> and test reports before editing. See AGENTS.md §18.

## Current Phase

**Phase 4U — Postman API Handover & Verification (complete, 2026-10-09; documentation, API verification, and developer experience only — no product features, no UI changes, no backend business logic refactoring).**

Prepares the existing SmartQueue backend for professional demonstration, testing, and handover by creating a complete, importable Postman collection, environment template, and comprehensive guide.

### Deliverables Created / Updated

- **Postman Collection (`backend/postman/SmartQueue.postman_collection.json`)**: Complete collection with 14 domain/workflow groups (Health & Infrastructure, Authentication & Account, Public Discovery, Organization & Branch Management, Services & Counters, Staff & Membership Management, Working Hours & Holidays, Appointment Availability & Booking, Queue Joining & Customer Status, Staff Queue & Counter Operations, Notifications, Analytics & Reporting, Platform Administration, Authorization & Tenant-Isolation Checks). Every request includes accurate methods, paths, headers, JSON payloads, descriptions, and automated test/token-capture scripts.
- **Environment Template (`backend/postman/SmartQueue.local.postman_environment.json`)**: Importable environment template containing non-secret placeholder variables (`baseUrl`, role tokens for customer, owner, staff, manager, platformAdmin, and entity IDs).
- **Postman Guide (`backend/docs/api/POSTMAN_GUIDE.md`)**: Comprehensive developer guide detailing prerequisites, infrastructure startup, import steps, environment variables reference, recommended execution workflows, email verification with Mailpit, platform-admin bootstrap, test scripts, and security/tenant-isolation checks.
- **Documentation Pointers**: Linked the Postman guide and collection from `backend/README.md` and `backend/docs/api/README.md`.

### Verification

- Validated JSON syntax and structure for `SmartQueue.postman_collection.json` and `SmartQueue.local.postman_environment.json`.
- Confirmed strict adherence to scope boundaries: zero product features added, zero UI changes introduced, zero backend business logic modified.

## Previous Phase

Transforms SmartQueue from a functional application into a distinctive,
polished, modern SaaS product with a cohesive design system inspired by
Linear's navigation discipline, Stripe's information hierarchy, and
hospitality products' approachable illustrations.

### Design System (Step A — Foundation)

- **Tokens**: Complete light/dark palette in CSS custom properties
  (`--sq-bg`, `--sq-surface`, `--sq-heading`, `--sq-brand`, `--sq-indigo`,
  `--sq-ok`, `--sq-warn`, `--sq-danger`, `--sq-muted`, `--sq-line`,
  `--sq-radius*`, `--sq-shadow*`, `--sq-font`, `--sq-space-*`,
  `--sq-focus-ring`). Dark mode redefines the same tokens under
  `[data-theme="dark"]` — components never hard-code colors.
- **Typography**: Inter (system fallback), tabular numerals on
  metrics/tables (`font-variant-numeric: tabular-nums`).
- **Shape & depth**: Consistent radii (10/14/20px), thin borders,
  three elevation shadows, 6-step spacing scale.
- **Buttons**: `sq-btn` with active press transform, focus-visible
  box-shadow ring (no outline), primary uses dark text in dark mode.
- **Inputs/Selects**: focus ring via `--sq-focus-ring`, dark backgrounds
  via `--sq-input-bg`.
- **Badges/Alerts/Chips**: all use CSS variables for light/dark contrast.
- **Dialogs/Drawer**: fade/pop/slide animations, escape handling via
  `useDismissOnEscape` (guards pending mutations), sticky drawer head.
- **Iconography**: Extended `NavIcon` set (30+ names: `building`,
  `search`, `x`, `refresh`, `chevron-*`, `plus`, `check`, `alert`,
  `info`, `clock`, `settings`, `logout`, `sun`, `moon`, `menu`,
  `file-text`, `trash`, `edit`, `eye`, `arrow-right`, `user`, `history`,
  `filter`), single stroke weight (1.8px), `currentColor`, `aria-hidden`.

### Application Shell (Step A)

- **Sidebar**: full-height (`sq-sidenav`), brand mark (`sq-brand-mark` +
  `sq-brand-word`), grouped nav with uppercase section labels, active
  route indicator bar, collapsible mobile drawer with scrim
  (`sq-nav-scrim`).
- **Topbar**: sticky blurred glass (`sq-topbar`), theme toggle
  (`moon`/`sun` icon buttons), notification bell, avatar identity
  (`sq-avatar`), icon-button logout.
- **Mobile**: hamburger toggle, proper drawer, no horizontal overflow,
  touch targets ≥44px, tables collapse to cards.

### Organization Management Console (Step C — Premium Pass)

- **Hero**: `sq-console-hero` with eyebrow, title, subtitle, refresh
  action, inline SVG art (CSS-only, no external images).
- **Stat cards**: `sq-stat-grid` + `sq-stat-card` — clickable filters
  (`aria-pressed`), colored icon badges (`sq-stat-icon--warn/ok/muted/danger`),
  live counts from loaded data.
- **Toolbar**: search wrapper with leading icon (`sq-search-wrapper`),
  status + verification + attention filters, Clear filters action.
- **Table**: `sq-org-row` with deterministic initials avatar
  (`sq-org-avatar`), `sq-row-selected` highlight, focus-visible ring,
  `sq-status-cell` groups badge + verification warning.
- **Cards fallback**: `sq-record-card` with avatar + ID row on mobile.
- **Detail drawer**: `sq-detail-drawer` (right-side panel, 28rem) replaces
  modal — sticky head with avatar/title/close, scrollable body, slide-in
  animation, stale-response guard, pending-mutation escape guard.
- **Actions**: Approve (disabled + explained while unverified), Reject
  (reason ≤500 chars), both with `ConfirmDialog` naming org + consequence.
- **Decided rows**: read-only with explicit unsupported-action notes
  (deactivation, reactivation, deletion have no platform endpoint).

### Dashboard Polish (Step B)

- **Customer dashboard** (`CustomerDashboard.tsx`): `sq-customer-hero`
  with time-of-day greeting, inline SVG art, icon-enhanced action buttons,
  same grid hero layout as console.
- **Other dashboards** (Staff, Manage, Platform Admin) inherit the new
  shell, tokens, and component styles automatically.

### Routes Updated

All existing 33 routes inherit the new shell, tokens, and component
styles. Navigation label "Business applications" → "Organizations"
(`layout/navigation.ts`). No routes added/removed.

### Tests & Gates (exact)

- `npm test` in `frontend/` → **320/320 green** (53 files; 309 baseline
  + 11 platform-console tests unchanged).
- `npx tsc --noEmit` clean.
- `npm run build` green (`dist/` 35 KB CSS / 402 KB JS gzipped).
- No backend suite re-run (no backend change, per §9 scoping).
- Browser verification not performed — no browser tooling in this
  environment (reported, not claimed).

### Documentation Updated

- `frontend/docs/design-system.md` — complete token reference,
  component inventory, rules, and console/dashboard specifics.
- `frontend/docs/routes.md` — updated `/platform/applications` description.

### Unsupported on Purpose (Documented, Not Faked)

- Organization deactivation/suspension, reactivation, permanent deletion
  — no platform endpoint exists; deactivation remains domain policy
  without a platform admin endpoint.
- No external image dependencies — avatars use deterministic initials,
  heroes use inline SVG art.

## Previous Phase

`/platform/applications` is now an Organization Management console instead
of a single-status card list with an inline detail panel: header
("Organization Management" + result count + Refresh), case-insensitive
search over name/owner email/organization ID/branch name, status filter
(All + PENDING_APPROVAL/ACTIVE/INACTIVE/REJECTED), independent owner
email-verification filter (All/Verified/Unverified), needs-attention
quick filter (pending OR unverified), Clear filters, compact responsive
table (card list on narrow screens), and a modal detail dialog (fresh
re-read per selection with loading/failure/retry, stale-response guard,
Escape, close button, scroll lock) with approve/reject for pending
applications only (approval disabled while the owner is unverified, with
the reason explained; rejection requires applicant-facing feedback
≤500 chars; both confirm with org name + consequence; 409s surface
honestly and preserve context). Decided rows are read-only with the
rejection reason/decision info where available.

Root cause of the reported ACTIVE-filter behavior (found by inspection
before any change): no code was mis-filtering. The backend
(`OrganizationReviewService.listApplications`) filters purely on
`Organization.status`, and the frontend passed `?status=` through
verbatim — both correct. The gap was presentational: legacy ACTIVE
organizations with unverified owners rendered with no distinct
verification signal and no verification filter, so the rows looked like
a filter bug. Fixed by separating the concepts (independent badge +
filter + `ACTIVE · Owner email unverified` warning) while leaving the
rows' status untouched — nothing was hidden, deactivated, or re-statused.

Unsupported on purpose (no endpoint exists; documented in
`frontend/docs/design-system.md`, not faked): organization
deactivation/suspension, reactivation, and permanent deletion. The
platform API supports approve/reject on PENDING_APPROVAL only; INACTIVE
is a legacy state with no transition endpoints, and deletion would break
appointment/queue/membership history and auditability — deactivation
remains the domain policy, and even that has no platform endpoint yet.

The list endpoint takes exactly one status per request and is
unpaginated, so the console loads all four statuses once and
searches/filters client-side ("Showing X of Y" = matching vs loaded;
correct for current onboarding-review volume). Navigation label is now
"Organizations" (route unchanged). No backend suite re-run (no backend
change, per §9 scoping); browser verification not performed — no browser
tooling in this environment (reported, not claimed).

Tests & gates (exact): `npm test` in `frontend/` → **320/320 green**
(53 files; 309 baseline + 11 new platform-console tests);
`npx tsc --noEmit` clean; `npm run build` green. Platform-console
coverage: status filter, verification filter independent of status,
combined search+filters, clear filters, empty results, modal (not
inline) open/close/stale-guard, unverified-approval block, per-status
permitted actions, both confirmations + exact request contracts,
success refresh, 409 preservation, list/detail loading + retry,
route-guard/nav matrix intact.

## Previous Phase

**Phase 4T — Repository Restructuring: Independent Backend and Frontend
(complete, 2026-10-09; organization only — no business logic, endpoint,
migration, or authorization change).**

`backend/` is now self-contained (`pom.xml`, `mvnw`, `src/`,
`compose.yaml`, `.env.example`, `docs/`, `scripts/`) and `frontend/`
likewise (`package.json`, lockfile, `src/`, Vite/TS configs,
`.env.example`, `docs/`). Former root `docs/` (api, architecture,
database, decisions ADR-001–ADR-041, development, this status file)
moved to `backend/docs/` with paths re-based; the ADR series stays
intact in one place by decision. Former root `compose.yaml`
(backend infra only: PostgreSQL + Redis + Mailpit) moved to
`backend/compose.yaml`; former root `.env` placeholders split into
`backend/.env.example` (+ preserved local `backend/.env`, git-ignored)
and `frontend/.env.example`. Backend `demo-seed.sql`/`probe.sql` moved
to `backend/scripts/`; Spring boilerplate `backend/HELP.md` removed.
New `backend/README.md`, `backend/AGENTS.md`, `frontend/README.md`,
`frontend/AGENTS.md`, `frontend/docs/`; root `README.md`/`AGENTS.md`
reduced to pointers. Verified: backend 795/795 tests green from
`backend/`, frontend 309/309 + `tsc --noEmit` + `vite build` green from
`frontend/`, migrations V1–V21 untouched and ordered, no secrets
committed. `backend/postman/` is reserved for the next (API handover)
phase — not created yet.

## Previous Phase

**Phase 4S — Final UI Review, Browser Verification & UX Hardening
(complete, 2026-10-09; frontend-only, no ADR, no migration, no endpoint or
contract change).**

Full-route audit (all 33 routes in `frontend/src/app/App.tsx` inventoried
against guards, navigation, and tests) plus real-browser verification
(headless Chrome 155 against the production `dist` build on `:5173` with
the live backend on `:8080`, desktop 1280px + true-390px mobile via CDP
device emulation, screenshots captured) and live API end-to-end proofs
(register → Mailpit-captured verify → login; business application →
verify → pending → platform approval → discoverable; denial matrix).
One real navigation defect found and fixed; the rest is evidence-driven
hardening. No backend file changed.

Findings fixed (highest priority first):

- **P0 — unknown public URLs rendered the login form instead of the
  404.** The protected branch's layout route is pathless, so it matches
  every URL; its nested `*` then bounced anonymous visitors to `/login`
  via `RequireAuth` before the top-level `*` could ever match (route
  ranking + order tie-break). Reproduced in a real browser (screenshot)
  and in jsdom. Fix: the nested `*` is removed; the single top-level
  `*` renders a context-aware `NotFoundPage` (signed-in → "Back to
  dashboard", anonymous → "Back to sign in"). New regression test for
  the anonymous case; the existing authenticated-404 test still passes.
  Verified fixed in the live browser (screenshot: "Page not found" +
  "Back to sign in").
- **P1 — platform review list/filter rendered unstyled** (`sq-filters`,
  `sq-list`, `sq-list-row`, `sq-list-title`, `sq-list-meta` had zero CSS
  definitions). Added token-reusing styles (filter row, card rows,
  active wash, wrapping long emails, focus-visible rings); verified
  present in the built CSS bundle. The reject-reason textarea is now the
  shared `TextInput` (label, hint, inline error, `aria-invalid`) with
  identical copy and request behavior (a stray `required` that would
  have triggered native bubbles was caught by the test and removed).
- **P1 — pending business applicants saw Manage/Operations navigation
  that can only deny them** (`ADMIN` with no organization yet).
  `navigationForRoles` accepts an optional organization context;
  org-less staff/manager/admin accounts lose only the operational
  sections (customer + platform sections unaffected; role-only call
  sites behave exactly as before). `AppShell`/`DashboardPage` pass the
  session's organization. Covered by 3 new navigation tests.
- **P2 — stale login subtitle** ("Use your organization account" →
  "Use your SmartQueue account", customers are platform-level since
  4R.3); **business-status trailing separator** (`"Bakery · "` →
  `"Bakery"`); **`StatusBadge` tones** for `PENDING_APPROVAL` (warn),
  `REJECTED` (danger), `PLATFORM_ADMIN` (danger) — the core 4R.4 states
  no longer render neutral gray.

Verified live (this phase, real services — no mocks, no invented
accounts beyond timestamped `phase4s-*` test addresses):

- Customer: org-less registration (`verificationRequired: true`),
  pre-verify login `403`, Mailpit-captured verify `200`, token reuse
  `400`, post-verify login (`CUSTOMER`, null org). Invalid-token
  verification also proven in the real browser ("Link not usable" +
  resend form).
- Business onboarding: application creates `PENDING_APPROVAL` + inactive
  membership, owner verifies and logs in (`ADMIN`, null org), owner
  status endpoint reports pending + verified, pending org excluded
  from public discovery, owner `403` on platform APIs.
- Platform admin: configured + verified address logs in with
  `PLATFORM_ADMIN`, pending list/detail work, approve → `ACTIVE`,
  double-approve `409`, approved org becomes discoverable.
- Public routes render with full styling at desktop and true-390px
  widths with zero horizontal overflow (`scrollWidth == innerWidth`
  measured via CDP on all 10 public paths); customer/business
  registration are visually distinct; invalid/valid auth states behave.
- Retracted after measurement: an initial "mobile overflow" reading
  was a headless `--window-size` clipping artifact (layout ran at
  500px, screenshots clipped to 390px) — disproven by CDP-measured
  `innerWidth 390 / scrollWidth 390` plus a true-390px screenshot.
  No such defect exists.

Tests & gates (exact): `npx vitest run` in `frontend/` → **309/309
green** (53 files; 305 baseline + 1 anonymous-404 + 3 navigation; one
platform test extended with a style-class assertion);
`npx tsc --noEmit` clean; `npm run build` green. No backend change, so
no backend suite re-run (per §9 scoping); backend health `UP`
throughout. Browser tooling note: no Playwright/Cypress in the repo
(none added, per §3); verification used headless Chrome + CDP directly.
Console-error collection via CDP script injection did not take in this
Chrome build — recorded as a gap; all pages render fully styled with
no blank screens or failed bundles, and interactions are covered by
component tests. Authenticated pages were not screenshotted (no
localStorage seeding via CLI flags — would need a test hook in prod
code, rejected); their rendering is covered by the 309 component
tests.

Remaining limitations: backend suite state is whatever Phase 4R.4 left
(795/795 then; untouched here); production-relay email and real-device
checks remain out of scope as before. Test-only `phase4s-*` accounts
and one approved test bakery exist in the local dev database only.

**Phase 4R.4 — Organization Onboarding & Approval (complete, 2026-10-09;
ADR-041).**

Business owners submit an application, verify their email, and wait for
authorized platform review. Only approved `ACTIVE` organizations are
discoverable/bookable. Customer registration is unchanged
(platform-level, organization-independent).

Lifecycle (`OrganizationStatus` += `PENDING_APPROVAL`, `REJECTED`;
string-persisted; guarded `approve()`/`reject()`; anything else `409
INVALID_TRANSITION`): `POST /api/v1/auth/register/business` (same DTO —
no status/role fields, unknown JSON ignored) creates `PENDING_APPROVAL`
+ initial branch + owner (`ADMIN` vocabulary) + **`INACTIVE`** owner
membership + verification token/email. Verification consumes the token
only — never activates. Approval (verified owner + `PENDING_APPROVAL`,
row-locked, atomic org-`ACTIVE` + membership-`ACTIVE` + `decidedAt/By`)
or rejection (`INACTIVE` kept, owner-visible reason ≤500, no
internal-notes column by design) are the only exits; repeat/concurrent
decisions fail `409` without forking state (proven by a 2-thread race).

Platform administration: `RoleType.PLATFORM_ADMIN` (no membership, no
tenant scope, review-only). Provisioning is `app.platform.admin-emails`
(`APP_PLATFORM_ADMIN_EMAILS`, empty = fail-closed — review denies
everyone; the first registered user is never crowned). Listing alone
grants nothing: the account must exist, sign in normally, and be
verified before login attaches the role; `PlatformAdminPolicy`
re-checks configuration + live account state per call (stale tokens,
deactivated accounts, config removals, unverified configured emails
all `403`). Registration/business/invitation/management can never
grant it. First-admin procedure: register the address → verify →
set the env → restart → sign in (`/platform/applications`).

APIs: `GET /api/v1/organizations/applications/mine` (owner status:
status + `emailVerified` + owner-safe reason; no-application `404`);
`GET /api/v1/platform/organizations[?status=]`, `GET …/{id}`,
`POST …/{id}/approve`, `POST …/{id}/reject` (all platform-admin only;
`401`/`403` for everyone else incl. org ADMINs). Eligibility:
discovery `branches`/`services` now 404 for non-`ACTIVE`
organizations; `AppointmentBookingService`/`QueueJoinService` refuse
non-`ACTIVE` orgs; operational scope resolution requires org liveness.

Persistence: **V21** (additive only — nullable `rejection_reason`,
`decided_at`, `decided_by_user_id` + FK, `idx_organization_status`;
legacy rows keep status with NULL review columns, stay usable;
V1–V20 untouched). Verified on PostgreSQL 16 via Testcontainers
(columns/index assertions + legacy discoverable/bookable proofs).

UI: public `/register/business` (linked from sign-in + customer
registration, never confused with it; any email provider; no legal-
verification claims), authenticated `/business/status`
(verification-required / pending / approved / rejected + empty/loading/
error states), `/platform/applications` (pending oldest-first, owner +
verification context, approve confirm + applicant-facing reject reason,
`409` surfaced honestly; hidden from all other roles' navigation).
Pending owners (ADMIN, no org) land on their status, not management.

Tests (32 new backend, all green here; full suite **795 run, 0
failures, 0 errors, 0 skipped, BUILD SUCCESS** — 763 baseline + 32;
frontend **305/305 green** — 285 baseline + 20 — plus `tsc` and Vite
production build green; no browser tooling in this environment —
reported, not claimed):
`OrganizationOnboardingTests` (23: pending/inactive creation, invalid
input, duplicate safety, personal providers, verify-doesn't-activate,
token binding/expiry, org-ADMIN/customer/staff/ordinary/unverified-
configured denials, no self-assign incl. post-approval org-scoping,
admin login role + null org, approval gating + atomic activation,
rejection feedback, transition matrix, concurrent-approve race,
owner-status states, no-application 404, pending/rejected exclusion +
direct-call refusal, approval usability, legacy compatibility, V21
migration); `OrganizationOnboardingApiTests` (7, real HTTP: injection
ignored, gmail accepted, 401s, customer 404/403, org-ADMIN 403,
approve/reject e2e incl. discovery + owner reason, unverified 409);
`PlatformAdminMissingConfigTests` (2: empty config denies + grants
nothing, invalid entries dropped/normalized). One existing test
updated to the new contract (`businessRegistersAtomically` →
`businessApplicationIsCreatedPendingWithInactiveMembership`); no other
existing test changed behavior.

Known remaining work (later phases): multi-membership staff
organization selection (owner-status picks one membership);
OAuth-provisioned platform admins (password login only — fail-closed);
appeal/re-application workflow (rejected owners may submit a new
application — documented, not built); no MFA (bootstrap credential
hygiene is the operator's); production-relay deliverability untested
(4R.3.2 limitation carries).

**Phase 4R.3.2 — Email Verification Delivery & End-to-End Testing
(complete, 2026-10-09; narrowly scoped reliability phase, no new endpoint,
no schema change, no auth-contract change, no new ADR, no frontend change).**
Verification email now works end to end locally through the Compose Mailpit
relay, proven by a captured message plus the real verification flow.

Proven root causes (two, distinguished by inspection before any change):

- **Missing configuration (why nothing arrived):** `app.email.provider`
  defaults to `log`, wiring `LogEmailSender`, which records the attempt
  and sends nothing — no SMTP connection was ever attempted in any
  default/local run. The Mailpit service existed in `compose.yaml`, but
  nothing pointed the backend at it (`.env.example` carried no
  `APP_EMAIL_*` values; no doc mentioned email). Registration correctly
  persisted user + credential + token hash and answered `200
  verificationRequired: true`, scheduling an after-commit send that did
  nothing — success perceived, email never sent. Ruled out with evidence:
  sender implementation exists and is correct; message construction is
  correct (subject, frontend `/verify-email?token=` link, honest 1-day
  expiry, ignore-instructions, no password; URL-safe tokens need no
  encoding; origin from configuration, never the `Host` header);
  after-commit degradation is by design (warn-log, resend paths exist);
  token crypto/persistence is correct (SecureRandom 256-bit, SHA-256
  digests only, single-use, TTL-enforced, generic `INVALID_VERIFICATION`).
- ** latent startup defect (would have blocked the fix):**
  `EmailSenderConfiguration` selected the log sender with
  `@ConditionalOnMissingBean` on a sibling `@Bean` method, which
  evaluates in declaration order — with `provider=smtp` both senders
  were created (`NoUniqueBeanDefinitionException` at startup). Proven by
  the new `SmtpEmailSenderTests` failing before the fix; repaired by
  keying both candidates on opposite values of the same property
  (`log` with `matchIfMissing`, `smtp`), so exactly one can match.

Changes (production code: 3 small files + config):

- `email/EmailSenderConfiguration.java` — property-based sender
  selection (above); the `@ConditionalOnMissingBean` rationale comment
  it replaced was factually wrong about sibling-`@Bean` evaluation.
- `email/EmailProperties.java` + `email/EmailConfiguration.java` +
  `application.yml` — new `app.email.smtp.starttls-enabled`
  (`APP_EMAIL_SMTP_STARTTLS_ENABLED`, default `false`): the one genuinely
  missing knob. Local Mailpit stays plain SMTP with no auth; real
  external relays set it `true` (auth still only with a username).
- `.env.example` — `APP_EMAIL_*` block with the working Mailpit shape
  (placeholders only, no secrets); code default stays `log` (safe
  anywhere, explicitly documented).

Tests (17 new; full suite **763 run, 0 failures, 0 errors, 0 skipped,
BUILD SUCCESS — twice consecutively** — 746 baseline + 17 new; focused registration/
verification/email suites green; no regressions):

- `AccountEmailComposerTests` (7, no containers) — link exactness
  (frontend route + token), honest expiry wording per TTL, trailing-slash
  base, no-password/no-extra-personal-data, reset/invitation links.
- `SmtpEmailSenderTests` (6, no containers) — provider wiring both ways,
  STARTTLS/auth propagation, hostless-SMTP fail-fast, and real refused-
  connection failure surfacing as `EmailDeliveryException` with the
  recipient but never the body.
- `SmtpMailpitDeliveryTests` (1, Testcontainers `axllent/mailpit:v1.27`
  — the same image Compose runs) — real SMTP send through
  `SmtpEmailSender`, message read back via Mailpit's HTTP API with the
  actionable link for the right recipient, plus a no-duplicate settle
  check. This is the only delivery proof; mocked-sender tests are
  documented as composition-only.
- `RegistrationServiceTests` (+2) — registration commits user + live
  token through a throwing sender (failure degrades, login stays gated,
  resend recovers) and a token verifies only its own account.
- `EmailPropertiesTests` (+1) — STARTTLS binding + plain-SMTP default.

Live local proof (real backend on `:8080` with `provider=smtp` →
`localhost:1025`, Compose Mailpit, 2026-10-09): customer registration
(no org) → `200 verificationRequired: true`, org empty; message captured
in Mailpit (`Verify your SmartQueue account`, from
`no-reply@smartqueue.local`, link
`http://localhost:5173/verify-email?token=…`, "expires in 1 day",
ignore-instructions); login before verify → `403 EMAIL_NOT_VERIFIED`;
live-link verify → `200`; reuse and garbage tokens → `400`; login after
→ Bearer `CUSTOMER` with empty org; resend-after-verified → generic 200
with no second message. Logs carry no token fragment, password, or
recipient email (verified by search); lifecycle lines log user id only.

Docs updated in the same change: `README.md` (email env table, Mailpit
setup steps, fixed a stale register line that still described
organization selection), `development/README.md` (new "Account
email" section: start/config/inbox walkthrough, production-relay
differences, pending-vs-delivered failure semantics),
`api/auth.md` (delivery configuration + failure note, no contract
change), `.env.example` (above).

Remaining limitations (recorded, not hidden): local SMTP capture is not
a production-readiness claim — real-relay deliverability (SPF/DKIM,
reputation, bounces) is untested and lives with the domain operator;
the code default stays `log`, so any environment without the `smtp`
values still sends nothing (by documented design); delivery failures
stay log-observable only (response semantics unchanged: committed token
= pending, resend recovers); no resend-coverage gap was found (the
endpoint already existed and is now exercised live); frontend untouched
(no UI files changed — frontend tests/typecheck/build not re-run per
the phase's conditional scope).

**Phase 4R.3.1 — Backend Test Baseline Repair (complete, 2026-10-09;
test-only, no production change, no new ADR).** All 11 problems reported at
the end of Phase 4R.3 are resolved; the backend suite is green twice in a
row. Zero production files changed (verified: every root cause was a
fixture, transaction-boundary, or expectation defect against an otherwise
correct contract); 7 test files repaired (the 8th reported problem,
`missingQueueReturns404`, was verified already-correct and untouched).

Reproduced starting baseline (`.\mvnw.cmd test` in `backend/`, real
Testcontainers PostgreSQL/Redis, before any change): **746 run, 4 failures,
6 errors, 0 skipped** — 10 of the 11 reported problems reproduced. The
11th, `ScheduleStatusApiTests.managerCanReadScheduleStatus`, passed in that
run only because 09:45 IST falls inside the branch's 09:00–17:00 working
hours; it is wall-clock-dependent and was repaired anyway. The reported
"5 failures" vs reproduced "4 failures" is exactly that test.

Root causes and repairs (all test-only):

- `AnalyticsServiceTests` (3): the shared `entry()` helper reused one
  customer, so any test inserting two active entries hit the
  `uq_active_entry` partial unique index (`windowBoundaries…`,
  `fallBackDay…`); and `invalidRanges…` passed `(to, from)` — a valid
  [Oct 7, Oct 8) window — so it never threw. Helper now mints a fresh
  customer per entry (no assertion depended on sharing); the range test
  passes the genuinely inverted `(from, to)`. Uniqueness constraints
  untouched. 18/18 green.
- `AppointmentReminderTests` (1): `sameInstant…` booked two overlapping
  appointments for one customer, violating the `no_customer_double_booking`
  exclusion (double-booking prevention working as designed). Second
  appointment now uses a second customer (services already differed);
  assertions strengthened to per-customer IST/EDT message checks. 18/18.
- `CalendarManagementTests` (2): the deliberate 409 probes
  (overlap/duplicate) fired exclusion/unique violations inside the shared
  rollback-test transaction, aborting it in PostgreSQL and cascading into
  every later statement. Probes now run in their own top-level transaction
  (`REQUIRES_NEW`, mirroring production's one-transaction-per-request)
  after committing the random-tag fixtures they must see (a separate
  transaction cannot see uncommitted rows; `TestTransaction` commit +
  fresh rollback transaction; savepoint-nested was tried and is
  unsupported by the JPA dialect). Translated 409s still asserted; no
  exception suppressed. 4/4 green.
- `NotificationApiTests` (1): `senderFailure…` called the
  `REQUIRES_NEW` service on a manually constructed (unproxied) instance
  with no surrounding transaction — detached recipient (lazy roles, no
  session) plus no synchronization for the after-commit echo. Now runs
  inside a real transaction, reproducing the production AFTER_COMMIT path;
  row commits, failing echo is caught/logged, row stays. 13/13 green.
- `QueuePreJoinEstimateTests.estimateFormula…`: expected a newcomer's
  60-min pre-join estimate to equal the second entry's 30-min per-entry
  estimate — different positions, different models. Now asserts the true
  agreement (pre-join estimate with N waiting == per-entry estimate of the
  entry joining at the back) plus the documented one-unit divergence.
- `QueuePreJoinEstimateTests.inactiveQueue…`: tried to join an
  already-inactive queue (join contract rejects it, 409). Now joins while
  ACTIVE, deactivates, and asserts the null estimate (waiting row proven
  present via JDBC; `waitingCount` is 0 by design — the rank scope, like
  claiming, covers ACTIVE queues only).
- `ScheduleStatusServiceTests.sameInstant…`: expectations inverted —
  14:30Z is 20:00 IST (CLOSED) and 09:30 EST (OPEN). Swapped to the
  documented timezone contract; production logic untouched.
- `ScheduleStatusApiTests.managerCanRead…`: asserted OPEN against the real
  clock. Now saves a holiday on the branch-local today (precedence over
  working hours at any hour) and asserts the explicit HOLIDAY state.
- `QueuePreJoinEstimateApiTests.missingQueueReturns404`: verified
  already-correct (creates a same-branch orphan service → genuine 404
  `QUEUE_NOT_FOUND`; the cross-branch 422 is covered by its sibling test).
  Untouched. 9/9 green.

Verification (`.\mvnw.cmd test` in `backend/` throughout): each affected
class individually green; all 8 affected classes together 113/113 green;
identity/registration/appointment/queue/concurrency/notification/tenant/
membership regression sweep 338/338 green (incl.
`CustomerPlatformIdentityTests` 7/7, concurrency proofs, V20 membership
suites); full suite **746 run, 0 failures, 0 errors, 0 skipped, BUILD
SUCCESS — twice consecutively** (~14 min each). No remaining failures; no
newly discovered independent failures. Identity contracts, tenant
isolation, and security invariants intact (membership-grounded policy,
resource-derived customer scope, and cross-tenant denial suites all green;
no authorization semantics changed).

**Phase 4R.3 — Platform-Level Customer Identity, Registration UX &
Membership-Based Authentication (complete, 2026-10-09).** Customer
registration takes no organization (frontend + backend); customers
authenticate platform-level (no org, no membership, optional JWT `org`
claim); operational authorization is grounded in ACTIVE
`OrganizationMembership` (login resolution, policy re-verification,
invitation/user-directory provisioning); customer appointment/queue/
availability/schedule/notification operations derive scope from the target
resource with owner-scoped reads plus an explicit, documented tenant-context
bridge. **Unchanged (later phases):** organization approval
(`PENDING_APPROVAL`/`PLATFORM_ADMIN`), multi-membership staff context
selection, OAuth provisioning contract.

Entries below are the historical phase log, oldest first; each entry stands as
written when its phase completed. (The phase-3B entry that previously opened
this section is retained as history.)

**Phase 3B — Redis Foundation (complete, 2026-10-06).**

Redis as bounded derived-state infrastructure: dependency, Compose service,
env config, JSON + key conventions, TTL policy, health semantics, and
Testcontainers verification. Zero authority moved; zero consumers yet.

**Phase 3C — Real-Time Queue State & WebSocket/STOMP (complete, 2026-10-06).**

Live queue snapshots: Redis projections rebuilt from PostgreSQL on every
committed queue event, delivered over STOMP topics with on-demand snapshot
requests. PostgreSQL authoritative; no auth on sockets yet (deferred).

**Phase 3D — Live Wait-Time Refresh & Projection Freshness (complete, 2026-10-06).**

Bounded time-driven freshness on top of the event-driven snapshots: active
queues are rebuilt from PostgreSQL every minute (configurable), reusing the
Phase 2I estimator and Phase 3C snapshot contract, with guarded writes so a
scheduled rebuild can never regress a newer event-driven projection.
PostgreSQL authoritative; no new migration, no new dependencies.

**Phase 3E — Multi-Tenant Context & Application Isolation (complete, 2026-10-06).**

Application-level tenant boundary on top of the existing database
integrity: immutable `TenantContext` (organization + optional branch),
fail-closed holder, scope-aware repositories, and scope enforcement in
call/lifecycle/estimate/projection paths — with background tasks (scheduler,
event bridge) installing explicit per-unit scope instead of depending on
request context. No authentication, no JWT, no REST; V1–V8 untouched.

**Phase 3F — Authentication & Authorization Foundation (complete, 2026-10-06).**

BCrypt credentials in a separate table, self-contained HS256 JWTs, a
single login endpoint, a tenant-bound security context, a reusable
org/branch/role authorization policy, and authenticated WebSocket
handshakes with tenant-scoped subscriptions. V9 migration; two
dependencies; no refresh tokens, no lockout/rate-limit/audit.

**Phase 3G — REST API & Contract Foundation (complete, 2026-10-06).**

Reusable HTTP conventions on top of the 3F security boundary: `/api/v1`
versioning, package-by-feature thin controllers, entity-free DTOs with
explicit manual mapping, Jakarta shape validation vs domain validation,
one `ApiError` envelope from one global handler (login keeps its statuses
and generic messages inside the shared envelope), scope-safe `404`s plus
policy-driven `403`s, two representative reads (own organization,
same-organization branch) proving JWT → principal → scope → policy →
service → DTO, and an OpenAPI document with the `bearerAuth` scheme
(springdoc 3.1.1, the Boot 4 line). One new dependency; no migration; no
CRUD expansion; concurrency paths untouched.

**Phase 3H — Organization & Branch Management API (complete, 2026-10-06).**

First management surface on the 3G conventions: own-organization read
(unchanged 3G endpoint) plus branch list (paginated, allow-list sorted,
status-filtered), get, create (`201` + `Location`, org from principal),
full-replacement PUT update, and idempotent activate/deactivate commands
(no DELETE — status lifecycle, dependents never cascade). Writes require
MANAGER/ADMIN in the caller's own organization (new `TenantAccessPolicy`
management tier); foreign ids stay scope-safe `404`s; duplicate names
translate the unique constraint to `409 BRANCH_NAME_CONFLICT`
(cross-org duplicates legal). Reusable `common/api` pagination
(`ApiPageRequest` + `ApiPage` + `InvalidRequestException`); no migration;
no new dependencies; concurrency paths untouched.

**Phase 3I — Service & Counter Management API (complete, 2026-10-06).**

Branch-nested collections with flat item routes: service/counter
list (paginated, allow-list sorted, status-filtered), get, create
(`201` + `Location`, branch from path), full-replacement PUT, idempotent
activate/deactivate (no DELETE), plus the capability association API
(ordered plain-array list, `201` add, `204` remove). Same matrix
(display reads; MANAGER/ADMIN org-scoped writes via the existing policy
methods); same-branch capability invariant enforced in code (`422`)
with the V3 composite FKs as backstop; name/pair conflicts
constraint-arbitrated (`409`s, no SQL leaks). No migration; no new
dependencies; concurrency paths untouched.

**Phase 3J — User & Staff Management API (complete, 2026-10-06).**

Tiered-visibility directory on the same conventions: management-only
user list/get/create/update/activate/deactivate, self-or-manager reads,
operational branch rosters (assigned staff included, customers denied),
user-nested plus flat assignment routes with idempotent
activate/deactivate (no deletion — history preserved). Email is
immutable identity (global unique, `409`); roles replace wholesale and
stay org-scoped (no platform admin); optional creation-time password is
BCrypt-hashed immediately and never returned; deactivation preserves all
references and fails login generically (proven through real HTTP login).
Assignments require the STAFF role; the status-blind unique makes
reactivation the path back; deactivation revokes branch access at once.
No migration; no new dependencies; concurrency paths untouched.

**Phase 3K — Appointment Management API (complete, 2026-10-06).**

HTTP over the untouched 2E engine: nested self-service booking (`201` +
`Location`, customer always the principal, future starts, branch/service
agreement with `422` on same-org mismatch), privacy-first reads (owner/
operational scope, scope-safe `404` otherwise), role-routed listing
(customers pinned to self, staff naming assigned branches, managers with
scope-checked facets, overlap window, chronological default sort), and
lifecycle commands calling the guarded transitions (cancel owner-or-
management, complete/no-show operational scope, honest `409`s — not
idempotent). One conjunctive search query (flag-bound nullable instants,
a PostgreSQL typing lesson recorded). No migration; no new dependencies;
2E booking + race suites untouched.

**Phase 3L — Queue Management API (complete, 2026-10-06).**

Thin adapter over the untouched engine: self-service walk-in joins
(`201` + `Location`, server tokens/positions/waits, triage gated to
operational staff), branch queue display, live snapshots rebuilt from
PostgreSQL per read (shared rank query + estimator, identity added for
authorized eyes), paged record listings (stored-field sorts, no live
positions in bulk pages), counter-centric atomic claims with explicit
branch tenant scope, and lifecycle commands on the guarded transitions
(honest `409`s). Privacy tiers match 3K; events/realtime flow unchanged
(join lands in Redis before its 201); REST never reads Redis (dedicated
Redis-down proof). No migration; no new dependencies; 2F–2I/3C/3D suites
untouched.

**Phase 3M — Service Session & Counter Operations API (complete,
2026-10-06).**

Read-only operational visibility over measured interactions: session
list (conjunctive facets, newest-first default via shared direction
support), privacy-first item reads, per-counter current session (domain
running definition, newest wins; idle 404 doubles as the derived
occupancy signal), and paged counter history. Operational tiers only
(managers/admins, assigned staff; customers have no session views);
derived `active`/`durationMinutes` documented as derived; N+1 accepted
at the page cap like every other surface. No lifecycle commands (3L
owns them), no ADR (pure template application), no migration, no new
dependencies.

**Phase 3N — React Frontend Foundation & Auth Shell (complete,
2026-10-07).**

Thin client of the existing API: React 18 + strict TypeScript + Vite 6
+ React Router 6 with no store library (ADR-028). Real login against
`POST /api/v1/auth/login` (exact contract, no `/me` invented — identity
from the login response + JWT claims); `localStorage` session with
honestly documented tradeoffs (no refresh infrastructure exists, none
faked); centralized API client with typed `ApiError`s; `AuthProvider`
as the single auth authority; protected routes (`/login`, `/dashboard`,
role placeholders, `/unauthorized`, 404); role-aware shell navigation
(UX only — backend enforces); dashboard shell with no fake metrics;
responsive login + shell; 33 Vitest tests green; production build green.
Backend diff is CORS-only (`infrastructure/cors` allow-list on
`/api/**`, default Vite origin; `app.cors.allowed-origins` for
production) — 452 backend tests green on re-run.

**Phase 4A — Customer Appointment & Queue Experience (complete,
2026-10-07).**

First real product on the 3N shell, CUSTOMER-only, zero backend changes:
customer dashboard (next appointment + current queue, real data only),
appointment list (status filter, pagination), detail (branch-local times,
confirmed owner-cancel), booking (branch → service → branch-local start,
no faked availability — backend validates), queue join (empty body,
server token/position/wait), `/queue/me` (id-only `localStorage`,
authoritative entry reads, terminal/gone states, owner leave), and live
status via a dependency-free STOMP client used as refresh signal +
waiting-count context with explicit degradation (banner + manual Refresh
+ 30 s REST polling). Null waits render "Not currently available";
`customerId`/triage are never sent; staff snapshot endpoints never
called. 43 new Vitest cases (76 total frontend); production build green;
452 backend tests green on re-run.

**Phase 4B — Staff Operational Experience (complete, 2026-10-07).**

Operational workspace for STAFF (plus MANAGER/ADMIN org-wide), zero
backend changes: branch context from own ACTIVE assignments
(managers list the org), dashboard with queues + derived counter
occupancy, live queue operations (rank-ordered snapshot + entry
history with status facet), counter workspace (counter-centric atomic
call-next, start with workspace counter, complete/no-show/cancel with
confirmations, double-submit guards as UX only), session history
(running/ended filter, backend-derived durations), branch-scoped
appointments (own-live-row cancel only). Realtime reuses the shared 4A
STOMP client as a refresh signal with banner + manual Refresh (no ops
timer polling by decision). Cancel shown to managers only; 404 on
current-session means free. 37 new Vitest cases (113 total frontend);
production build green; 452 backend tests green on re-run.

**Phase 4C — Manager & Admin Experience (complete, 2026-10-07).**

Management workspace for MANAGER/ADMIN, zero backend changes:
overview with real organization counts only (no invented KPIs),
branch directory (status filter, allow-list sort, pagination) + detail
hub (facts, lifecycle with confirmations, tabbed service/counter/
staff previews) + full create/edit forms mirroring the request DTOs,
branch-scoped service management (inline create dialog, detail with
PUT + lifecycle), branch-scoped counter management with capability
add/remove (same-branch 422 and duplicate 409 surfaced as sentences),
staff directory (status + role filters) + profile create/edit
(creation-only optional password, never rendered back; role changes
confirm) + lifecycle + assignment workflows (activate/deactivate with
confirmations, STAFF-role gating explained). Design system evolved
(tokens, tables with card fallbacks, skeletons, breadcrumbs, toasts,
dialogs) with reduced-motion support. CUSTOMER/STAFF denied on every
`/manage` route (UX redirect; backend enforces). 17 new Vitest cases
(130 total frontend); production build green; management API suites
re-run green; full backend suite 452 run with 1 pre-existing
concurrency flake (`AppointmentConcurrentBookingTests` deadlocked
under full-suite load — passes in isolation, no backend files
touched in this phase).

**Phase 4D — In-App Notifications (complete, 2026-10-07).**

Persistent notification center, PostgreSQL-authoritative: V10
`notification` table (recipient timeline + unread partial indexes,
`event_id` uniqueness backstop); `notification/` feature module
(entity, string-persisted type/reference enums, repository,
`NotificationService` with REQUIRES_NEW persist-first + best-effort
`NotificationSender` echo, AFTER_COMMIT listener mapping six
customer-meaningful facts to rows, recipient-scoped REST controller
with `NotificationResponse` DTO); realtime echo over the existing
STOMP broker via per-session user destinations (session named by
user id, `/queue` broker prefix, one explicitly allowed subscribe
path — no id in any subscribable destination); frontend bell with
database-derived badge, panel (mark-read/all-read, deep links,
live/offline status), full `/notifications` page (filters,
pagination), live client with degradation, QUEUE_CALLED-only toast.
No staff broadcasts, no appointment-lifecycle notifications, no
exactly-once claims, no email/SMS/brokers, no new
dependencies (retention arrived in Phase 4E). 18 new backend tests (13 API incl. live-socket
recipient isolation + 5 persistence); 20 new frontend tests (150
total); production build green.

**Phase 4E — Notification Retention & Cleanup (complete, 2026-10-07).**

Bounded, explicit, tenant-safe cleanup for the notification table:
`notifications.retention` policy (`enabled`, `read-after` P90D,
`unread-after` P365D, `interval` PT6H — all validated, fail fast,
disableable), eligibility on `created_at` with strict cutoffs,
id-batched bulk deletes (500 per batch, no hydration, per-batch own
transactions), scheduled tick reusing the existing `@EnableScheduling`
setup, V11 `created_at` index (both V10 indexes lead with recipient
and cannot serve the sweep). Creation and retention share only the
table. No reminders, no preferences, no archival, no locks, no new
dependencies, no frontend changes. 15 new backend tests (7 properties,
6 behavior, 2 disabled).

**Phase 4F — Appointment Reminder Scheduling & Delivery Foundation (complete, 2026-10-08).**

Upcoming-appointment reminders through the existing in-app channel:
`appointment.reminders` policy (`enabled`, `before` PT24H, `interval`
PT10M — validated, fail fast, disableable), eligibility on stored
instants (`startAt − before ≤ now`, still upcoming, still
SCHEDULED/CONFIRMED, no reminder row yet), branch-timezone rendering
(DST-safe, no stored due times), durable `appointment_reminder` rows
with database-enforced `uq_appointment_reminder` claim arbitration
(one atomic reminder+notification transaction per candidate, bounded
200-per-tick discovery over `(now, now + before]`), customer-only
`APPOINTMENT_REMINDER` notifications with appointment references,
catch-up after downtime, and documented race/failure semantics
(at-least-once ticks, at-most-once rows, no exactly-once claim). V12
migration (reminder table + status/start index); no new dependencies;
one frontend label+glyph addition (deep links already worked).
30 new backend tests (7 properties, 18 behavior incl. TZ/DST, 2
disabled, 1 concurrency, 2 end-to-end); 1 new frontend test (151 total).

**Phase 4G — Operational Analytics & Reporting (complete, 2026-10-08).**

Manager/admin operational reporting over committed PostgreSQL state:
one read-only `GET /api/v1/analytics/overview` with explicit local
`[from, to)` windows (366-day cap, never truncated), explicit IANA
timezone (param wins, else branch zone, org-wide requires it),
branch/service filters, and one shared effective scope for summary
plus breakdowns. Metrics: appointment/queue status splits (current
status only, sums verified), measured wait
(`session.startedAt − joinedAt`, never Phase 2I estimates),
session counts/durations (running counts, never durations),
service/branch breakdowns (volume-ordered). Zero data → `0` counts,
`null` averages. Tenant-pinned (org from principal, scope-safe
404s, 422 same-org mismatch); staff/customers denied by the existing
management gate. V13 adds three predicate-shaped indexes; no new
dependencies. Manager analytics page (4C design language, no fake
KPIs/charts). 30 new backend tests (4 properties, 18 service, 8
API); 10 new frontend tests (161 total).

**Phase 4H — Production hardening (complete, 2026-10-08; no new
business subsystem).**

Full-system audit (two independent passes: backend
security/tenant/transactions, frontend workflows/states/a11y) with a
P0–P3 assessment. No P0 found — auth, tenant isolation, lifecycle,
and transaction cores verified sound. Fixed: (backend) WebSocket
handshake origins now follow the CORS allow-list (was `*` with a
stale comment); notification and reminder realtime echoes moved to
after-commit delivery (no phantom push the center cannot re-read);
snapshot controller enforces the full queue-scope policy as a second
layer (matching its documented contract); JWT rejects tokens at the
exact expiration instant (RFC 7519 §4.1.4) + boundary test.
(frontend) appointment-detail errors branch to retry vs not-found by
code; booking/join dependent-select failures show errors with retry
instead of infinite spinners; Service/Counter lists show branch-load
failures instead of "no branches"; shared `ConfirmDialog` + 4
bespoke dialogs gained Escape-to-dismiss, safe-action autofocus, and
mid-mutation dismiss guards (new `useDismissOnEscape` hook);
occupancy/branch-filter/analytics-filter failures surfaced with
retry; terminal/fresh-counter live banners suppressed when nothing
is subscribed; manage detail/form loads gained Try-again; queue-ops
history page resets on queue/tab switch; notification mark-read
rejection no longer an unhandled promise. Residual P2s documented,
not hidden: preview-subsection errors show text without retry;
`Instant.now()` in queue/audit timestamps bypasses `Clock`
(testability only, no defect — constructor/signature churn
rejected); Redis write inside the read-only projection path left
untouched (rollback there is speculative). No new ADR (no
architectural decision changed — fixes align code with ADR-021/028/
032). 1 new backend test (JWT boundary, executed here); 3 new
frontend tests (164 total).

**Phase 4K — Queue auto-provisioning & backfill (complete, 2026-10-08).**

Production gap fix: the one-queue-per-(branch, service) invariant had
no production creation path (rows existed only via tests/seeds), so a
REST-created service lacked its walk-in queue. Service creation now
provisions the `ACTIVE` queue in the same transaction via the new
`queue.application.QueueProvisioningService` seam (find-or-create;
existing rows returned untouched, never reactivated/duplicated; no
status mirroring or cascading — the queue stays the independent
walk-in kill-switch; the unique constraint stays the backstop). V19
backfills pre-existing pairs idempotently (`WHERE NOT EXISTS` + `ON
CONFLICT DO NOTHING`; existing ids/statuses/history preserved).
No queue CRUD, no endpoint or response change (`201` + `Location`
unchanged, no queue id embedded), no frontend work — existing queue
UI simply starts working for new services. `demo-seed.sql` keeps its
explicit queue inserts (raw SQL bypasses the application seam).
8 new backend tests (7 provisioning + 1 rollback, all green here);
see ADR-038. Full-suite state at merge: 622 run with 7 pre-existing
failures in untouched suites (calendar/analytics/reminder/
notification shared-transaction/fixture bugs — recorded in the phase
report, not fixed here).

**Phase 4M — Appointment availability API (complete, 2026-10-08;
backend only; no frontend slot picker).**

`GET /api/v1/branches/{branchId}/appointments/availability?serviceId=…&date=YYYY-MM-DD`
(authenticated, same-org, scope-safe 404s, 422 on branch/service
mismatch, 400 on inactive/invalid). Advisory continuous free-time
search mirroring booking exactly: branch-local date in the branch
IANA zone, holiday veto, whole-interval fit inside one working
interval (splits never merged), strict-future start, and half-open
`[start,end)` overlap against the caller's live service + customer
appointments. One-minute candidate steps derived from the domain's
minute granularity — no fixed grid, no new increment config.
`java.time` gap/overlap resolution with round-trip check (nonexistent
local starts skipped, ambiguous starts bookable — identical to
booking). `java.time.Clock` reused (no second policy). Reuses the
existing booking predicates; no rule duplication. 32 new backend
tests (20 service + 12 API over real PostgreSQL/Testcontainers, all
green); no migration, no new index. Booking excludes untouched —
exclusion constraints stay authoritative, availability is advisory.
No slot holds/reservations. New ADR-039.

**Phase 4N — Customer appointment availability & booking UX
(complete, 2026-10-08; frontend only).**

The customer booking page now drives off the Phase 4M availability
API instead of free date/time entry: branch → service → date →
advisory slot buttons (rendered in the branch timezone, never the
browser zone) → confirmation summary (branch/service/date/time/
duration) → existing `POST …/appointments` booking contract.
Distinct states: loading, loaded-with-slots, closed day (empty
intervals), holiday (`Closed for {name}`), fully booked (intervals
but no slots), API error + retry. A 409 `APPOINTMENT_CONFLICT` on
submit shows "no longer available", refreshes availability, and
preserves branch/service/date context — no auto-retry, no
auto-alternate, no holds. Stale-response guard via request id;
duplicate submit disabled while pending. Slot buttons are
`role="option"` with `aria-selected`, keyboard-focusable, min
touch target, wrapping grid that goes 2-up at ≤560px. No
scheduling rules reimplemented client-side; ADR-039 exclusivity
honoured (slots are advisory, booking re-arbitrates). No new
ADR (no architectural decision — UX maps the existing contract).
12 new frontend tests (206 total, green); `tsc` + Vite build green.
Browser verification not performed — no browser tooling in this
environment (reported, not claimed).

**Phase 4L — Working-hours & holiday management UI (complete,
2026-10-08; frontend only, no backend change).**

First scheduling-configuration UI over the existing 2D contracts:
branch-detail Hours/Holidays tabs (manager/admin) with weekday
groups Mon–Sun, split intervals rendered as-is, `Closed` empty days,
immutable intervals (delete + recreate, no edit form), date + name
holidays with upcoming/past split and per-day entry guidance, branch
timezone bannered and never converted, shared ConfirmDialog deletes
(Escape, safe-action focus, pending guards), honest loading/empty/
error/retry states with authoritative re-reads after mutations, and
a staff read-only Today's-hours strip on the operations dashboard
(selected branch, branch-local weekday). Customers call no schedule
API — availability/status arrive in a later phase. 33 new frontend
tests (197 total, green); `tsc` + Vite build green. Backend
regression: consumed suites unchanged (calendar suite shows the same
pre-existing 1F+1E signature as Phase 4K; persistence 18/18 green).
Browser verification not performed — no browser tooling in this
environment (reported, not claimed). No ADR (no architectural
decision; UI maps existing contracts 1:1).

**Phase 4Q — Customer authentication & onboarding UX (complete,
2026-10-09; frontend only, zero backend change).**

Customer-facing onboarding over the existing authentication contracts
(registrations, verification, recovery, invitations, rate limiting,
session revocation all already existed on the backend — nothing was
redesigned or added there, including no new backend endpoint):

- `POST /api/v1/auth/register` (`organizationId` required, `displayName`,
  `email`, `password`) → `RegistrationResponse {userId, organizationId,
  branchId:null, email, verificationRequired}`.
- `POST /api/v1/auth/verify-email`, `/resend-verification`,
  `/forgot-password`, `/reset-password`, `/change-password`
  (authenticated) — exact DTOs, no invented fields.
- `POST /api/v1/invitations/accept` (public) → `{userId,
  organizationId}`; role/organization/branch stay backend-owned.
- `GET /api/v1/discovery/organizations` (public, ACTIVE only, id+name)
  powers explicit organization selection.
- New frontend routes: `/register`, `/verify-email`, `/forgot-password`,
  `/reset-password`, `/invitations/accept` (all public) and
  `/account/security` (authenticated, every role). Emailed-link routes
  are deliberately NOT wrapped in `RedirectIfAuthenticated` so a link
  works regardless of an open session; `/login`, `/register`,
  `/forgot-password` still bounce signed-in users to the dashboard.
  Role guards and the backend boundary are unchanged.
- Organization choice is explicit: selected from the public ACTIVE
  directory; a `?organization=<id>` link is honoured only when the
  directory returns that id, never inferred from location, branch,
  email domain, or frontend config. Inactive organizations and
  administrative organization data are never exposed (the public DTO
  carries id+name only).
- Error copy centralized in `auth/utils/accountErrors.ts`:
  `WEAK_PASSWORD`, `USER_EMAIL_CONFLICT`,
  `ORGANIZATION_NOT_FOUND`, `INVALID_VERIFICATION`, `INVALID_RESET`,
  `INVALID_PASSWORD`, `INVALID_INVITATION`, `EMAIL_NOT_VERIFIED`,
  `RATE_LIMITED`, plus status-based fallbacks for unknown codes — raw
  codes, SQL text, UUIDs, and exception names cannot reach the screen.
  Sign-in failures stay generic about which credential was wrong.
- Password rules mirrored from the backend policy (8–72 chars, at least
  one letter and one digit) for pre-submit guidance only; backend
  validation stays authoritative. Client-side checks never replace it.
- No token or password is logged, stored, or rendered; the reset token
  lives only in the URL and in the request body; no frontend-only
  authentication state was introduced (`AuthContext`/`ApiClient`
  unchanged in shape, plus one `errorCode` field for the
  verification-required branch).
- No OAuth buttons or placeholders, no marketplace/search, no
  SMS/OTP/MFA, no business-registration change, no new endpoint.
- 76 new frontend tests (286 total, 0 failures); `tsc` + Vite production
  build green. Browser verification not performed — no browser tooling
  in this environment (reported, not claimed).
- Repaired during this phase (pre-existing, unrelated to auth, required
  to get a green suite/build): `ScheduleStatusCard.tsx` had two unused
  imports; `JoinQueuePage.test.tsx` had two unused stub parameters;
  `BookAppointmentPage.test.tsx` never stubbed
  `/branches/{id}/schedule-status`, so the generic branch-list match
  answered with a page envelope and crashed the card — 11 failures at
  baseline. All four fixed; the 210-test baseline is now 210 green.

Limitations recorded (not invented around): the backend answers
`INVALID_VERIFICATION` for expired, unknown, reused, and
already-consumed verification links alike, and `INVALID_RESET` for
expired/unknown/reused reset links, so those share one honest state
each rather than a guessed distinction; there is no read-only
"describe this invitation token" endpoint, so no organization/branch
context can be shown before acceptance (the invitation email carries
it); `api/auth.md` still documents only `POST /api/v1/auth/login`
and is stale for the newer backend auth endpoints — left untouched
because no contract changed in this phase (recorded as debt).

**Phase 4Q.1 — Customer identity & organization membership architecture
audit (complete, 2026-10-09; decision/audit only, no code touched).**

Full read-only audit of the identity/tenancy model against the intended
product behavior. Findings: customers are structurally organization-owned
(`app_user.organization_id NOT NULL`, V4; JWT `org` claim required;
`TenantContext` organization-required; `TenantAccessPolicy` org equality;
composite FKs `(customer_id, organization_id)` on `appointment`/`queue_entry`,
V6/V7; `QueueJoinService` rejects cross-org customers), so one account
cannot interact with multiple organizations — an architectural constraint,
not a UI issue. Also found: business registration creates an ACTIVE,
instantly discoverable organization with no approval mechanism (email
ownership is the only verification that exists); invitations already
restrict to MANAGER/STAFF (admins cannot invite customers — already the
desired behavior). Delivered a 18-section decision report recommending the
membership model and a new ADR.

**Phase 4R.1 — ADR-040 & architecture documentation correction (complete,
2026-10-09; documentation/ADR only).**

The Phase 4Q.1 audit and its recommended target were reviewed and accepted.
This phase recorded the decision and corrected architecture documentation.
Nothing else changed.

- **ADR-040 — Customer Identity and Organization Membership Model**
  (`decisions/ADR-040-customer-identity-organization-membership.md`)
  created, Status: Accepted. It **supersedes ADR-036** (organization-scoped
  customers), which was marked `Status: Superseded by ADR-040` with its
  historical rationale preserved (not rewritten).
- Target identity model recorded: **customers are platform-level
  identities** (self-register, email-verified, no organization membership,
  book/join at multiple ACTIVE organizations, owner-scoped reads across
  organizations; selecting an organization for an action is request context,
  never membership).
- Target membership model recorded: **`OrganizationMembership`**
  (user + organization + role ∈ STAFF/MANAGER/ADMIN + status) is the sole
  authorization fact for operational access — no row, no access; the branch
  `StaffAssignment` concept is unchanged; organization ADMIN stays
  organization-scoped, never platform-wide.
- Tenant model recorded: `TenantContext` remains the **operational** scope
  (organization resolved from membership); customers receive **no global
  tenant context** — organization is derived from the target resource
  (`branchId`/`queueId`/`appointmentId`), validated ACTIVE, then the
  operation is owner-scoped. A customer-supplied organization ID is never an
  authority for access.
- Registration decision recorded: future customer contract is
  `POST /api/v1/auth/register` with `{displayName, email, password}` and **no
  `organizationId`**; business registration stays the separate
  `POST /api/v1/auth/register/business` organization-application flow.
  **Neither endpoint was changed.**
- Organization onboarding target recorded: business registration will create
  `PENDING_APPROVAL`, owner email verification proves inbox ownership only,
  and platform review decides ACTIVE. **Email-domain verification is
  explicitly rejected** (local businesses use Gmail/Outlook/Yahoo). A future
  `PLATFORM_ADMIN` concept (review/approve/reject, onboarding status) is
  documented as a target; it does not exist.
- Security invariants recorded (binding on implementation): customer
  isolation (own rows across orgs; never another customer's), organization
  isolation (membership-gated), no forged customer scope (resource-derived
  organization), no privilege crossover (CUSTOMER never grants operational
  roles), invitation security (org/role/branch server-owned from the token).
- Planned migration sequence documented in `database/README.md`
  (membership + backfill → customer FK un-pinning → nullable identity column
  → optional review persistence). **No V20/V21/V22 migration exists; the
  latest applied version is V19.**
- Documentation corrections: `api/auth.md` rewritten from stale
  (login-only) to the full implemented surface (login, customer register,
  business register, verify/resend verification, forgot/reset/change
  password, invitations, OAuth, discovery) with a clearly-labeled ADR-040
  **target** section; `architecture/system-architecture.md` gained the
  identity/membership section (current vs target, Planned-tagged);
  `api/appointments.md` and `api/queues.md` gained identity-model
  notes (current org-scoped behavior enforced; target labeled not
  implemented); `database/README.md` applied-migrations table now lists
  V14–V18 (they existed since Phase 4I but were missing there — a real
  staleness gap fixed here) plus the planned-migration strategy.
- **Documentation honesty enforced:** no doc claims cross-organization
  booking, PLATFORM_ADMIN, organization approval, or organization-less
  customer registration exists today. All are marked approved target /
  upcoming (Phase 4R implementation stages).
- **Explicitly not done (by design):** no Java/React production change, no
  schema change, no migration, no JWT/TenantContext/authorization change, no
  endpoint change, no frontend change, no tests run (nothing to test — no
  code changed). This repository is not a git checkout in this environment,
  so `git diff` is unavailable; the changed-file list in the phase report is
  the record.

**Phase 4R.2 — Customer Identity Database & JPA Migration (complete,
2026-10-09; schema + persistence foundation only, per ADR-040).**

Implements the persistence half of ADR-040. **Not migrated (by design,
later 4R phases):** JWT/claims, `AuthenticatedPrincipal`, `TenantContext`,
login/registration behavior, discovery, appointment/queue/notification
authorization, OAuth, and all frontend code. The running application still
reads `app_user.organization_id` for customer principals; existing customer
login, registration, booking, and joining behave exactly as before.

- **V20 migration** (`src/main/resources/db/migration/V20__organization_membership.sql`):
  1. creates `organization_membership` (`id`, `user_id`, `organization_id`,
     `role`, `status`, timestamps; `uq_membership_user_organization UNIQUE
     (user_id, organization_id)`; `idx_membership_organization`);
  2. backfills one membership per pre-V20 operational user (STAFF/MANAGER/
     ADMIN with a legacy organization) — highest-privilege role on collapse
     (ADMIN > MANAGER > STAFF, a documented derivation for legacy
     multi-role users), status mirrored from `UserStatus`, `created_at`
     inherited from the account; guarded by `WHERE NOT EXISTS` so a genuine
     duplicate fails the migration instead of being swallowed;
  3. replaces the composite FKs `fk_appointment_customer_organization`
     (V6) and `fk_entry_customer_organization` (V7) with plain
     `customer_id → app_user(id)` FKs — rows keep their own
     `organization_id`/`branch_id`, so historical transactions stay attached
     to the organization where they happened;
  4. makes `app_user.organization_id` **nullable** (column retained as the
     legacy/primary-organization anchor until `staff_assignment` can
     re-point at membership); existing customer rows keep their populated
     value — nulling them early would break the still-org-scoped principal
     built by `AuthService`.
- **Composite-FK decisions (audited, documented in V20 + database docs):**
  `staff_assignment`'s `(user_id, organization_id)` composite is **kept** —
  it now enforces that an assignment's user has that organization as their
  primary one (platform-level customers can never be staff-assigned); the
  two customer-leg composites are replaced as above.
- **New persistence package** `membership/`: `OrganizationMembership`
  entity, `MembershipRole` (STAFF/MANAGER/ADMIN — CUSTOMER is a
  platform-level role with no membership representation),
  `MembershipStatus` (ACTIVE/INACTIVE; only ACTIVE will grant operational
  access), `OrganizationMembershipRepository` (find by user+org, by
  user+status, by org+status, exists) — minimal by design, no management API.
- **JPA changes:** `User.organization` is now optional (nullable column, no
  `@NotNull` — Bean Validation runs on persist and must not reject the
  platform-customer shape); added the `User(email, displayName)`
  constructor for org-less accounts. All existing members/methods retained
  for compatibility. `StaffAssignment` is untouched and stays conceptually
  separate (branch-level ops vs organization membership).
- **demo-seed.sql** updated: explicit `organization_membership` rows for
  Sam (STAFF), Mira (MANAGER), Adi (ADMIN) + idempotent delete, mirroring
  what V20 derives for pre-V20 data.
- **New tests** (16, all green on real PostgreSQL/Testcontainers):
  `OrganizationMembershipPersistenceTests` (12 — create, duplicate
  rejection (entity + raw-SQL), string-persisted role/status,
  multi-organization membership for one user, inactive queries, repository
  resolution, customer with NULL organization persists as SQL NULL, missing-leg
  guards, staff_assignment still pins user+branch and rejects org-less
  customers, V20 applied/FK-shape assertions) and
  `OrganizationMembershipMigrationTests` (4 — pre-V20 constraints visible,
  backfill leaves customers unassigned, multi-role collapse to highest
  privilege, historical transactions preserved + platform customer
  bookable cross-organization).
- **Regression run (182 tests, all green):** the persistence-level suites
  this change could affect — Postgres/Flyway infrastructure,
  Organization↔Branch, User/Staff, Appointment booking, Queue persistence,
  Notification persistence, TenantContext/TenantIsolation, AuthService,
  RegistrationService, InvitationService, ServiceSession, plus the
  concurrency proofs (queue token/claim races, appointment double-booking
  race, session start/complete races) and queue-provisioning data
  semantics. Hibernate `validate` passed in every context boot (entity ↔
  V20 schema agreement).
- **Verification mechanics (environment reality):** the main source tree
  does **not** compile in this environment due to **pre-existing Phase 4O
  breakage** (missing `java.util.UUID` import in `ScheduleStatusService`,
  `QueueService.estimateJoin` referencing the never-created
  `ServiceCounter.ServiceIdPk` and a non-existent `QueueNotFoundException`
  2-arg constructor, `QueuePreJoinEstimateController` missing the
  `QueueJoinEstimate` import, `BranchController` importing a non-existent
  `ScheduleStatusResponse` from the wrong package and missing
  `io.swagger…Parameters` — all written after the last green build at
  2026-10-08 20:55, and Phase 4O's own status records "Maven unavailable in
  this environment — tests not executed locally"). So `mvnw.cmd test` cannot
  run until that is repaired (a separate phase's decision — not repaired
  here, per "do not silently fix unrelated failures"). Verification instead
  used the last good build's dependency set (the 2026-10-08 repackaged
  boot jar) plus a targeted compile of the reachable sources, executing the
  suites through the JUnit Platform launcher against Docker-hosted
  PostgreSQL 16 (Testcontainers `postgres:16-alpine`). One black-box HTTP
  test (`QueueProvisioningTests.restCreatedServiceIsImmediatelyListableAndJoinable`)
  cannot run in that harness because its endpoints' controllers
  transitively require the broken `QueueService` — the 404 it produces was
  proven to be a missing-controller artifact (compiling the service
  controller moves the 404 to the next uncompiled controller), not a
  regression. `mvnw.cmd test` should be run for the full suite once the
  Phase 4O breakage is repaired.
- **Known limitation carried:** `PLATFORM_ADMIN`, organization approval
  (`PENDING_APPROVAL`), and org-less customer registration are ADR-040
  targets from Phase 4R.1 and remain **unimplemented** — Phase 4R.4.
  Existing customers remain organization-scoped at the application layer
  (schema now permits the platform-customer shape; the application has not
  pivoted to it yet).

**Phase 4R.2.1 — Restore Backend Build & Regression Baseline (complete,
2026-10-09; build/regression repair only).**

The pre-existing Phase 4O compile breakage recorded in the Phase 4R.2 entry
is repaired; `.\mvnw.cmd clean compile`, `test`, and `package` all succeed on
the current tree. (The Phase 4R.2 entry's note that "mvnw.cmd test cannot
run" was accurate when written and is superseded by this phase.)

Root cause (all Phase 4O code written 2026-10-08 ~22:00, after the last green
build at 20:55, and never compiled — Phase 4O's own status records "Maven
unavailable in this environment — tests not executed locally"):

1. `ScheduleStatusService` — missing `import java.util.UUID;`.
2. `QueueService.estimateJoin` — referenced the never-created
   `ServiceCounter.ServiceIdPk`, a non-existent `ServiceCounter.Status` enum,
   and 2-arg constructors of `QueueNotFoundException` /
   `InvalidQueueRequestException` that do not exist; also missing
   `QueueJoinEstimate`/`QueueStatus` imports and the `ServiceRepository`
   dependency it needed.
3. `QueuePreJoinEstimateController` — missing `QueueJoinEstimate` import.
4. `BranchController` — imported `ScheduleStatusResponse` from the wrong
   package, missing `io.swagger…Parameters` import, and never wired the
   `ScheduleStatusService` it called.

Repairs (smallest possible; contracts preserved per the Phase 4O docs):
added the missing imports; rewired `BranchController`; re-pointed
`estimateJoin` at the existing `ServiceRepository` +
`InvalidRequestException(code, message)` (→ `400 QUEUE_INACTIVE` /
`400 SERVICE_INACTIVE`, exactly the documented codes) and the existing
`QueueNotFoundException` (→ `404 QUEUE_NOT_FOUND`) / `QueueBranchMismatchException`
(→ `422`) exceptions. Two genuine Phase 4O **logic** defects were also
exposed by finally running the suite and fixed to match the documented
contracts:

- `ScheduleStatusService.isWithinAnyInterval` had swapped operands
  (`!opens.isBefore(time)` = "opening is at/after now") — schedule status was
  inverted for most of the day; corrected to `!time.isBefore(opens)`
  ("now is at/after opening"), `[opens, closes)` semantics.
- `QueueWaitTimeEstimator.estimateJoin` passed `queueId` where
  `waitingIdsInRankOrder` expects a `branchId` — every pre-join estimate was
  silently zero; corrected to the queue's branch id (and dropped a redundant
  duplicate query in the same expression).

Two Phase 4O **test-file** compile defects were also repaired:
`QueuePreJoinEstimateTests` (missing `AuthenticatedPrincipal` field +
import; `Set.of("ROLE_CUSTOMER")` string → `Set.of(RoleType.CUSTOMER)`) and
`ScheduleStatusServiceTests` (same principal construction + missing
`RoleType` import).

Regression (full `.\mvnw.cmd test`, real Testcontainers PostgreSQL/Redis):
**730 tests, 6 failures, 6 errors, 0 skipped.** All 12 problem tests
classified with evidence:

- **7 proven pre-existing** (reproduce identically with the two logic repairs
  temporarily reverted — executed and confirmed; all are fixture/transaction
  defects in suites untouched by this phase, and all were recorded in the
  pre-4R.2 status): `AnalyticsServiceTests` ×3 (`uq_active_entry` fixture
  collisions, wrapped as one assertion failure), `AppointmentReminderTests`
  ×1 (`no_customer_double_booking` fixture collision),
  `CalendarManagementTests` ×2 (cascading aborted transaction + duplicate
  409), `NotificationApiTests` ×1 (transaction synchronization).
- **5 defective Phase 4O tests** (contracts satisfied — each contradicts the
  documented contract, a sibling test, or is impossible to set up):
  `ScheduleStatusServiceTests.sameInstantDifferentTimezoneProducesDifferentLocalTime`
  (expects Kolkata OPEN at 14:30Z = 20:00 IST — inverted),
  `ScheduleStatusApiTests.managerCanReadScheduleStatus` (asserts OPEN against
  the real clock — only passes 09:00–17:00 IST),
  `QueuePreJoinEstimateTests.inactiveQueueReturnsNullEstimate` (tries to join
  an already-inactive queue — impossible setup),
  `QueuePreJoinEstimateTests.estimateFormulaMatchesPerEntryEstimator`
  (expects a new joiner's 60-min estimate to equal the second entry's 30-min
  per-entry estimate — contradicts the documented model),
  `QueuePreJoinEstimateApiTests.missingQueueReturns404` (expects 404 for a
  cross-branch service that its own sibling test and the documented contract
  say is 422).

Concurrency suites all green: `AppointmentConcurrentBookingTests` 1/1,
`QueueConcurrentTests` 3/3, `ServiceSessionConcurrencyTests` 2/2,
`AppointmentReminderConcurrencyTests` 1/1, `QueueProvisioningTests` 7/7 —
the last includes `restCreatedServiceIsImmediatelyListableAndJoinable`,
which the Phase 4R.2 report could not run in its harness; it passes for real
here, confirming that report's "harness artifact" diagnosis.
Phase 4O/4O.1 suites: `ScheduleStatusServiceTests` 26 (25 pass), 
`ScheduleStatusApiTests` 9 (8 pass), `QueuePreJoinEstimateTests` 16
(14 pass + 1 error), `QueuePreJoinEstimateApiTests` 9 (8 pass) — note the
actual discovered counts are 28/10/18/10 = **66 `@Test` methods** across the
four files, not the 56/64 quoted in the Phase 4O.1 status entry (that entry's
per-file breakdown undercounts every file; the files themselves are the
authority).

Phase 4R.2 persistence suites: `OrganizationMembershipPersistenceTests` 12/12,
`OrganizationMembershipMigrationTests` 4/4 — V20 applies, membership
uniqueness, backfill, nullable customer organization, historical
transactions, and staff-assignment constraints all verified in the full run.

**Phase 4R.2.1 — Restore Backend Build & Regression Baseline (complete,
2026-10-09; build/regression repair only).** [Historical entry — the full
details below it stand.] **Phase 4R.3 (this phase) changes behavior on top
of that baseline; see the 4R.3 entry for results.**

**Phase 4R.3 — Platform-Level Customer Identity, Registration UX &
Membership-Based Authentication (complete, 2026-10-09; ADR-040).**

The registration UI no longer asks for an organization, and the identity
model matches the decision: customers are platform-level users,
operational roles are memberships.

### Registration (frontend + backend)

- `RegisterPage` has no organization selector, dropdown, list, preselection,
  or organization validation: name + email + password + confirm, loading/
  error/success states unchanged in the design system. Customer copy now
  reads "Create your SmartQueue account".
- `POST /api/v1/auth/register` takes `{displayName, email, password}` —
  no `organizationId`; the account is created org-less (CUSTOMER, NULL
  organization, verification token, same duplicate/policy behavior).
  `RegistrationResponse.organizationId` is null for customers. Extra JSON
  fields are ignored, never trusted — self-provisioning privileged roles
  or memberships through the public endpoint is impossible by contract.
- Booking and queue-join pages choose the organization per action from the
  public ACTIVE directory (org → branch → service/date/queue), exactly the
  ADR-040 "request context, never membership" behavior. The booking/queue
  request contracts are byte-identical; no membership is created by
  selecting an organization.

### Identity and authentication

- `AuthenticatedPrincipal.organizationId` is nullable (`hasOrganization()`,
  `isOperational()` helpers). JWT `org` claim is optional: omitted for
  customers, present for operational users; validation accepts both.
  `LoginResponse.organizationId` is null for customers.
- `AuthService.login` resolves the operational organization from ACTIVE
  memberships (legacy home organization wins ties; exactly one membership
  resolves; otherwise null). Customers always resolve null — a legacy
  organization value never authorizes.
- `TenantContextFilter` installs the request context only for principals
  with a resolved organization; customers pass through (background paths
  unchanged). The fail-closed holder still protects every operational path.
- `TenantAccessPolicy` is membership-grounded: `requireOrganizationAccess`
  verifies an ACTIVE membership row; `requireOrganizationManagement`
  requires roles + ACTIVE membership at the resolved org; operational
  access with no membership, inactive membership, or cross-organization
  naming is denied. Queue-topic authorization admits platform-level
  customers only to ACTIVE queues (token-only payloads); INACTIVE queues
  and all non-customer roles without scope are denied.
- Membership provisioning: `registerBusiness` (owner ADMIN),
  `InvitationService.accept` (server-side role), and
  `UserManagementService` create/update (role-sync, incl. INACTIVE on
  de-roleing) all create membership rows. Public registration, password
  recovery, and OAuth *linking* create none.

### Customer operations (resource-derived, with the documented bridge)

- Appointment book/list/read/cancel, availability, schedule-status, queue
  join/list/read/cancel/estimate, branch/service/queue display reads, and
  notification reads all derive the organization from the target resource
  (ACTIVE branch/queue/service/appointment/entry checks) with
  owner-scoped customer reads. Authorization matrices are unchanged for
  operational roles (commands stay 403-denying, reads stay scope-safe
  404); customers booking/joining/reading at any ACTIVE organization is
  the intended new behavior (previously 404).
- The compat bridge: `QueueService.inResourceScope` and the
  estimate path install the already-authorized row's organization around
  the fail-closed-holder engine calls (lifecycle, estimator), with
  save/restore, for customers only. It cannot widen access (authorization
  happened against the same row). Full proof: one customer books and joins
  at two organizations, reads both entries with positions, cancels both
  (`CustomerPlatformIdentityTests`).
- OAuth provisioning keeps its current organization-scoped account
  creation; the linked principal follows the same rule (customers → null).

### Tests

- Backend: **745 run, 5 failures, 6 errors** (`.\mvnw.cmd test`). All 11
  are the 4R.2.1-classified set, unchanged: 7 pre-existing fixture/
  transaction defects (analytics ×3, reminder ×1, calendar ×2,
  notification ×1) + 4 defective Phase 4O tests (estimate-agreement,
  inactive-queue-setup, wall-clock schedule assertion, inverted timezone
  assertion).
- New/rewritten coverage: `CustomerPlatformIdentityTests` (7 — cross-org
  booking/joining/listing, legacy-org non-authorization, cross-customer
  denial, no/inactive-membership denial, tampering denial), updated
  `RegistrationServiceTests` (org-less registration, no-mint test),
  `AuthServiceTests` (+null-org customer login, legacy non-authorization,
  inactive-membership login), `JwtServiceTests` (+customer token omits org),
  `TenantAccessPolicyTests` (membership grounding, customer queue display,
  tampering), `InvitationServiceTests` (acceptance creates membership),
  fixture helpers create memberships mirroring the V20 backfill, API suites
  updated to the new cross-org contracts. `OAuthServiceTests` reflects
  platform principals for linked/provisioned customers.
- Frontend: **285/285 green** (`npx vitest run`); production build green
  (`npm run build`: `tsc` + Vite). Registration tests assert no selector
  and no `organizationId` in flight; booking/join tests drive the
  discovery-first flow; a new token test covers org-less JWTs.

### Known remaining work (later phases)

- Organization approval (`PENDING_APPROVAL` + `PLATFORM_ADMIN` review) —
  business registration still activates immediately (documented gap).
- Multi-membership staff organization selection (operational users with
  several ACTIVE memberships currently resolve to one).
- OAuth *provisioning* still requires an organization for new users (the
  linked principal is already platform-consistent).

## Completed

- [x] Spring Initializr skeleton present (verified, not rewritten):
  `com.sourabh:smart-queue-platform:0.0.1-SNAPSHOT`, Java 21,
  `SmartQueuePlatformApplication` + `contextLoads` test.
- [x] `AGENTS.md` — permanent engineering rules created.
- [x] `docs/` structure created (relocated to `backend/docs/` in Phase 4T; paths below are backend-relative)
  (`architecture/`, `decisions/`, `api/`, `database/`, `development/`).
- [x] `architecture/system-architecture.md` — intended modular-monolith
  architecture documented (marked Planned where not yet implemented).
- [x] `decisions/ADR-001-modular-monolith.md` created.
- [x] `README.md` updated (purpose, actual stack, status, no fictional claims).
- [x] Boot version decision resolved: Spring Boot 4.1.1 + Java 21 adopted
  (ADR-002). `AGENTS.md`, `README.md`, and architecture docs aligned.
- [x] Phase 1 infrastructure (2026-10-05, verified — see Current Reality):
  - [x] PostgreSQL configured via `DB_*` env vars (`application.yml`);
    Hibernate `ddl-auto: validate` (Flyway owns the schema).
  - [x] Flyway 12.4.0 (Boot-managed) + `flyway-database-postgresql`;
    `db/migration/V1__baseline.sql` establishes the migration pipeline
    (no domain tables — by design).
  - [x] `compose.yaml` local PostgreSQL 16-alpine (named volume,
    healthcheck); verified `up -d` + app boot + `/actuator/health` UP.
  - [x] Testcontainers 2.0.5 strategy (`@ServiceConnection`, no H2);
    `PostgresInfrastructureTests` (4 tests) proves context + connectivity +
    Flyway V1 + JPA bootstrap. `contextLoads` passes again.
  - [x] Actuator added for the health endpoint only (health exposed,
    details never shown).
  - [x] `.env.example` + `.gitignore` (`.env`) — no secrets committed.
  - [x] ADR-003 (PostgreSQL), ADR-004 (Flyway), ADR-005 (Testcontainers).
- [x] Phase 2A domain slice (2026-10-05, verified — see Current Reality):
  - [x] `common/BaseAuditableEntity`: app-assigned UUID ids + `Instant`
    timestamps via lifecycle callbacks (ADR-006). No `@Version` yet —
    no concurrent transition exists; adds trivially later.
  - [x] `organization/`: `Organization` (name NOT globally unique),
    `OrganizationStatus` (`ACTIVE`/`INACTIVE`, string-persisted),
    bare `OrganizationRepository`.
  - [x] `branch/`: `Branch` with unidirectional lazy `ManyToOne` to
    Organization (no cascade, explicit FK), flat address columns
    (name/city/country required), `BranchStatus`, bare `BranchRepository`.
  - [x] `V2__create_organization_and_branch.sql`: PKs, FK, NOT NULLs,
    `uq_branch_organization_name` (doubles as org lookup index — no
    duplicated index). No other indexes; none justified.
  - [x] No services / APIs / DTOs this phase (no domain ops beyond
    persistence; CRUD API out of scope — ADR-007).
  - [x] `OrganizationBranchPersistenceTests` (10 tests): persist, defaults,
    non-unique org names, Branch→Org linkage, null-org rejection, DB-level
    FK enforcement, composite uniqueness in/across orgs, timestamp update,
    string enums, V2 applied. Full suite 15/15 green.
  - [x] Testcontainers lesson: one container per test class (a shared static
    container's lifecycle races between subclasses — found and fixed).
  - [x] ADR-006 (identity/timestamps), ADR-007 (ownership/mapping).
- [x] Phase 2B domain slice (2026-10-05, verified — see Current Reality):
  - [x] `service/`: `Service` (branch-owned, per-branch unique name,
    `description`, `standard_duration_minutes` + `Duration` accessor,
    `ServiceStatus`), bare `ServiceRepository`.
  - [x] `counter/`: `ServiceCounter` (branch-owned, per-branch unique name,
    `CounterStatus` incl. `MAINTENANCE`, no runtime states),
    `CounterCapability` (branch-pinned link), `ServiceCounterRepository`
    (bare) + `CounterCapabilityRepository` (`findByService`/`findByCounter`
    for the relationship tests + future queue reads).
  - [x] Cross-branch integrity in the DB: composite FKs
    `(service_id, branch_id)` / `(counter_id, branch_id)` — raw-SQL
    cross-branch insert proven rejected; constructor guard fails fast.
  - [x] `V3__create_service_and_counter.sql`: 3 tables, PKs, FKs (incl.
    composites), per-branch uniques, `uq_capability_service_counter`,
    `idx_capability_counter`. Duration = explicit INTEGER minutes
    (Hibernate maps `Duration` to opaque numeric, rejects `INTERVAL`).
  - [x] No services layer / APIs / DTOs (same rule as 2A — ADR-008).
  - [x] `ServiceCounterPersistenceTests` (15 tests). Full suite 30/30 green.
    Local-dev lesson: resetting the Compose volume (`down -v`) is the
    documented recovery when an unshared migration file changes mid-dev.
  - [x] ADR-008 (capability model + all representation decisions).
- [x] Phase 2C domain slice (2026-10-05, verified — see Current Reality):
  - [x] `user/`: `User` (org-owned, globally unique normalized email,
    `UserStatus`), `RoleType` grants via `user_role` join
    (`@ElementCollection`, no Role entity — multi-role from day one),
    bare `UserRepository`. No password/auth fields.
  - [x] `staff/`: `StaffAssignment` (org + user + branch, `ACTIVE`/`INACTIVE`,
    no runtime states), bare `StaffAssignmentRepository`.
  - [x] Cross-org integrity in the DB: composite FKs
    `(user_id, organization_id)` / `(branch_id, organization_id)` —
    raw-SQL cross-org insert proven rejected; constructor guard fails fast.
  - [x] `V4__create_users_roles_and_staff_assignments.sql`: `app_user`
    (`USER` reserved), `user_role` (PK = uniqueness), `staff_assignment`
    + `uq_staff_user_branch`, `idx_user_organization`, `idx_staff_branch`.
    V1–V3 untouched.
  - [x] No services layer / APIs / DTOs / Security / JWT (same rule — ADR-009).
  - [x] `UserStaffPersistenceTests` (16 tests). Full suite 46/46 green.
  - [x] ADR-009 (identity, role model, staff integrity).
- [x] Phase 2D calendar slice (2026-10-05, verified — see Current Reality):
  - [x] `workinghour/`: `WorkingHour` (branch, `DayOfWeek` via converter,
    minute-granular `LocalTime` bounds, immutable intervals — no setters),
    bare `WorkingHourRepository`.
  - [x] `holiday/`: `Holiday` (branch, `LocalDate`, name; full-day closures
    only), bare `HolidayRepository`.
  - [x] Range validity in two layers (constructor guard + `CHECK`); overlap
    prevention ONLY in the DB (exclusion + `btree_gist`, verified empirically
    pre-adoption: overlaps/duplicates/containment rejected, adjacency and
    cross-day rows accepted). No `UNIQUE(branch, day)` (would forbid splits);
    `idx_working_hour_branch_day` for future reads.
  - [x] Absent weekday rows mean closed (no flags/CLOSED rows); timezone
    interpretation explicitly deferred to appointments (ADR-010).
  - [x] `V5__create_working_hours_and_holidays.sql`. V1–V4 untouched.
  - [x] No services layer / APIs / DTOs / availability logic (same rule).
  - [x] `WorkingCalendarPersistenceTests` (18 tests). Full suite 64/64 green.
    Test lesson: `JdbcTemplate` binds TIME via `LocalTime` args (strings
    mistype as VARCHAR); `save()` without flush is invisible to JDBC reads.
  - [x] ADR-010 (calendar decisions).
- [x] Phase 2E booking foundation (2026-10-06, verified — see Current Reality):
  - [x] `branch.time_zone` (required IANA `ZoneId`, VARCHAR; Phase 2D's
    deferred decision resolved — silent UTC would book wrong local times).
  - [x] `appointment/`: `Appointment` (org/branch/service/customer,
    `TIMESTAMPTZ` `[start, end)`, snapshotted end, 5 statuses with guarded
    one-way transitions), bare `AppointmentRepository`.
  - [x] `AppointmentBookingService` (first application logic): validate →
    derive end from service duration → holiday check → working-hour fit in
    branch zone → persist; conflicts translate to
    `AppointmentConflictException` (4 small booking exceptions total).
  - [x] Temporal protection: partial exclusions per service + per customer
    (`SCHEDULED`/`CONFIRMED` only; capacity-1 assumption documented);
    ownership composites (service,branch)/(customer,org)/(branch,org);
    `CHECK(end>start)`; `idx_appointment_branch_time`.
  - [x] Mandatory race test: 2 threads, same slot → exactly 1 success +
    1 conflict + 1 row. Full suite 97/97 green.
  - [x] No REST/DTOs (service tested directly), no rescheduling/listing.
  - [x] ADR-011 (time model, conflict protection, capacity, calendar use).
- [x] Phase 2F queue engine (2026-10-06, verified — see Current Reality):
  - [x] `queue/`: `Queue` (one row per branch+service, ACTIVE/INACTIVE),
    `QueueEntry` (walk-ins: token, branch-date, priority, guarded lifecycle),
    `QueuePriority` (strict EMERGENCY>PRIORITY>NORMAL, FIFO, UUID tie-break —
    no aging by decision), `QueueTokenCounter` (daily sequence),
    `QueueJoinService`/`QueueCallService`/`QueueEntryLifecycleService`,
    `QueueEntryClaimRepository` (ordered `FOR UPDATE SKIP LOCKED` claim),
    7 small exceptions, focused repositories. No REST/DTOs.
  - [x] Tokens: per (queue, branch-date) via locked counter row
    (`INSERT … ON CONFLICT DO NOTHING` + `SELECT FOR UPDATE`) — never
    MAX+1, never JVM locks. 8-thread race → exactly tokens 1–8.
  - [x] Atomic claims: one ordered claim query across all servable queues;
    2-counter (same-counter race) + 3-counter races claim exact entry sets.
  - [x] `V7__create_queue_and_queue_entries.sql`: 3 tables, ownership
    composites, partial active-entry + token uniques, waiting index. V1–V6
    untouched.
  - [x] Design correction: first-attempt REQUIRES_NEW allocator removed —
    isolated insert can't see uncommitted rows and post-failure sessions are
    unusable; ON CONFLICT design has neither flaw (ADR-012).
  - [x] `QueuePersistenceTests` (30) + `QueueConcurrentTests` (3).
    Full suite 130/130 green.
  - [x] ADR-012 (cardinality, tokens, claims, lifecycle, deferrals).
- [x] Phase 2G sessions (2026-10-06, verified — see Current Reality):
  - [x] `queue/ServiceSession` (entry + counter + service + branch,
    `startedAt` + nullable `endedAt`, no status/duration columns),
    `ServiceSessionRepository` (entry lookup only), project `Clock` bean
    (fixed in tests for exact timestamps).
  - [x] Branch-pinned composites (counter, service legs); entry↔session
    *service* equality application-enforced by decision (documented +
    proven by a test asserting the DB's permissiveness there).
  - [x] Lifecycle service reworked (not forked): row-locked atomic
    start/complete; `UNIQUE(queue_entry_id)` backstop; no `@Version`
    (locks order the only writers).
  - [x] `V8__create_service_session.sql`. V1–V7 untouched.
  - [x] `ServiceSessionTests` (17) + `ServiceSessionConcurrencyTests` (2:
    concurrent starts → 1 session; concurrent completions → 1 end).
    Full suite 149/149 green.
  - [x] ADR-013 (separation, one-session model, atomicity, Clock, deferrals).
- [x] Phase 2H aging (2026-10-06, verified — see Current Reality):
  - [x] Effective rank in the claim SQL: EMERGENCY-first, then base weight
    (PRIORITY=1) + `min(floor(waitMin/15), 3)`, then joinedAt, then id
    (`queue.aging.*`: enabled/interval-minutes/max-promotions, validated,
    restart to change; invalid values fail fast — verified).
  - [x] Clock reused (epoch seconds into the query); persisted priority
    never mutates; disabled mode reproduces Phase 2F order exactly.
  - [x] Guarantee as implemented: aged NORMAL precedes newer NORMALs and
    out-waited PRIORITYs; only continuous EMERGENCY arrivals can still
    starve (documented, not oversold).
  - [x] No migration (nothing stored), no new index (waiting-entry index
    still drives access), no new packages/dependencies.
  - [x] `QueueAgingTests` (9) + `QueueAgingDisabledTests` (2, separate
    context). Full suite 160/160 green, incl. all Phase 2F ordering and
    race tests unchanged.
  - [x] ADR-014 (policy, ceiling, cap, SQL strategy, alternatives).
- [x] Phase 2I estimation (2026-10-06, verified — see Current Reality):
  - [x] `WaitTimeEstimate` record (entry, whole minutes, start, calculated
    time, ahead/active/counters basis — no false precision).
  - [x] `QueueWaitTimeEstimator`: position `floor(ahead+active / capable) ×
    duration`; CALLED/SERVING → 0; terminal/inactive-queue/zero-capacity →
    empty (never silent zero).
  - [x] Single ordering definition (`QueueRankingSql` shared by claim +
    estimator; claim refactored onto it with zero behavior change — full
    2F/2H ordering suite green).
  - [x] Active sessions count one full unit (documented approximation);
    cross-queue contention absorbed by the shared divisor (documented).
  - [x] Clock-driven (epoch seconds into SQL); no migration, no new index
    (evaluated), no new packages/dependencies.
  - [x] `QueueWaitTimeEstimatorTests` (17, fixed Clock, no sleeps).
    Full suite 177/177 green.
  - [x] Design correction: row-value tuple comparison with UUID bind params
    silently mis-evaluates — replaced by ordered-ids + Java position
    (ADR-015).
  - [x] ADR-015 (definition, model, reuse, capacity, rounding, Clock,
    consistency, alternatives, limitations).
- [x] Phase 3A events (2026-10-06, verified — see Current Reality):
  - [x] Mechanism: Spring `ApplicationEventPublisher` + records;
    `@TransactionalEventListener(AFTER_COMMIT)` delivery (rollback →
    never delivered, proven). No custom bus, no new dependencies.
  - [x] 9 facts: `queue/event/` (Joined/Called/ServingStarted/Completed/
    Cancelled/NoShow, SessionStarted/SessionCompleted) +
    `appointment/event/` (AppointmentBooked). IDs-only payloads, app UUIDs,
    Clock stamps; no entities in events.
  - [x] Published from join/call/lifecycle/booking services after state is
    established. No AppointmentCancelled (no service flow performs it).
  - [x] Test-only AFTER_COMMIT collector is the sole consumer; production
    adds none. No outbox (no external consumer to protect), no async,
    no config, no migration (V1–V8 untouched).
  - [x] `DomainEventPublicationTests` (9). Full suite 186/186 green.
  - [x] ADR-016 (model, ownership, IDs, tx semantics, deferrals).
- [x] Phase 3B Redis (2026-10-06, verified — see Current Reality):
  - [x] `spring-boot-starter-data-redis` (Boot-managed Lettuce, single
    client); `redis:7.4-alpine` in Compose (no volume — disposable by
    design) and Testcontainers (`GenericContainer`, no Redis module in the
    BOM) via `@ServiceConnection` — zero manual wiring.
  - [x] `REDIS_HOST`/`REDIS_PORT` env config (localhost/6379 defaults; no
    local password by decision); `.env.example` placeholders only.
  - [x] `infrastructure/redis/`: `RedisKeyConvention`
    (`sq:o:{org}:b:{branch}:{domain}:{id}`, null-scope rejected) +
    `RedisJson` (Boot Jackson-3 `ObjectMapper`, strings on the wire, no
    entities ever). Auto-configured `StringRedisTemplate` used as-is.
  - [x] TTL philosophy (explicit per-write, freshness-not-correctness),
    proven without sleeps via expiry assertion; health semantics verified
    live (main DOWN with Redis stopped, liveness + readiness stay UP;
    startup never requires Redis).
  - [x] `RedisInfrastructureTests` (7, incl. JSON probe round-trip).
    Full suite 193/193 green. No migration (V1–V8 untouched).
  - [x] ADR-017 (role, serialization, keys, TTL, health, failure mode).
- [x] Phase 3C real-time (2026-10-06, verified — see Current Reality):
  - [x] `queue/realtime/`: `QueueRealtimeState` + `QueueEntryView`
    (tokens/priority/status/waits only — no customer UUIDs/emails),
    `QueueRealtimeProjectionService` (PG → snapshot → Redis, 5-min TTL),
    `QueueRealtimeEventBridge` (8 queue/session events → refresh + topic,
    failures caught/logged, never propagated), `QueueRealtimeController`
    (`/app/…/snapshot` → rebuild + publish), `QueueRealtimeDestinations`
    (tenant-scoped topic + request paths).
  - [x] `infrastructure/websocket/`: STOMP config (`/ws`, no SockJS, simple
    broker, open origins by documented decision); TEXT JSON with
    `application/json` content type.
  - [x] `QueueRealtimeProjectionTests` (10) + `QueueRealtimeWebSocketTests`
    (4, real STOMP client: snapshot request, join broadcast, tenant
    isolation, payload privacy) + `QueueRealtimeFailureTests` (2, Redis
    container stopped mid-test: queue transactions still commit, only the
    projection fails). Full suite 209/209 green. No migration
    (V1–V8 untouched); one dependency (`spring-boot-starter-websocket`).
  - [x] Bridge hardened so even the session→queue lookup sits inside the
    failure guard (nothing from AFTER_COMMIT can reach the caller).
  - [x] ADR-018 (snapshot model, projection, destinations, deferrals).
- [x] Phase 3D live refresh (2026-10-06, verified — see Current Reality):
  - [x] Freshness bound: every queue with live entries rebuilt at least
    once per `queue.realtime.refresh.interval` (default `PT1M`, ISO-8601;
    `enabled` flag; zero/negative/unparseable fail fast via `@AssertTrue`,
    restart to change). Rationale documented: ~15 rebuilds per 15-min
    aging step, 1 min < 5-min TTL (about five renewals per TTL window).
  - [x] `queue/realtime/`: `QueueRealtimeRefreshProperties`,
    `QueueRealtimeRefreshService` (discover → rebuild → guarded store →
    conditional publish; fixed-delay `@Scheduled`, orchestration only, no
    business mutation), `QueueRealtimeSchedulingConfiguration`
    (`@EnableScheduling`, no custom threads/frameworks).
  - [x] Discovery: `QueueRepository.findIdsRequiringRealtimeRefresh`
    (ACTIVE + WAITING/CALLED/SERVING, ids only); empty/inactive queues
    excluded, their keys expire untouched.
  - [x] Same builder as events (`buildSnapshot` over shared rank +
    estimator; persisted priority never mutated); same payload/topic
    contract. Unchanged content renews TTL quietly
    (`sameLiveContent` ignores `generatedAt`, order-sensitive).
  - [x] Stale-write protection: pre-read raw value + atomic Lua
    compare-and-set; lost races rebuild once from current state instead
    of regressing (proven by an explicit CAS test). Two instances may
    duplicate work (documented, accepted — no locks/ShedLock/Quartz/
    outbox/Kafka/Streams).
  - [x] Best-effort: Redis/broker/PG failures log + skip per queue;
    scheduler never throws; app boots/serves with Redis down (unchanged
    3B contract); broker failure still leaves Redis fresh.
  - [x] `QueueRealtimeRefreshTests` (14: current snapshot, quiet renewal,
    no-threshold stability, threshold flip + wait change + priority
    immutability, cap, emergency, zero capacity, multi-counter sharing,
    called/serving + session wait, terminal exclusion, discovery,
    event→scheduler non-regression, stale CAS, broker failure) +
    `QueueRealtimeRefreshSchedulingTests` (3, incl. live-tick wire proof
    at 100 ms + empty-queue silence) + `QueueRealtimeRefreshDisabledTests`
    (2: no bean when disabled) + `QueueRealtimeRefreshPropertiesTests`
    (5: defaults/valid/zero/negative/bogus). Full suite 233/233 green.
    No migration (V1–V8 untouched); no new dependencies (scheduling ships
    with Spring).
  - [x] ADR-020 (freshness, interval, discovery, TTL, comparison, guard,
    scheduler, failures, multi-instance, alternatives, limitations).
- [x] Phase 3E tenant context (2026-10-06, verified — see Current Reality):
  - [x] `tenant/`: immutable `TenantContext` (organization required,
    branch optional; no User/roles/JWT/HTTP/entities), fail-closed
    `TenantContextHolder` (private ThreadLocal, set/get/getOrNull/clear/
    restore), `TenantContextRequiredException`. Context ≠ authentication
    ≠ authorization ≠ role.
  - [x] Scope-aware repositories: `findByIdAndOrganizationId` (Queue,
    QueueEntry, Appointment), `findByIdAndBranchId` (Service,
    ServiceCounter) — out-of-scope ids yield empty (scope-safe not-found).
  - [x] Enforcement in call/lifecycle/estimate/projection paths; bridge,
    controller, and scheduler install explicit scope (event payload, path
    variables, per-queue DB lookup) with save/restore semantics — a nested
    dispatch can no longer clear its caller's context (found by failing
    tests, fixed in phase).
  - [x] `TenantContextTests` (11: lifecycle, fail-closed, branch semantics)
    + `TenantIsolationTests` (16: cross-org/cross-branch denial, missing
    context, organization-level scope) + `TenantBackgroundTests` (3:
    scheduler/event paths without HTTP context, multi-tenant sweep).
    Full suite 263/263 green. No migration (V1–V8 untouched); no new
    dependencies; no Security/JWT/REST.
  - [x] One honest expectation update: the cross-branch counter test now
    asserts scope rejection instead of empty-claim.
  - [x] ADR-019 (scope model, context, holder, repository isolation,
    fail-closed, background/event/WebSocket/Redis boundaries, alternatives,
    limitations).
- [x] Phase 3F auth foundation (2026-10-06, verified — see Current Reality):
  - [x] `auth/`: `UserCredential` + repository (BCrypt hash only, unique per
    user), `AuthProperties` (`security.jwt.*`: required ≥32-byte secret,
    1h expiration; fail fast), `AuthenticatedPrincipal` (no entities),
    `JwtService` (Nimbus HS256 issue/validate on the project Clock),
    `PasswordCredentialService` (provision/verify), `AuthService`
    (generic-failure login with dummy-hash timing parity), `AuthController`
    (`POST /api/v1/auth/login`, DTOs only), narrow exception mapping
    (401 generic / 400 malformed), `SecurityConfig` (public login/handshake/
    health/error, everything else authenticated; stateless; CSRF disabled
    for header auth with justification; method security enabled as
    capability), JWT + tenant filters (org-level context, save/restore),
    `TenantAccessPolicy` (org, then branch ownership, then
    manager/admin-wide, staff-by-assignment, customer display rule).
  - [x] WebSocket auth: handshake interceptor (`?token=`, anonymous rejected),
    session principal binding (CONNECT headers provably do not reach the
    session — verified against Spring bytecode), channel interceptor for
    SUBSCRIBE/SEND scope, controller principal re-check outside the
    log-only block. Denial terminates the session.
  - [x] `AuthServiceTests` (8) + `JwtServiceTests` (9, pure units) +
    `AuthControllerTests` (5, real HTTP incl. chain posture) +
    `TenantAccessPolicyTests` (13) + `SecurityFilterTests` (6 units) +
    `AuthPropertiesTests` (5, fail-fast) + `QueueRealtimeWebSocketSecurityTests`
    (6, live sockets). Full suite 315/315 green. V9 migration; two deps
    (`spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`
    for Boot-managed Nimbus); no refresh tokens/lockout/rate-limit/audit.
  - [x] Bootstrap lessons (documented, not hidden): same-name test YAML
    shadows main YAML in this Boot line (test secret lives in
    `application.properties` instead); `@AutoConfigureMockMvc` with
    `@ServiceConnection` containers breaks bootstrap (black-box HTTP
    tests instead); detached role grants need explicit re-save outside TX.
  - [x] ADR-021 (mechanism, credentials, JWT, tenant integration, branch
    access, WebSocket, background, limitations, alternatives).

## Current Reality (verified 2026-10-06)

### Actual stack (from `pom.xml`)

| Concern | Actual | Target | Status |
|---|---|---|---|
| Java | 21 (`java.version=21`) | Java 21 | ✅ match |
| Spring Boot | **4.1.1** (`spring-boot-starter-parent:4.1.1`) | 4.1.1 (ADR-002) | ✅ decided |
| Spring Web | `spring-boot-starter-webmvc` (+ `webmvc-test`) present | Spring Web | ✅ present |
| Spring Data JPA / Hibernate | `spring-boot-starter-data-jpa` (+ `data-jpa-test`) present | Same | ✅ present |
| Bean Validation | `spring-boot-starter-validation` (+ `validation-test`) present | Same | ✅ present |
| PostgreSQL driver | `postgresql` (runtime, Boot-managed) + Compose PG 16 | PostgreSQL (ADR-003) | ✅ configured |
| Spring Security / JWT | `spring-boot-starter-security` + `oauth2-resource-server` (Nimbus HS256), login API, bearer filter chain, socket auth | Present | ✅ present (3F) |
| Flyway | 12.4.0 + `flyway-database-postgresql`; V1–V9 (schema v9) | Flyway (ADR-004) | ✅ configured |
| Redis | 7.4 (`redis:7.4-alpine`, Boot-managed Lettuce, JSON strings) + live queue snapshots via STOMP | Derived only (ADR-017/018) | ✅ configured |
| WebSocket / STOMP | `spring-boot-starter-websocket`, endpoint `/ws`, simple broker | Tenant-scoped queue topics (ADR-018) | ✅ present (no auth yet) |
| Testcontainers | 2.0.5 via Boot-managed BOM; `@ServiceConnection` PG tests | Testcontainers (ADR-005) | ✅ configured |
| Mockito | via Boot test starters (self-attach warning on JDK 21 noted, harmless) | Same | ✅ present |
| Docker / Compose | `compose.yaml` PostgreSQL (Phase 1 scope only) | Expanded later | ✅ present |
| Actuator | health endpoint only (`show-details: never`) | Minimal | ✅ present |
| OpenAPI / Swagger | `springdoc-openapi-starter-webmvc-ui:3.1.1` (3.x ↔ Boot 4); `/v3/api-docs` + Swagger UI, `bearerAuth` scheme | Present (3G) | ✅ present |
| Frontend | React 18 + TS strict + Vite 6 + Router 6 (`frontend/`, 3N shell + 4A customer) | Auth shell + customer workflows (ADR-028/029) | ✅ present |

> Spring Boot 4.x test starters (`-test` artifacts such as
> `spring-boot-starter-webmvc-test`) reflect the Boot 4.1 line adopted in
> ADR-002. `pom.xml` is authoritative for the version.

### Code

- `SmartQueuePlatformApplication` — entry point. Still no controllers,
  DTOs, or business config (correct for this phase).
- `common/BaseAuditableEntity` — UUID id + `Instant` created/updated
  (lifecycle-managed); the convention all future entities extend.
- `organization/Organization` (+ `OrganizationStatus`, `OrganizationRepository`).
- `branch/Branch` (+ `BranchStatus`, `BranchRepository`) — unidirectional
  lazy `Branch → Organization`, FK `fk_branch_organization`.
- `service/Service` (+ `ServiceStatus`, `ServiceRepository`) — branch-owned,
  per-branch unique name, minutes duration.
- `counter/ServiceCounter` (+ `CounterStatus`, `ServiceCounterRepository`),
  `counter/CounterCapability` (+ `CounterCapabilityRepository`) — branch-pinned
  links, composite-FK cross-branch protection.
- `user/User` (+ `UserStatus`, `RoleType` grants, `UserRepository`) —
  org-owned, globally unique email, no auth fields.
- `staff/StaffAssignment` (+ `StaffAssignmentStatus`,
  `StaffAssignmentRepository`) — org-pinned user/branch link,
  composite-FK cross-org protection.
- `workinghour/WorkingHour` (+ `DayOfWeekConverter`, `WorkingHourRepository`)
  — branch-owned intervals, minute-granular wall-clock bounds.
- `holiday/Holiday` (+ `HolidayRepository`) — branch-owned full-day closures.
- `appointment/Appointment` (+ `AppointmentStatus`, guarded transitions,
  `AppointmentBookingService`, 4 booking exceptions, `AppointmentRepository`).
- `branch.time_zone` (required IANA zone; existing constructor extended).
- `queue/Queue`, `queue/QueueEntry` (+ statuses, priority, token counter,
  join/call/lifecycle services, claim repo, exceptions).
- `queue/` (+ `ServiceSession`, `ServiceSessionRepository`, lifecycle
  service now coordinating entry + session, project `Clock`,
  `QueueAgingProperties` + claim ranking, `WaitTimeEstimate`,
  `QueueWaitTimeEstimator`, `QueueWaitTimeQueries`, `QueueRankingSql`,
  `event/` records published by the services).
- `appointment/event/` — `AppointmentBooked`, published by booking.
- `infrastructure/redis/` — `RedisKeyConvention` + `RedisJson`
  (auto-configured `StringRedisTemplate`).
- `queue/realtime/` — state + views, projection service (build/store/
  guarded compare-and-set), event bridge, scheduled freshness service
  (`queue.realtime.refresh.*`: enabled + `PT1M` interval), STOMP
  controller, destination convention.
  `infrastructure/websocket/` — STOMP config (`/ws`, simple broker).
- `tenant/` — `TenantContext` (org + optional branch, immutable),
  `TenantContextHolder` (fail-closed ThreadLocal with save/restore),
  `TenantContextRequiredException`. No Security/JWT/REST.
- `auth/` — `UserCredential`, `AuthProperties`, `AuthenticatedPrincipal`,
  `JwtService`, `PasswordCredentialService`, `AuthService`,
  `AuthController` (login, OpenAPI-tagged) + shared error envelope,
  `SecurityConfig`, JWT + tenant filters, `TenantAccessPolicy`, STOMP handshake/channel auth.
- `common/api/` — `ApiError` envelope, scope-safe `ResourceNotFoundException`,
  `GlobalRestExceptionHandler` (replaces the narrow Phase 3F auth handler;
  login statuses/messages preserved inside the shared envelope).
- `organization/api/` + `organization/application/` — `OrganizationResponse`
  DTO (explicit manual mapping), `OrganizationService` (own-org read,
  scope-safe 404), thin `OrganizationController`.
- `branch/api/` + `branch/application/` — `BranchResponse` DTO,
  `BranchService` (same-org scope + `TenantAccessPolicy` branch rule),
  thin `BranchController`.
- `infrastructure/openapi/` — `OpenApiConfig` (title/description/version +
  `bearerAuth` JWT scheme).
- `db/migration/`: V1–V8, all asserted applied by tests (no V9 — neither
  events, Redis, nor WebSocket stores relational state).
- Package-by-feature unchanged (infra lives outside features); future
  modules stay unscaffolded until their phase.

### Tests (verified 2026-10-08 — backend 546 total (545 prior + 1 JWT expiry-boundary unit, executed here green); frontend 164 run, 0 failures)

- `SmartQueuePlatformApplicationTests#contextLoads` — passes.
- `PostgresInfrastructureTests` (4 tests): context loads, `SELECT 1`,
  Flyway history contains applied V1, JPA maps Organization + Branch.
  Own container per class (shared-static lifecycle races — see test note).
- `OrganizationBranchPersistenceTests` (10 tests, `@Transactional`
  rollback): org persist + defaults, non-unique org names, branch persist +
  org linkage, null-org rejection, native-insert FK violation, composite
  uniqueness (same org fails / different orgs pass), timestamp create/update
  semantics, string enum storage, V2 applied + tables exist.
- `ServiceCounterPersistenceTests` (new, 15 tests, `@Transactional`
  rollback): service persist + defaults, branch linkage, string statuses,
  minutes-duration round-trip + positive-duration guard, per-branch
  uniqueness (same branch fails / different branches pass) for services and
  counters, counter×N services, service×M counters, duplicate-link
  rejection, cross-branch rejection via domain guard AND raw-SQL
   (composite-FK backstop), V3 applied + tables exist.
- `UserStaffPersistenceTests` (16 tests): user persist + defaults, org
  linkage, string statuses, email normalization + global uniqueness,
  multi-role grants + string storage + duplicate-grant + revoke, assignment
  persist + linkage + string status, multi-branch / multi-user assignments,
  duplicate-assignment rejection, cross-org guard + raw-SQL rejection,
  V4 applied + tables exist.
- `WorkingCalendarPersistenceTests` (18 tests): intervals, string weekdays,
  split + adjacent days, duplicate/overlap/containment rejection, guards,
  CHECK + exclusion at raw-SQL level, cross-branch independence, holidays,
  V5 applied.
- `AppointmentBookingTests` (new, 32 tests, `@Transactional` rollback):
  booking success + linkage, unknown/inactive/mismatched validation,
  ownership guards + DB rejections, duration snapshot, edge/boundary/gap/
  closed-day rules, branch-zone conversion, holiday rules, customer/service
  conflicts, adjacency, terminal-state slot freeing, transition guards,
  native temporal + range checks, V6 applied.
- `AppointmentConcurrentBookingTests` (1 test, NOT transactional, own
  container): two threads race one slot → exactly 1 booking +
  1 `AppointmentConflictException` + 1 row. The mandatory proof that
  check-then-insert cannot double-book.
- `QueuePersistenceTests` (new, 30 tests, `@Transactional` rollback):
  queue persist + defaults, string status, per-branch-service queues,
  duplicate/cross-branch rejections (guard + DB), walk-in persist + token
  sequence/scoping, string priority/status, cross-org guard + DB rejections,
  duplicate-active + rejoin, join validation, strict ordering (emergency >
  priority > normal, FIFO), capability/branch/empty rejections, full
  lifecycle chain + invalid moves + unknown ids, V7 applied.
- `QueueConcurrentTests` (3 tests, NOT transactional, own container):
  8-thread token race → exactly 1–8; same-counter 2-claim race and
  3-counter race → exact entry sets, zero double-claims.
- `ServiceSessionTests` (new, 17 tests, `@Transactional` rollback):
  session persist + linkage, branch/counter/service ownership guards,
  service-mismatch guard + honest DB-permissiveness proof, timestamp types
  + exact fixed-clock values, lifecycle integration (start/end), invalid
  moves, unknown ids, V8 applied.
- `ServiceSessionConcurrencyTests` (2 tests, NOT transactional, own
  container): concurrent starts → exactly 1 session + SERVING; concurrent
  completions → exactly 1 end + COMPLETED + zero open sessions.
- `QueueAgingTests` (new, 9 tests, `@Transactional` rollback, fixed Clock):
  below/at/multi-threshold promotions, cap proof, starvation scenario,
  emergency ceiling, priority preservation, UUID tie-break semantics,
  legacy order.
- `QueueAgingDisabledTests` (2 tests, separate context with
  `queue.aging.enabled=false`): strict order despite age, emergency first.
- `QueueWaitTimeEstimatorTests` (17 tests, `@Transactional` rollback,
  fixed Clock, no sleeps): empty/single/multi ahead, priority/emergency
  ordering, aging-narrowed waits, multi-counter rounds, active-session
  units (single + multi), durations, SERVING/CALLED zero, terminal/unknown/
  empty/no-capacity cases, 25-deep queue, estimate-order == claim-order proof.
- `DomainEventPublicationTests` (9 tests, non-transactional, own
  container, fixed Clock, AFTER_COMMIT collector): per-event publication +
  full payload assertions, distinct event ids, Clock timestamps, commit
  delivery, rollback suppression.
- `RedisInfrastructureTests` (new, 7 tests, own containers): connectivity,
  string/JSON round-trips, TTL without sleeps, missing-key behavior,
  tenant-scoped deterministic keys, null-scope rejection.
- `QueueRealtimeProjectionTests` (new, 10 tests, `@Transactional` rollback):
  snapshot content/order/waits, privacy (no customer ids/emails in Redis
  JSON), serving/called token lists, terminal exclusion, rebuild after
  delete, idempotent double refresh, TTL bound, unknown queue, schema
  unchanged.
- `QueueRealtimeWebSocketTests` (new, 4 tests, RANDOM_PORT, own containers,
  real STOMP client): snapshot on request, join broadcast, tenant-topic
  isolation (bounded absence proof), wire payload privacy.
- `QueueRealtimeFailureTests` (new, 2 tests, own containers, real
  non-transactional commits): Redis stopped mid-test — join/call/serve/
  complete and cancel still commit in PostgreSQL, the bridge logs the
  failure, and a direct `refresh` call throws `DataAccessException`.
- `QueueRealtimeRefreshTests` (new, 14 tests, own containers, real
  non-transactional commits, fixed advanceable Clock, mocked broker):
  current-snapshot content + TTL + broadcast, quiet TTL renewal without
  broadcast, sub-threshold stability, threshold flip with wait change and
  persisted-priority immutability, aging cap, emergency ceiling,
  zero-capacity null waits, two-counter wait sharing, called/serving
  tokens with active-session wait, terminal exclusion + discovery
  exclusion, empty/inactive discovery exclusion, event→scheduler
  non-regression, stale-CAS protection + fresh retry, broker-failure
  degradation (Redis fresh, PostgreSQL intact).
- `QueueRealtimeRefreshSchedulingTests` (new, 3 tests, 100 ms interval):
  refresh bean present when enabled, deleted snapshot recreated by the
  live tick with no business event, empty queue left alone (bounded
  absence proof).
- `QueueRealtimeRefreshDisabledTests` (new, 2 tests,
  `queue.realtime.refresh.enabled=false`): no refresh bean, properties
  still bind.
- `QueueRealtimeRefreshPropertiesTests` (new, 5 tests, no containers):
  defaults (`enabled`, `PT1M`), valid custom interval, zero/negative/
  bogus values fail fast.
- `TenantContextTests` (new, 11 tests, no containers): lifecycle,
  fail-closed holder, branch-scope semantics, no User/roles in context.
- `TenantIsolationTests` (new, 16 tests, own containers, rollback):
  missing context fails closed; wrong organization/branch denied
  scope-safely; organization-level scope works; same-org branches isolated.
- `TenantBackgroundTests` (new, 3 tests, own containers, real commits):
  scheduler and event bridge work without HTTP context; multi-tenant
  sweep refreshes both tenants and never leaks scope.
- `AuthServiceTests` (new, 8 tests, rollback, fixed Clock): token shape +
  expiry + round-trip, case-insensitive lookup, generic failures for wrong
  password/unknown/inactive/credential-less users, BCrypt storage proof.
- `JwtServiceTests` (new, 9 tests, pure units, fixed Clock): round-trip,
  expiry boundary, tampered signature, `none` algorithm, missing claims,
  unknown role, malformed input.
- `AuthControllerTests` (new, 5 tests, real HTTP + PG/Redis containers):
  bearer response shape (no secrets), byte-identical 401s (no
  enumeration), 400 bodies, chain posture (401 anonymous/bad-token, 404
  authenticated-but-unmapped), public health.
- `TenantAccessPolicyTests` (new, 13 tests, rollback): org isolation,
  unknown/foreign branch denial, manager/admin org-wide, staff by ACTIVE
  assignment (inactive/other-branch denied), customer same-org display,
  exact queue-scope checks.
- `SecurityFilterTests` (new, 6 unit tests, servlet mocks + real JWT):
  bearer validation, 401 without chain, anonymous pass-through, tenant
  bridging with restore (incl. on exception).
- `AuthPropertiesTests` (new, 5 tests, no containers): missing/short
  secret and zero expiration fail fast; valid values bind.
- `QueueRealtimeWebSocketSecurityTests` (new, 6 tests, RANDOM_PORT, real
  sockets): anonymous/forged CONNECT rejected, own-scope served,
  cross-org subscribe/snapshot denied, unassigned staff denied.
- `RestApiFoundationTests` (new, 16 tests, RANDOM_PORT, own containers,
  real HTTP): own-org/branch reads with DTO shape (UUID/enum/ISO-8601),
  foreign-org scope-safe 404s (org + IDOR branch, incl. manager-of-other-org
  positive control), staff assigned-200/unassigned-403, customer display,
  401 anonymous/bad-token, 400 path + validation with `fieldErrors`,
  unknown-property leniency, login in the shared envelope (401 code/message
  parity), 404 envelope for unmapped paths, OpenAPI scheme/path/security
  assertions on the generated JSON, Swagger UI served, health public.
- `BranchManagementApiTests` (new, 28 tests, RANDOM_PORT, own containers,
  real HTTP): org-scoped listing (both directions, empty org, customer
  access, 401), pagination (first/middle/beyond-total-as-empty-page),
  name asc/desc + default sort, invalid sort/direction/size/page codes,
  status filter + invalid value, cross-tenant get/update/status 404s both
  directions, admin/manager/staff/customer role matrix on every write,
  201 + `Location` + org-derivation (supplied `organizationId` ignored),
  blank-name/timezone 400s, duplicate-name 409 + cross-org dup legal +
  no SQL leakage, PUT semantics incl. ownership/status immutability,
  activate/deactivate cycle + idempotency + visibility of inactive.
- `TenantAccessPolicyTests` (+3 tests): management tier —
  manager/admin allowed, staff/customer denied, scope-before-role on
  branch management.
- `ServiceCounterApiTests` (new, 23 tests, RANDOM_PORT, own containers,
  real HTTP): branch-scoped service/counter lists (both cross-tenant
  directions, empty branch, read matrix incl. unassigned-staff 403),
  pagination/sort (incl. `durationMinutes`)/status-filter edges,
  `201` + `Location` creates, validation failures, per-branch duplicate
  `409`s (cross-branch legal), PUT/activate/deactivate lifecycle +
  idempotency, capability add/list/remove (duplicate `409`, cross-branch
  `422`, foreign `404`s, write matrix, deactivation keeps capabilities,
  missing-body/unknown-service faults).
- `UserStaffApiTests` (new, 22 tests, RANDOM_PORT, own containers, real
  HTTP): management-only directory (staff/customer 403s), pagination/
  email-sort/status/role-filter edges, self-or-manager reads, foreign
  `404`s both directions, `201` + `Location` creates (defaults,
  password provisioning + immediate login, ADMIN stays org-scoped),
  global email `409`s (incl. case-folded), validation/unknown-role
  `400`s, PUT semantics (email/org/status immutability), full login
  lifecycle cycle (login → deactivate → generic denial → reactivate →
  login), assignment create/matrix/eligibility/cross-tenant `404`s,
  duplicate `409` + reactivate-instead, roster tiers (assigned 200,
  unassigned/customer 403), branch-access revocation on deactivation,
  credential absence from every body.
- `TenantAccessPolicyTests` (+2 tests): operational roster access and
  self-or-manager reads.
- `AppointmentApiTests` (new, 15 tests, RANDOM_PORT, own containers,
  real HTTP): self-service booking for every role (customer id always
  the principal), `201` + `Location` + snapshotted response shape,
  shape/time validation, past-start rejection, same-org `422` vs
  foreign `404` branch/service mismatch, calendar delegation
  (outside-hours/holiday/inactive-service codes), double-booking `409`
  with no SQL leakage, offset-instant preservation, privacy-tiered
  reads (owner/manager/staff-200, other-customer/unassigned-staff/
  foreign-404), role-routed listing (customer pinning + `403` on
  foreign id, staff branch requirement, manager facets + foreign
  `404`s), pagination/default-chronological/desc sorts, overlap window
  + inverted-range `400`, status facet + cancel-then-filter, cancel
  semantics (incl. re-bookable freed slot + terminal `409`), cancel
  matrix (owner/manager-200, staff/customer-403, foreign-404),
  complete/no-show matrix with terminal `409`s.
- `QueueApiTests` (new, 20 tests, RANDOM_PORT, own containers, real
  HTTP): self-service joins for every role (customer id always the
  principal), `201` + `Location` + server tokens/positions/waits,
  triage gating (operational PRIORITY ok, customer/elsewhere `403`,
  unknown `400`), duplicate-active `409`, inactive-queue `409`,
  same-org `422` vs foreign `404` branch/queue mismatch, branch display
  listing, rank-ordered snapshot with counts/positions/waits,
  privacy-first entry reads (owner/operational-200, others-404),
  operational entries listing (filters, pagination, no live
  positions in bulk pages), rank-ordered claims, call-next matrix +
  documented empty-queue `409`, two-caller race proving no shared
  claim, full join/call/serve/complete flow, lifecycle guards
  (incapable/cross-branch-400, unknown-404, customer-403),
  cancel/no-show semantics with terminal `409`s + freed-slot re-join,
  zero-capacity unknown waits, REST→Redis projection proof.
- `QueueRestDegradationTests` (new, 1 test, DynamicProperty Redis
  wiring): join/state/claim all succeed with the Redis container
  stopped — correctness never depends on freshness.
- `ServiceSessionApiTests` (new, 7 tests, RANDOM_PORT, own containers,
  real HTTP): session shape (derived active/duration, full linkage, no
  credentials), operational visibility tiers (managers/admin/assigned
  200, customer/unassigned/foreign 404s), current-session lifecycle
  (running 200 → completed 404-idle) + scope/role matrix, counter
  history (exact counts, newest-first default, asc reversal,
  pagination), global list (facets, active true/false incl. derived
  durations, staff branch requirement, customer 403, foreign 404),
   window/sort/size edges.
- Frontend suite (33 Vitest tests, `fetch` stubbed — no backend
  needed): API client (7: auth header, anonymous omission, envelope +
  fieldErrors, 401 notify + code, filter-level 403, 409 code/message,
  network error); JWT claims (4); session storage (3: round-trip, clear,
  corrupt-shape rejection); navigation (4: customer/staff/manager/admin
  + multi-role); AuthProvider (6: login success + persist, 401 message,
  restore, expired drop, logout, 401-clears-session); route guards (5:
  anonymous→login, auth→dashboard, login→dashboard, role→unauthorized,
  unknown→404); LoginPage (4: empty/malformed validation without fetch,
  backend 401 display, success persist).
- Frontend customer suite, Phase 4A (43 new, 76 total): customer APIs
  (6: exact URLs/bodies, never any `customerId`/token/priority params);
  branch-zone datetime (6: fixed-offset + DST round-trips, invalid
  rejection, zone display, wait copy); last-entry storage (2);
  dependency-free STOMP client (6: handshake/subscribe/snapshot-request,
  matching/foreign/malformed frames, failure, disconnect); customer
  dashboard (2: widgets + empty states); appointments list (3), detail
  (5: cancel action gating, not-found, confirm→cancel, 409 copy),
  booking (3: validation, instant preservation, conflict copy); queue
  join (2: join→status, duplicate copy); my-queue (8: empty,
  position/wait, unknown-wait, terminal + dismiss, gone, leave, manual
  degradation, live snapshot refresh + context).
- Frontend staff suite, Phase 4B (37 new, 113 total): staff APIs (7:
  assignment discovery, counter/capability reads, bodiless call-next,
  idle-404→null, session facets, exact lifecycle commands, branch-scoped
  appointments without customer search); branch context (3: assignment
  resolution, empty honesty, manager org list); queue ops (9: live
  snapshot, history + facet, complete, no-show confirm, staff/manager
  cancel gating, 409 copy, customer refs, refresh degradation); counter
  workspace (6: idle + capabilities, claim, empty-queue copy,
  double-submit guard, running occupancy, start-with-counter +
  complete); sessions/appointments/dashboard (6: durations, active
  facet, branch appointments, own-row cancel gating, occupancy,
   no-assignment); operational route authorization (5: staff/manager
   access, customer denial, ops nav).
- Frontend management suite, Phase 4C (17 new, 130 total):
  management APIs (5: branch list/create/lifecycle shapes without
  org/status fields, service branch-nested + flat lifecycle,
  counter + capability add/remove routes, user create without
  password leakage + assignment/roster lifecycle); error copy (6:
  conflict sentences, 400 field-error preference, fallback);
  branch payload helpers (2: optional omission, address format);
  navigation (5 incl. no-future-placeholder assertion);
  management route authorization (5: manager/admin access,
  customer/staff denial on every `/manage` route, dashboard
  landing).
- Backend regression for Phase 4C: management API suites
  (`BranchManagementApiTests`, `ServiceCounterApiTests`,
  `UserStaffApiTests`) re-run green; full suite 452 run with a
  single pre-existing flake — `AppointmentConcurrentBookingTests`
  deadlocked under full-suite load (PostgreSQL ShareLock between
  the two racing inserts, counted only as
  `AppointmentConflictException`). Passes in isolation; zero
  backend files changed in this phase, so the flake is unrelated
  to it.
- Backend notification suite, Phase 4D (18 new, all green in the
  full-suite run): persistence (5:
  round-trip with string enums, cross-org recipient rejection,
  blank-title/missing-event guards, event uniqueness backstop,
  read-stamp idempotency, V10 applied); API (13: 401, empty
  list/zero count, joined/appointment creation with DTO shape and
  no org/recipient leakage, recipient + tenant 404 isolation,
  idempotent read, read-all caller scope, pagination + unread
  filter + 400 edges, rollback creates nothing, duplicate handling
  keeps one row, sender failure keeps the row, live echo reaches
  only the recipient session, unknown destinations denied).
- Frontend notification suite, Phase 4D (20 new, 150 total): API
  shapes (3: unread filter/sort, no user-id params, count + read
  commands without PUT); live client (4: fixed user-destination
  subscribe, delivery + malformed-frame tolerance, failure/ERROR
  degradation, DTO guard); navigation targets + display helpers
  (4: customer-only links, safe nulls, labels, relative times);
  bell/panel (5: badge, hidden badge, mark-all + view-all, empty +
  Escape, error retry); route authorization (3: anonymous redirect,
  all-roles access, mark-all confirmation); navigation (1: bell
  destination for every role).
- Tests require a Docker daemon by design (documented, not worked
  around with H2).

## Pending Features (all future phases)

All of the following are **Planned, not implemented**: email/SMS/push
senders, notification archival, staff-broadcast and
appointment-lifecycle notifications, reminder preferences, additional
reminder kinds/channels, analytics exports/scheduled reports,
scheduled jobs, app
Docker image, deployment, plus working-hours/holiday management
APIs. (All domain slices through operational analytics are done,
plus customer, staff, manager/admin, notification-center, and
analytics frontends; suites grow per phase.)

## Known Limitations

1. Walk-in queue operations incl. sessions, aging, estimation, events,
   Redis snapshots, tenant scope, auth foundation, the REST contract,
   branch/service/counter/capability/user/assignment/appointment
    management, queue join/state/claim/lifecycle, and session reads exist
    as services/config plus sixty-one application endpoints (login,
   own-org read, branch/service/counter/user equivalents, capability
   list/add/remove, assignment routes and roster, appointment
   booking/list/item/cancel/complete/no-show, queue join/list/snapshot/
     entries/item/claim/lifecycle, session list/item/current/history,
      notification list/item/read/read-all/unread-count, and the
      notification retention sweep, plus the appointment reminder sweep
      (no endpoints — reminders surface through the center), plus the
      read-only analytics overview (manager/admin, no endpoints beyond
      it); no
      other CRUD, no deletion/transfer, no organization mutation/listing,
      (password workflows exist since the account-lifecycle phase: register,
      verify, resend, forgot/reset, change — rate-limited, token-based, with
      session revocation on change; there is still no rescheduling, no
      email/SMS/push senders, no
      notification archival, no reminder preferences, no analytics
      exports). Scheduled
   freshness duplicates refresh work across instances by documented
   decision (no distributed lock). Worker threads install their own
   tenant scope explicitly — ThreadLocal scope never crosses into pool
   threads on its own. Stateless tokens stay valid until expiry (no
   revocation). Customer frontend (4A) tracks only the latest queue entry
   (one stored id), offers no availability search (none exists to offer).
   Staff frontend (4B) browses per-branch (no combined manager view),
   requires the counter workspace for start-serving, and shows Cancel to
   managers only. No other
   domains — correctly so.
2. Tests require a Docker daemon (Testcontainers by design). Without Docker
   they cannot run; with Docker, 452/452 pass (verified 2026-10-07, clean
   re-run build).
   (The known timing flake in `AppointmentConcurrentBookingTests` recurred
   once during Phase 3N verification: two racing inserts deadlocked on
   the exclusion check instead of conflicting cleanly. It passes in
   isolation and on full-suite rerun; the appointment code is untouched by
   Phase 3N — the phase's backend diff is CORS config only, and the
   deadlock graph contains only the two booking transactions.)
   Phase 4F verification note (2026-10-08): this environment has no
   Docker daemon, so the committed Testcontainers suites could not
   execute here. All Phase 4F behavior was instead proven against a
   real PostgreSQL via a temporary embedded-PostgreSQL harness (9/9
   green: V1–V12 applied, Hibernate validate green, due/boundary/
   catch-up/terminal/batching/TZ/DST/sender-failure/tick-failure
   behavior, plus the two-thread claim race yielding exactly one
   reminder and one notification); the harness was deleted afterwards
   and `pom.xml` is unchanged by it. The committed suites compile and
   mirror the proven assertions one-to-one; the full 515-test suite
   awaits a Docker-capable runner.
   Phase 4G verification note (2026-10-08): same environment, same
   approach — the committed Testcontainers suites (30 new) compile but
   could not execute here. All Phase 4G behavior was proven against a
   real PostgreSQL via a temporary embedded-PostgreSQL harness (6/6
   green: V1–V13 applied with all three new indexes present,
   Hibernate validate green, exact metric math, `[from, to)`
   boundaries, branch-zone derivation, DST windows, guards, zero-data
   nulls, running-session exclusion, and the HTTP contract
   401/403/200); the harness was deleted afterwards and `pom.xml` is
   unchanged by it. Verification caught and fixed two real defects
   pre-completion (HQL `EXTRACT(EPOCH …)` over instant subtraction
   fails query validation; multi-select Spring Data methods must
   declare `List<Object[]>`). The full 545-test suite awaits a
   Docker-capable runner.
   Phase 4H verification note (2026-10-08): same environment. All
   Docker-free backend tests pass here (32 run: JWT incl. the new
   expiry-boundary test, tenant, analytics/reminder properties).
   The delivery-ordering fixes were additionally proven against a
   real PostgreSQL via a temporary embedded-PostgreSQL harness (5/5
   green: commit→row+single echo, duplicate→single row/echo,
   sender-throw→row kept, reminder sweep→rows+echo with idempotent
   rerun, reminder echo failure→rows kept); harness deleted
   afterwards,    `pom.xml` unchanged. Frontend 164/164 green plus a
   clean production build. The full 546-test Testcontainers suite
   still awaits a Docker-capable runner; the WebSocket-origins and
   snapshot-auth changes are wiring-level and covered by the
   existing live-socket suites there.
3. Only V1–V20 migrations exist (V10 notifications, V11 retention
   index, V12 appointment reminders, V13 analytics indexes, V14
   password-change revocation column, V15 verification tokens, V16 reset
   tokens, V17 organization invitations, V18 OAuth identities, V19 service
   queue backfill, V20 organization membership — see ADR-040/Phase 4R.2).
   Future tables arrive one migration per phase — never speculative. (Phase 3H
   needed no migration: the branch schema already held every field.)
4. `HELP.md` is the unmodified Spring Initializr help file (git-ignored);
   it will be removed or replaced when real setup docs mature.
5. Mockito self-attach warning appears on JDK 21 during tests (inline
   mock-maker); harmless, no Mockito usage yet.

## Technical Debt / Open Decisions

1. **Spring Boot version — resolved in ADR-002 (kept as reference):**
   Spring Boot **4.1.1** + Java 21 adopted (no downgrade to 3.x). This affects
   dependency coordinates and Security config style for upcoming phases —
   always verify against Boot 4.x / Spring Framework 7.x documentation.
2. **Empty `pom.xml` metadata:** `<name/>`, `<description/>`, `<url/>`,
   `<licenses>`, `<developers>`, `<scm>` are empty Initializr placeholders.
   Fill in before any public/portfolio presentation.
3. **`target/` directory is present locally** (build output). It is covered by
   `.gitignore` and must never be committed.

## Important Decisions So Far

- ADR-001: modular monolith, package-by-feature (Accepted).
- ADR-002: Spring Boot 4.1.1 + Java 21 adopted, no downgrade to 3.x (Accepted).
- ADR-003: PostgreSQL as primary database, same family everywhere (Accepted).
- ADR-004: Flyway owns schema evolution; Hibernate validates only (Accepted).
- ADR-005: Testcontainers PostgreSQL via `@ServiceConnection`, no H2 (Accepted).
- ADR-006: UUID identity + `Instant` timestamps via `BaseAuditableEntity` (Accepted).
- ADR-007: Organization–Branch ownership (unidirectional lazy FK, naming
  constraints, no services/APIs in 2A) (Accepted).
- ADR-008: Service/counter capability model (branch-owned, composite-FK
  cross-branch protection, minutes durations, no services/APIs in 2B)
  (Accepted).
- ADR-009: User identity (global email), role grants without a Role entity,
  staff assignments with composite-FK cross-org protection; no passwords,
  no Security, no APIs in 2C (Accepted).
- ADR-010: Branch calendar (interval rows, exclusion-guarded non-overlap,
  explicit holidays, deferred timezone, no availability yet) (Accepted).
- ADR-011: Appointment time model (branch zones, snapshotted `[start,end)`),
  partial per-service/per-customer exclusions (capacity-1 assumption),
  transactional booking with translated conflicts, race-tested (Accepted).
- ADR-012: Walk-in queue (one queue per branch+service, daily tokens via
  locked counter rows, strict priority + FIFO, SKIP LOCKED atomic claims,
  guarded lifecycle; no aging/events/wait-time) (Accepted).
- ADR-013: Service sessions (entry/counter/service linkage, one session per
  entry, row-locked atomic start/completion, Clock-recorded facts, no
  estimates) (Accepted).
- ADR-014: Queue aging (derived effective rank, structural EMERGENCY
  ceiling, capped steps, SQL ranking, Clock-driven, no migration) (Accepted).
- ADR-015: Wait-time estimation (position model, shared ordering, whole
  minutes, snapshot semantics, no ML/history/migration) (Accepted).
- ADR-016: Application events (immutable facts, IDs-only payloads,
  AFTER_COMMIT delivery, no brokers/consumers/async) (Accepted).
- ADR-017: Redis foundation (derived-only state, strings + Jackson-3 JSON,
  tenant-scoped keys, TTL-as-freshness, health-visible/degradable, no
  authority moved) (Accepted).
- ADR-018: Real-time queue state (PG-rebuilt snapshots, tenant-scoped STOMP
  topics, snapshot-on-request, public token-only payloads, simple broker,
  auth deferred) (Accepted).
- ADR-020: Live wait-time refresh (bounded per-interval rebuilds of queues
  with live entries, same builder/contract, quiet-on-unchanged, Lua
  compare-and-set against stale overwrites, best-effort scheduler, no
  locks/brokers/quartz) (Accepted).
- ADR-019: Multi-tenant application context (immutable org+branch scope,
  fail-closed holder with save/restore, scope-aware repositories,
  scope-safe not-found, explicit background scope; no Hibernate filters,
  no schema/database-per-tenant, no Security/JWT/REST) (Accepted).
- ADR-021: Authentication & authorization foundation (BCrypt credentials,
  self-contained HS256 JWTs, login API, tenant-bound security, org/branch/
  role policy, authenticated sockets; no refresh tokens, no lockout/
  rate-limit/audit) (Accepted).
- ADR-022: REST API & contract foundation (`/api/v1`, thin controllers,
  DTOs + manual mapping, `ApiError` envelope, scope-safe reads,
  policy-reused authorization, OpenAPI `bearerAuth`, pagination convention
  deferred to the first list endpoint; no CRUD expansion) (Accepted).
- ADR-023: Branch management API (MANAGER/ADMIN org-scoped writes,
  paginated allow-list sorting with `id` tiebreak, PUT full replacement,
  idempotent activate/deactivate commands, constraint-arbitrated `409`s,
  no DELETE/transfer, no migration) (Accepted).
- ADR-024: Service/counter/capability API (nested collections + flat
  items, existing association model, `422` same-branch mismatch,
  minutes at the boundary, constraint-arbitrated conflicts, no
  migration) (Accepted).
- ADR-025: User/staff API (tiered directory visibility, org-scoped
  roles, immutable email, creation-time passwords only, STAFF-gated
  assignments, login-coupled lifecycle, no migration) (Accepted).
- ADR-026: Appointment API (self-service-only booking, delegation over
  duplication, privacy-first reads, honest non-idempotent lifecycle,
  conjunctive search with flag-bound instants, no migration) (Accepted).
- ADR-027: Queue API (thin adapter over untouched engine,
  counter-centric claiming, shared rank/estimator views, privacy tiers,
  Redis-independent correctness, no migration) (Accepted).
- ADR-028: React frontend foundation & auth shell (React 18 + strict TS
  + Vite + Router, no store library; `localStorage` session with honest
  tradeoffs; no `/me` invented; minimal CORS allow-list on `/api/**`;
  frontend authorization is UX only; no fake metrics) (Accepted).
- ADR-029: Customer appointment & queue experience (no faked
  availability; `Intl`-only branch-zone times; id-only queue storage;
  realtime as refresh signal with explicit degradation; null waits stay
  null; no backend changes) (Accepted).
- ADR-030: Staff operational experience (assignment-derived branch
  scope; counter-centric claim + workspace-scoped start; 404-as-
  occupancy; manager-only cancel visibility; realtime reuse without ops
  timer polling; double-submit guards as UX only; no backend changes)
  (Accepted).
- ADR-031: Manager/admin experience (management workspace over existing
  management APIs; real counts only; CUSTOMER/STAFF denied on `/manage`
  as UX — backend enforces; no backend changes) (Accepted).
- ADR-032: In-app notifications (persisted rows, `IN_APP` sender port,
  AFTER_COMMIT mapping, recipient-scoped REST + per-session realtime echo;
  no email/SMS/brokers) (Accepted).
- ADR-033: Notification retention (bounded policy: P90D read / P365D
  unread, PT6H sweep, id-batched deletes, V11 index) (Accepted).
- ADR-034: Appointment reminders (PT24H in-app sweep, durable
  idempotency rows, DST-safe copy, at-least-once ticks / at-most-once
  rows, V12) (Accepted).
- ADR-035: Operational analytics (one read-only manager/admin overview,
  measured waits/durations only, explicit windows + timezone, V13
  predicate-shaped indexes) (Accepted).
- ADR-036: Customer identity & organization — organization-scoped
  customers, organizationId required at customer registration, atomic
  business registration (**Superseded by ADR-040** — historical record
  retained in the file).
- ADR-037: Account tokens & session revocation (single-use verification /
  reset / invitation tokens with digests and TTLs, password-change
  session revocation via `password_changed_at`, lifecycle cleanup sweep;
  V14–V17).
- ADR-038: Queue auto-provisioning (service creation provisions the
  `ACTIVE` queue in the same transaction; V19 idempotent backfill; no
  endpoint or response change).
- ADR-039: Appointment availability (advisory continuous free-time search
  mirroring booking exactly; reuses booking predicates; no slot holds;
  booking exclusions stay authoritative).
- ADR-040: Customer identity & organization membership — platform-level
  customers, `OrganizationMembership` (STAFF/MANAGER/ADMIN) as the sole
  operational authorization fact, resource-derived customer context,
  `PENDING_APPROVAL` organization onboarding, `PLATFORM_ADMIN` review
  concept (Accepted, Phase 4R.1 — **target documented; NOT implemented**;
  supersedes ADR-036).
  (Phase 3M carried no ADR: it applied the 3H–3K templates with no new
  architectural decision — see `api/service-sessions.md`.)
- Actuator decision (no ADR — config-level): health endpoint only, details
  never shown; broaden exposure only with a documented reason.
- Shared-database logical multi-tenancy detail deferred to ADR-019 (later).

**Phase 4O — Branch Schedule Status & Queue Pre-Join Estimate (complete, 2026-10-08).**

Two read-only backend capabilities:

1. **Branch Schedule Status** — `GET /api/v1/branches/{branchId}/schedule-status`.
   Returns `OPEN`/`CLOSED`/`HOLIDAY`/`INACTIVE` state based on branch-local
   time (IANA timezone from branch config), working hour intervals, and
   holidays. Inactive branches return `INACTIVE` rather than an error.
   Readable by every role in the organization.

2. **Queue Pre-Join Estimate** — `GET /api/v1/branches/{branchId}/queues/{serviceId}/estimate-join`.
   Read-only aggregate wait estimate for a new joiner. Reuses the existing
   per-entry estimator scope and formula (new entry ranks last among current
   WAITING). Never creates a QueueEntry. Exposes only aggregate information
   (no customer PII). `estimatedWaitMinutes` is null when queue is inactive
   or no capable counters exist.

**Phase 4O.1 — Completion (tests + docs):**
- `ScheduleStatusServiceTests` — 24 service-level tests (fixed clock, timezone, holiday, split intervals, closed weekday, precedence, response shape)
- `ScheduleStatusApiTests` — 8 API-level tests (success, inactive, holiday, closed day, wrong org, unauthenticated)
- `QueuePreJoinEstimateTests` — 16 estimator-level tests (empty, waiting, priority, aging, counters, sessions, inactive, privacy, read-only)
- `QueuePreJoinEstimateApiTests` — 8 API-level tests (success, inactive queue/service, wrong org, missing queue, mismatch, unauthenticated, PII)
- API docs updated: `api/branches.md`, `api/queues.md`

**Known baseline test failures (pre-existing, unchanged):**
- AnalyticsServiceTests: 1 error + 1 failure
- AppointmentReminderTests: 1 error
- CalendarManagementTests: 1 error + 1 failure
- NotificationApiTests: 1 error

**Build:** Maven unavailable in this environment — tests not executed locally.

**Phase 4P — Customer Schedule Status & Pre-Join Queue Experience (complete, 2026-10-08).**

Frontend integration of the Phase 4O backend read models into the customer-facing experience:

1. **Schedule Status API client** — `getScheduleStatus()` added to `customer/api/branches.ts`. Typed `ScheduleStatusResponse` added to `customer/types/customer.ts`. No frontend state calculation.

2. **Queue Estimate API client** — `getQueueEstimate()` added to `customer/api/queues.ts`. Typed `QueueEstimateResponse` added to `customer/types/customer.ts`. No frontend wait-time formula.

3. **ScheduleStatusCard component** — Compact operational status display. Renders OPEN/CLOSED/HOLIDAY/INACTIVE states with text labels, today's hours from backend `todayIntervals`, timezone display. No color-only information.

4. **QueueEstimateCard component** — Pre-join aggregate wait display. Shows waiting count, estimated wait (~N min), handles null estimate as "Wait time currently unavailable", blocks join for inactive queue.

5. **JoinQueuePage enhanced** — Shows `ScheduleStatusCard` after branch selection, `QueueEstimateCard` after queue selection. Join button only enabled for ACTIVE queues. Existing join endpoint unchanged.

6. **BookAppointmentPage enhanced** — Shows `ScheduleStatusCard` after branch selection. Availability section unchanged.

7. **CSS styles** — `.sq-schedule-card`, `.sq-estimate-card` added to `styles.css` following existing `sq-` BEM conventions.

8. **Tests** — 6 new tests in `JoinQueuePage.test.tsx` covering schedule status display, estimate display, null estimate, inactive queue, join success, and PII absence.

**Known limitations:**
- Browser verification not performed (no browser tooling available).
- Full frontend test suite not executed (environment limitation).

## Next Planned Task

**Phase 4R.3 (platform-level customer identity) is complete. The next phase
is NOT started and must be requested explicitly.**

Recommended follow-ups (each a separate, explicitly scoped phase): the
defective Phase 4O tests and the 7 pre-existing fixture failures if a fully
green suite is desired; organization approval lifecycle
(`PENDING_APPROVAL` + `PLATFORM_ADMIN` review); multi-membership staff
organization selection; OAuth provisioning decoupled from organization
selection. OAuth/SMS/MFA otherwise remain future phases.
