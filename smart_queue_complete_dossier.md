# Smart Queue & Appointment Management Platform — Complete Project Dossier

> Status: **In Progress**
>
> This document captures the agreed product requirements, architecture, technical design, implementation plan, and interview positioning. Features must only be described as implemented on a resume after they are actually built and verified in the codebase.

---

## 1. Project Overview

**Project:** Smart Queue & Appointment Management Platform

**Primary Goal:** Build a backend platform that allows organizations with customer-facing services to manage appointments, walk-in queues, service counters/staff, estimated waiting times, notifications, and real-time queue updates.

**Target Organizations**
- Hospitals
- Clinics
- Banks
- Government offices
- Service centers
- Other appointment/queue-driven organizations

The platform is designed to solve a common operational problem: customers should be able to book appointments or join a queue, while staff should be able to manage service flow and customers should receive timely status and wait-time information.

---

## 2. Current Status

The project is being developed as a **modular monolith** initially.

The first version should prioritize:
- Clear domain boundaries
- Correct transactional behavior
- Concurrency safety
- Maintainable Spring Boot architecture
- Good API design
- Security
- Real-time updates
- Database correctness
- Testability
- Dockerized local development

Microservices, Kafka, Kubernetes, Elasticsearch, and similar distributed infrastructure are **not part of the initial architecture** unless a later requirement justifies them.

---

## 3. Technology Stack

### Backend
- Java 21
- Spring Boot 3.x
- Spring Web / Spring MVC
- Spring Data JPA
- Hibernate
- Spring Security
- Bean Validation
- Maven

### Data
- PostgreSQL
- Redis

### Real-Time Communication
- WebSocket
- STOMP

### Security
- JWT
- Role-Based Access Control

### Testing
- JUnit 5
- Mockito
- Spring Boot Test
- Testcontainers

### Infrastructure
- Docker
- Docker Compose

### Documentation / Development
- Git
- GitHub
- OpenAPI
- Swagger

---

## 4. High-Level Architecture

Initial architecture:

```text
                    ┌─────────────────────┐
                    │     Customer UI     │
                    └──────────┬──────────┘
                               │ REST/WebSocket
                               ▼
                    ┌─────────────────────┐
                    │   Spring Boot API   │
                    │   Modular Monolith  │
                    └──────────┬──────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                    │
          ▼                    ▼                    ▼
   Appointment Module    Queue Module        Organization Module
          │                    │                    │
          └────────────────────┼────────────────────┘
                               │
              ┌────────────────┴────────────────┐
              ▼                                 ▼
       ┌──────────────┐                  ┌──────────────┐
       │  PostgreSQL  │                  │    Redis     │
       │ Source Truth │                  │ Cache/State  │
       └──────────────┘                  └──────────────┘
                               │
                               ▼
                      WebSocket/STOMP
                               │
                               ▼
                       Real-Time Clients
```

PostgreSQL remains the primary source of truth for durable business data.

Redis is used selectively where fast access or temporary state provides a real benefit.

---

## 5. Why a Modular Monolith?

The first version is intentionally not split into microservices.

Reasons:
1. The domain is still evolving.
2. Appointment and queue operations are strongly related.
3. Many workflows require transactional consistency across multiple entities.
4. A modular monolith reduces distributed-system complexity.
5. It is easier to test and debug initially.
6. Deployment and local development remain simpler.

The application should still have clear modules/packages so that individual domains can later be extracted if scale or organizational requirements justify it.

Potential future modules/services:
- Identity/Auth
- Organization
- Appointment
- Queue
- Notification
- Reporting

That is a future evolution, not an initial requirement.

---

## 6. Domain Model

Core entities:

### User
Represents a platform user.

Potential responsibilities:
- Authentication identity
- Profile
- Role assignment
- Organization association where applicable

### Role
Primary roles:
- CUSTOMER
- STAFF
- MANAGER
- ADMIN

### Organization
Represents a business or institution using the platform.

Examples:
- Hospital
- Clinic
- Bank
- Government office

### Branch
Represents a physical location belonging to an organization.

### Service
Represents a service offered at a branch.

Examples:
- Doctor consultation
- Account opening
- Document verification
- Cash counter
- Registration

### ServiceCounter
Represents a physical/service counter that can serve customers.

### StaffAssignment
Maps staff members to relevant branches/services/counters.

### Appointment
Represents a scheduled customer appointment.

Important concepts:
- Customer
- Service
- Branch
- Appointment date
- Approximate time / slot
- Status
- Creation/update information

### Queue
Represents an active queue for a service/counter/branch.

### QueueEntry
Represents a customer waiting in a queue.

Possible states:
- WAITING
- CALLED
- IN_SERVICE
- COMPLETED
- CANCELLED
- NO_SHOW
- SKIPPED

