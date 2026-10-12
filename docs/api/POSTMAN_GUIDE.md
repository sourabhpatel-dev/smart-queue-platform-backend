# SmartQueue Postman API Guide & Handover

This guide documents how to import, configure, and execute the complete **SmartQueue Platform** API collection and environment template in Postman for professional demonstration, testing, and handover.

---

## 1. Deliverables

- **Collection:** `backend/postman/SmartQueue.postman_collection.json`
- **Environment Template:** `backend/postman/SmartQueue.local.postman_environment.json`
- **Guide:** `backend/docs/api/POSTMAN_GUIDE.md`

---

## 2. Prerequisites & Infrastructure Setup

Before running Postman requests against the backend, ensure the required infrastructure and application are running:

1. **Start Backend Infrastructure (PostgreSQL, Redis, Mailpit):**
   ```powershell
   cd backend
   docker compose up -d
   ```
   - Mailpit Web UI: `http://localhost:8025` (captures outbound emails locally).
   - PostgreSQL: `localhost:5432`.
   - Redis: `localhost:6379`.

2. **Start the Spring Boot Backend:**
   ```powershell
   $env:JWT_SECRET = "at-least-32-bytes-of-local-dev-randomness-for-jwt-signing"
   .\mvnw.cmd spring-boot:run
   ```
   - Server runs on `http://localhost:8080`.

---

## 3. Importing into Postman

1. Open Postman.
2. Click **Import** (top left).
3. Drag and drop both JSON files from `backend/postman/`:
   - `SmartQueue.postman_collection.json`
   - `SmartQueue.local.postman_environment.json`
4. Select **SmartQueue Local Environment** from the active environment dropdown (top right).

---

## 4. Environment Variables Reference

| Variable Name | Default Placeholder | Purpose |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | Spring Boot API root |
| `customerEmail` | `asha@example.com` | Test customer account email |
| `customerPassword` | `Secret123!` | Test customer account password |
| `customerToken` | *(empty)* | Captured Bearer JWT for customer |
| `ownerEmail` | `mira@example.com` | Business owner account email |
| `ownerPassword` | `Secret123!` | Business owner account password |
| `ownerToken` | *(empty)* | Captured Bearer JWT for owner/manager |
| `staffEmail` | `staff@example.com` | Staff member email |
| `staffPassword` | `Secret123!` | Staff member password |
| `staffToken` | *(empty)* | Captured Bearer JWT for staff |
| `platformAdminEmail` | `admin@smartqueue.local` | Platform administrator account email |
| `platformAdminPassword` | `Secret123!` | Platform administrator password |
| `platformAdminToken` | *(empty)* | Captured Bearer JWT for platform admin |
| `organizationId` | *(empty)* | Active organization UUID |
| `branchId` | *(empty)* | Active branch UUID |
| `serviceId` | *(empty)* | Active service UUID |
| `counterId` | *(empty)* | Active counter UUID |
| `appointmentId` | *(empty)* | Booked appointment UUID |
| `queueId` | *(empty)* | Active walk-in queue UUID |
| `queueEntryId` | *(empty)* | Queue entry (token) UUID |
| `sessionId` | *(empty)* | Active counter session UUID |

> **Security Note:** Postman environments can store sensitive credentials and JWT tokens. Keep private environments local and never commit populated secrets or production tokens.

---

## 5. Recommended Execution Order & Workflow

To test dependent flows successfully, follow this sequence:

1. **Health Check:** Run `1. Health & Infrastructure -> Health Check` (`GET /actuator/health`) to verify service readiness.
2. **Customer Registration & Verification:**
   - Run `2. Authentication & Account -> Customer Register (Public)`.
   - Open Mailpit at `http://localhost:8025`, copy the verification token.
   - Update the token in `Verify Email (Public)` and run it.
   - Run `Login (Customer / Owner / Staff)` to populate `customerToken`.
3. **Business Onboarding & Approval (Optional Owner Flow):**
   - Run `Business Register (Public Application)` to submit `City Clinic`.
   - Verify owner email via Mailpit.
   - Configure platform admin (`APP_PLATFORM_ADMIN_EMAILS=admin@smartqueue.local`), register/verify admin account, log in to get `platformAdminToken`.
   - Run `13. Platform Administration -> List Pending Applications`, then `Approve Organization Application`.
4. **Branch, Service & Counter Setup:**
   - Log in as business owner (`ownerToken` & `organizationId` auto-captured).
   - Create a branch (`Create Branch` -> captures `branchId`).
   - Create a service (`Create Service` -> captures `serviceId`).
   - Create a counter (`Create Counter` -> captures `counterId`).
   - Add counter capability (`Add Counter Capability`).
5. **Appointments & Queues:**
   - Check appointment availability (`Get Appointment Availability`).
   - Book appointment (`Book Appointment` -> captures `appointmentId`).
   - Join walk-in queue (`Join Queue (Walk-in)` -> captures `queueEntryId`).
6. **Staff Operations:**
   - Open counter session (`Open Counter Session` -> captures `sessionId`).
   - Call next customer (`Call Next Queue Entry`).
   - Start serving and complete entry.

---

## 6. Email Verification Workflow (Manual Step)

1. When registering a customer or business owner, the backend dispatches an email via Mailpit (`APP_EMAIL_PROVIDER=smtp`).
2. Open `http://localhost:8025` in your browser.
3. Click the most recent email to view the verification link.
4. Copy the 256-bit token parameter from the link and paste it into the `Verify Email (Public)` request body.

---

## 7. Platform-Admin Configuration

To test platform-admin review endpoints (`13. Platform Administration`):
1. Register an account (e.g., `admin@smartqueue.local`) and verify its email.
2. Set the environment variable in your backend process:
   ```powershell
   $env:APP_PLATFORM_ADMIN_EMAILS = "admin@smartqueue.local"
   ```
3. Restart the Spring Boot backend.
4. Log in with that account in Postman to capture `platformAdminToken`.

---

## 8. Test Scripts & Assertions

Postman requests include test scripts that:
- Validate HTTP response status codes (`200 OK`, `201 Created`, etc.).
- Automatically extract entity identifiers (`organizationId`, `branchId`, `serviceId`, `counterId`, `appointmentId`, `queueEntryId`, `sessionId`) from response headers (`Location`) or JSON bodies and save them into environment variables.
- Capture JWT tokens (`customerToken`) upon successful authentication.

---

## 9. Security & Tenant-Isolation Validation

Folder `14. Authorization & Tenant-Isolation Checks` demonstrates negative test cases:
- **Unauthorized Anonymous Request:** Accessing secured resources without a token returns `401 UNAUTHENTICATED`.
- **Cross-Tenant / Forbidden Access:** Accessing another organization's resources or administrative endpoints with insufficient privileges returns `403 FORBIDDEN` or scope-safe `404 NOT_FOUND`.

---

## 10. Known Limitations

- Real-time WebSocket subscriptions (`/ws`) require a native WebSocket client (such as Postman's WebSocket tab or wscat) with `?token=<jwt>` query parameter, rather than standard HTTP requests.
- Testcontainers integration tests run during `mvn test` using Docker daemon; Postman interacts with the running local development instance via HTTP.