### Notification
Stores notification-related information and delivery state where required.

### WorkingHour
Defines when an organization/branch/service is operational.

This is particularly important because the system should not generate customer schedules without knowing when the relevant operational entity is open.

### Holiday
Defines dates when a branch/service is unavailable despite normal working hours.

### AuditLog
Records important business/security actions for traceability.

---

## 7. Role-Based Behavior

### CUSTOMER
Should be able to:
- View organizations/branches/services
- View service availability
- View working schedules
- Book appointments
- Cancel eligible appointments
- Join eligible walk-in queues
- View queue position
- View estimated waiting time
- Receive queue/status updates
- View appointment history

### STAFF
Should be able to:
- View assigned services/counters
- Call the next customer
- Start service
- Complete service
- Skip customer
- Mark no-show
- Manage active queue
- See operational queue information

### MANAGER
Should be able to:
- Manage branch/service operational configuration
- Configure working hours
- Configure service availability
- Manage staff assignments
- Monitor queue/appointment operations
- Review operational information

### ADMIN
Should be able to:
- Manage platform-level users/roles
- Manage organizations
- Manage branches
- Perform administrative operations

Exact permissions should be enforced at the API/service layer, not only hidden in the UI.

---

## 8. Working Hours and Operational Schedule

A critical product requirement is that customers should know when an organization/service is actually available.

The platform should therefore model:
- Working hours
- Working days
- Holidays
- Branch availability
- Service availability
- Approximate service duration where applicable

This information should drive:
- Appointment availability
- Valid booking windows
- Queue availability
- Estimated schedules
- Customer-facing availability information

The system should not simply expose a static "open/closed" label.

Example:

```text
Clinic
  └── Branch
       └── Service: General Consultation
             ├── Mon-Fri: 09:00-17:00
             ├── Saturday: 09:00-13:00
             └── Sunday: Closed
```

The exact domain relationships should be finalized during implementation.

---

## 9. Appointment Management

Appointment booking is one of the core workflows.

Typical flow:

```text
Customer
   ↓
Select Organization
   ↓
Select Branch
   ↓
Select Service
   ↓
Check operating schedule
   ↓
Check appointment availability
   ↓
Book appointment
   ↓
Persist appointment
   ↓
Return confirmation
```

Important requirements:
- Do not allow bookings outside operating hours.
- Do not allow bookings on holidays/closed days.
- Prevent conflicting appointments.
- Validate service/branch availability.
- Enforce authorization.
- Use transactions where multiple records must remain consistent.

---

## 10. Appointment Concurrency

Two customers may attempt to book the same appointment capacity simultaneously.

The system therefore needs database-level protection.

Potential mechanisms:
- Unique constraints
- Transaction boundaries
- Appropriate locking
- Pessimistic locking where justified
- Optimistic locking where appropriate

The exact mechanism should be selected based on the final appointment-slot model.

The important design principle is:

> Application-level checking alone is not sufficient to guarantee booking correctness under concurrency.

---

## 11. Walk-In Queue

Customers who do not have appointments should be able to join an eligible queue.

Basic flow:

```text
Customer
   ↓
Select Branch/Service
   ↓
Check queue availability
   ↓
Join queue
   ↓
QueueEntry created
   ↓
Position calculated
   ↓
Wait-time estimate shown
   ↓
Customer receives updates
```

Queue state should be persisted reliably.

---

## 12. Queue State Machine

Expected queue-entry lifecycle:

```text
WAITING
   │
   ▼
CALLED
   │
   ▼
IN_SERVICE
   │
   ▼
COMPLETED
```

Alternative transitions:

```text
WAITING → CANCELLED
WAITING → SKIPPED
WAITING → NO_SHOW
CALLED  → NO_SHOW
CALLED  → SKIPPED
```

Invalid state transitions should be rejected.

For example:
- COMPLETED → WAITING should not normally be allowed.
- CANCELLED → IN_SERVICE should not be allowed.
- A customer should not be called twice simultaneously.

A state-transition policy should be centralized rather than scattered across controllers.

---

## 13. Priority Queue

The platform should support priority handling where business requirements justify it.

Possible priority sources:
- Service-defined priority
- Appointment vs walk-in
- Emergency/urgent cases where applicable
- Business-defined priority classes

However, pure priority ordering can cause starvation for normal customers.

Therefore the design should consider **starvation avoidance**.

One approach is an aging concept where a waiting entry's effective priority increases with waiting time.

The exact algorithm should be implemented and tested before being claimed as implemented.

---

## 14. Estimated Wait Time

The platform should provide an approximate wait-time estimate.

A basic model can consider:
- Number of customers ahead
- Number of active service counters/staff
- Historical or configured average service duration
- Current service status

Example:

```text
Customers ahead = 8
Average service duration = 10 min
Active counters = 2

Approximate wait ≈ (8 × 10) / 2
                ≈ 40 minutes
```

This is an estimate, not a guaranteed service time.

The implementation should clearly communicate that the value is approximate.

---

## 15. Real-Time Updates

WebSocket/STOMP should be used where customers or staff need live updates.

Examples:
- Queue position changes
- Customer called
- Service started
- Service completed
- Queue movement
- Appointment/status changes

Typical conceptual flow:

```text
Business operation
       ↓
Database transaction
       ↓
Application event/update
       ↓
WebSocket/STOMP publication
       ↓
Connected clients
```

The database remains the source of truth. WebSocket messages are a delivery mechanism for timely updates.

---

## 16. Redis

Redis should be used selectively.

Potential uses:
- Frequently requested service availability
- Queue-related transient state where appropriate
- Estimated wait-time data
- Short-lived operational data
- Rate limiting if required
- Caching read-heavy data

Redis should not replace PostgreSQL as the source of truth for durable business records.

Cache invalidation must be considered whenever source data changes.

---

## 17. Security

Spring Security + JWT should protect the application.

Expected concepts:
- Authentication
- JWT access token
- Role-based authorization
- Method/API-level authorization
- Ownership checks

Example:

```text
CUSTOMER
  → Can access own appointments/queue entries

STAFF
  → Can manage assigned operational queues

MANAGER
  → Can manage branch/service operations

ADMIN
  → Can perform platform administration
```

Security must be enforced server-side.

---

## 18. DTOs and Validation

Controllers should not directly expose JPA entities as the API contract.

Use DTOs for:
- Request payloads
- Response payloads
- Validation
- API stability

Bean Validation can handle rules such as:
- Required fields
- String length
- Valid dates/times
- Positive numeric values
- Valid enum values

Business validations should remain in the service/domain layer.

---

## 19. Database Design

PostgreSQL is the durable data store.

Important relational relationships include:

```text
Organization
    │
    └── Branch
          │
          ├── Service
          │     └── Appointment
          │
          ├── ServiceCounter
          │
          └── StaffAssignment

User
 ├── Appointment
 └── QueueEntry

Queue
 └── QueueEntry
```

Database constraints should be used where they protect business invariants.

Potential indexes:
- Appointment by branch/service/date
- QueueEntry by queue/status
- User lookup fields
- WorkingHour by branch/service/day
- Holiday by branch/date

Exact indexes should be finalized after the actual query patterns are implemented.

---

## 20. Transaction Management

Transactions are particularly important for:
- Appointment booking
- Queue joining
- Calling the next customer
- Starting service
- Completing service
- Cancelling appointments
- Updating related business state

The goal is to prevent partially completed business operations.

Example:

```text
Call Next Customer
    ↓
Select eligible WAITING entry
    ↓
Update status → CALLED
    ↓
Persist
    ↓
Publish real-time update
```

The implementation must carefully consider transaction boundaries and concurrency.

---

## 21. JPA/Hibernate Considerations

Potential concerns:
- N+1 queries
- Lazy vs eager loading
- Pagination
- Fetch joins/entity graphs where appropriate
- Database indexes
- Transaction boundaries
- Optimistic/pessimistic locking
- Bulk operations where justified

Do not optimize prematurely. Query behavior should be measured/understood before introducing complexity.

---

## 22. Scheduled Jobs

Potential scheduled operations:
- Mark expired appointments
- Process no-show rules
- Clean up stale queue entries
- Process notifications
- Maintain temporary/cache state

Scheduled jobs must be designed so that repeated execution does not corrupt business state.

---

## 23. Notifications

The notification domain can support:
- Appointment confirmation
- Appointment cancellation
- Customer called
- Queue position updates
- Operational changes

The first implementation can focus on backend notification events and delivery abstraction.

Specific email/SMS/push providers should not be claimed unless actually integrated.

---

## 24. Testing Strategy

### Unit Tests
Use JUnit 5 and Mockito for:
- Service logic
- Queue calculations
- Appointment validation
- State transitions
- Wait-time estimation
- Authorization-related business rules

### Integration Tests
Use Spring Boot Test for:
- Controller/service/repository integration
- Persistence behavior
- Security flows

### Testcontainers
Use Testcontainers where real PostgreSQL behavior is important.

Particularly valuable test:

**Concurrent appointment booking**

Two requests attempt to reserve the same capacity concurrently.

Expected result:
- Business invariant remains valid.
- No duplicate capacity is created.
- One request succeeds and another is rejected when capacity is exhausted.

Do not claim concurrency tests exist until they are actually implemented.

---

## 25. Docker

Docker Compose should provide local infrastructure such as:
- PostgreSQL
- Redis
- Application

The objective is reproducible local development.

---

## 26. API Documentation

OpenAPI/Swagger should document:
- Authentication
- Organization APIs
- Branch/service APIs
- Appointment APIs
- Queue APIs
- Staff operations
- Management APIs

Documentation should reflect the actual implemented endpoints.

---

## 27. Error Handling

Use a centralized exception-handling approach.

Expected categories:
- Validation errors
- Authentication failures
- Authorization failures
- Resource not found
- Business rule violations
- Conflict/concurrency errors
- Internal server errors

Responses should use a consistent error structure.

---

## 28. Logging and Observability

Useful logging should include:
- Request correlation where appropriate
- Appointment operations
- Queue state transitions
- Authentication/security events
- Errors and exceptions
- Important business actions

Avoid logging:
- Passwords
- JWT secrets
- Sensitive personal information
- Other credentials

Additional metrics/monitoring can be added after the core system is stable.

---

## 29. Important Failure Scenarios

The system should be designed for:

### Double booking
Prevented using database constraints/locking/transactional design.

### Two staff members call the same customer
Queue transition must be concurrency-safe.

### Customer cancels while being called
Define valid state transitions and transaction behavior.

### Redis unavailable
Core durable business operations should not become permanently dependent on cache availability unless explicitly required.

### WebSocket disconnected
Client should be able to refresh/reconnect and retrieve current state from REST APIs.

### Stale queue data
PostgreSQL remains authoritative.

### Scheduled job runs twice
Business operation should be safe against duplicate execution where relevant.

---

## 30. Performance Considerations

Potential optimization areas:
- Database indexes
- Pagination
- Query optimization
- Redis caching
- Efficient queue queries
- Avoiding N+1 queries
- WebSocket updates instead of frequent polling
- Appropriate transaction scope

Do not claim measured performance improvements until benchmarks exist.

---

## 31. Why Not Kafka Initially?

Kafka is not required for the initial modular monolith.

Using Kafka would introduce:
- Additional infrastructure
- Event delivery complexity
- Consumer failure handling
- Duplicate event handling
- Eventual consistency

For the current scale and architecture, direct transactional database operations plus WebSocket updates are simpler.

Kafka could become useful later if:
- Notification processing becomes asynchronous
- Multiple independent consumers need the same events
- Audit/event streams are required
- The system is split into services

---

## 32. Why PostgreSQL + Redis?

### PostgreSQL
Best suited for:
- Appointments
- Users
- Organizations
- Queue entries
- Staff assignments
- Working hours
- Audit records

These require durable relational consistency.

### Redis
Best suited for:
- Frequently accessed temporary/read-heavy state
- Cache
- Rate limiting
- Fast operational lookups

Using both keeps responsibilities clear.

---

## 33. Future Scaling Direction

If the platform grows significantly, potential extraction boundaries include:

```text
API Gateway
Auth Service
Organization Service
Appointment Service
Queue Service
Notification Service
Reporting Service
```

At that stage, asynchronous messaging could be introduced.

The current modular architecture should make this transition easier because domain boundaries already exist.

---

## 34. Interview-Level Architecture Explanation

A concise explanation:

> "I’m building Smart Queue as a modular Spring Boot application for organizations such as clinics, banks, and service centers. The platform supports appointments and walk-in queues, multiple service counters, staff operations, estimated wait times, and real-time updates using WebSocket/STOMP. PostgreSQL is the source of truth for business data, while Redis is used selectively for fast-access or temporary operational data. I’m intentionally starting with a modular monolith because appointment and queue workflows require strong transactional consistency and the domain is still evolving. The design keeps clear domain boundaries so individual modules can be extracted into services later if scale requires it."

---

## 35. Accuracy Rules for Resume

Until implementation is complete, use:
- "Building"
- "Developing"
- "Designed"
- "Currently implementing"
- "Planned"

After code verification, change only genuinely completed features to:
- "Built"
- "Implemented"
- "Developed"
- "Designed and implemented"

Do not claim:
- Production deployment
- Production traffic
- Specific performance improvements
- High availability
- Millions of users
- Kubernetes
- Kafka
- Elasticsearch
- Cloud deployment
unless independently implemented and verified.

---

## 36. Definition of Done

The project should be considered resume-ready only after:
1. Core authentication/security works.
2. Organization/branch/service model works.
3. Working hours/holidays work.
4. Appointment booking and cancellation work.
5. Concurrency protection is implemented.
6. Walk-in queue works.
7. Queue state transitions work.
8. Staff operations work.
9. Wait-time estimation works.
10. WebSocket updates work.
11. Redis is used for a justified purpose.
12. Validation/error handling is consistent.
13. PostgreSQL schema/indexes are reviewed.
14. Automated tests cover important business logic.
15. Docker Compose starts the required infrastructure.
16. OpenAPI documentation matches the implementation.
17. README explains architecture and setup.
18. The application has been tested through realistic workflows.

