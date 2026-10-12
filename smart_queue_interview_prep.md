# Smart Queue — Interview Preparation

> Status: **In Progress**
>
> These answers describe the agreed design. During interviews, describe only what has actually been implemented by the time you discuss the project.

---

# 1. 60-Second Project Answer

> "I’m building a Smart Queue and Appointment Management Platform using Java 21 and Spring Boot. The idea is to support organizations like clinics, banks, and service centers where customers can either book appointments or join walk-in queues. The system manages branches, services, counters, staff assignments, appointments, queue entries, and working hours. PostgreSQL is the source of truth, Redis is used selectively for fast-access operational data, and WebSocket with STOMP provides real-time queue updates. I’m building it as a modular monolith initially because appointment and queue operations need strong transactional consistency, while keeping clear module boundaries so it can be split into services later if needed."

---

# 2. 2–3 Minute Explanation

> "The problem I’m solving is that organizations such as clinics or service centers need to manage both scheduled appointments and walk-in customers. Customers need visibility into availability, appointments, queue position, and approximate waiting time, while staff need to operate the queue from the service side.
>
> I’m using Java 21 with Spring Boot, Spring Data JPA and Hibernate, PostgreSQL, Redis, Spring Security with JWT, and WebSocket/STOMP. The first version is a modular monolith rather than microservices. I chose that because the appointment and queue workflows are closely related and need transactional consistency. Splitting them too early would introduce distributed-system complexity without a strong benefit.
>
> The main domains are users and roles, organizations, branches, services, service counters, staff assignments, appointments, queues, queue entries, working hours, holidays, notifications, and audit logs.
>
> For appointments, the system first checks whether the service is operating, whether the selected time is valid, and whether capacity is available. Because two customers could try to book the same capacity at the same time, I’m treating concurrency as a database-level problem rather than relying only on an application-level availability check.
>
> For walk-ins, customers join a queue and receive a queue position and estimated wait time. Staff can call the next customer, start service, complete service, skip an entry, or mark a no-show. Queue entries follow controlled state transitions such as WAITING to CALLED to IN_SERVICE to COMPLETED.
>
> WebSocket/STOMP is used for real-time updates so clients don't have to continuously poll the backend. PostgreSQL remains the source of truth, while Redis is used selectively for data where fast access or caching makes sense.
>
> The project also focuses on validation, centralized exception handling, testing, Docker-based local setup, and API documentation. If the platform eventually needs much larger scale, the modular boundaries provide a path to extract appointment, queue, notification, or other modules into separate services."

---

# 3. Why Did You Choose a Modular Monolith?

### Interview Answer

> "I chose a modular monolith because the domain is still evolving and the core appointment and queue workflows are tightly connected. They also require transactional consistency. Starting with microservices would add service-to-service communication, distributed transactions, deployment complexity, and failure handling before those problems are actually necessary. I still keep clear domain boundaries inside the monolith so that individual modules can be extracted later if scale or team structure justifies it."

### Follow-up: Why not microservices?

> "Microservices solve organizational and scaling problems, but they also introduce distributed-system problems. For this project, I wanted to solve the business domain correctly first and keep deployment and transactions simpler."

---

# 4. How Does Appointment Booking Work?

> "The customer selects an organization, branch, service, date and available time. The backend validates that the branch and service are active, checks working hours and holidays, validates the requested appointment capacity, and then creates the appointment within a transaction. The important part is that availability checking and booking cannot be treated as two completely independent operations because concurrent requests could otherwise oversubscribe the same capacity."

---

# 5. How Do You Prevent Double Booking?

### Strong Interview Answer

> "I don't rely only on an application-level check. Two requests can both read the same available state before either one commits. So I use database-level protection through an appropriate combination of transaction boundaries, unique constraints, and locking depending on how the final appointment-capacity model is implemented. The invariant should be enforced by the database and transaction design, not just by an if-condition in Java."

### Follow-up: Why is an if-condition not enough?

> "Because of a race condition. Request A checks availability, request B checks availability before A commits, and both can see the same available capacity. Both then attempt to book it."

---

# 6. How Does the Queue Work?

> "A customer joins an eligible service queue and gets a QueueEntry. The entry starts in WAITING. Staff can call the next eligible customer, moving it to CALLED, then IN_SERVICE when service starts, and finally COMPLETED. Other valid outcomes include CANCELLED, SKIPPED, or NO_SHOW. The backend controls these transitions so invalid state changes aren't allowed."

---

# 7. Why Do You Need Queue States?

> "Because queue processing is a state machine. Without explicit states, it becomes difficult to know whether a customer is waiting, already called, currently being served, completed, or skipped. Explicit states also make authorization, reporting, notifications, and concurrency handling much easier."

---

# 8. How Would You Handle Two Staff Members Calling the Same Customer?

> "The operation that selects and marks the next queue entry needs to be concurrency-safe. Both staff members should not be able to successfully transition the same WAITING entry to CALLED. I would use a transaction with an appropriate locking strategy or atomic database update, depending on the query and database design."

---

# 9. How Do You Calculate Estimated Wait Time?

> "The basic estimate can consider the number of customers ahead, average service duration, and the number of active counters. A simple approximation is customers ahead multiplied by average service duration divided by active counters. I treat it as an estimate rather than a guaranteed time because actual service duration varies."

Example:

```text
8 customers ahead
10 minutes average service
2 active counters

Estimate = (8 × 10) / 2 = 40 minutes
```

---

# 10. How Do You Handle Priority Customers?

> "I would assign an effective priority to queue entries and order eligible customers based on priority and waiting time. But I wouldn't implement pure priority ordering without considering starvation. A normal-priority customer could otherwise remain waiting indefinitely while higher-priority customers continuously arrive. An aging mechanism can gradually increase the effective priority of waiting customers."

---

# 11. Why WebSocket Instead of Polling?

> "Polling means the client repeatedly asks the server whether anything changed. That creates unnecessary requests and can still introduce delay. WebSocket provides a persistent connection so the server can push relevant queue updates when they happen. For a queue system where customers care about changes in near real time, that is a better fit."

### Follow-up: Is WebSocket the source of truth?

> "No. PostgreSQL is the source of truth. WebSocket is only the real-time delivery mechanism. If a client disconnects, it can reconnect and retrieve the current state through REST APIs."

---

# 12. Why STOMP?

> "STOMP provides a messaging protocol on top of WebSocket with concepts such as destinations and subscriptions. It gives the application a structured way to publish updates to groups of connected clients instead of implementing a custom messaging protocol."

---

# 13. Why Redis?

> "I use Redis selectively for data that benefits from fast access or caching, such as frequently accessed operational information or temporary state. I don't use Redis as the primary source of truth for appointments or queue history because those are durable business records that need relational consistency."

---

# 14. What If Redis Goes Down?

> "The core durable data should still be available in PostgreSQL. Redis should be treated as an optimization or temporary-state layer wherever possible. If a particular feature has a hard dependency on Redis, that dependency should be explicit and have an appropriate fallback or failure behavior."

---

# 15. How Do Working Hours Affect the System?

> "Working hours are not just display information. They should drive actual business validation. Appointment availability should be generated only during valid operating periods, and queue operations should respect whether the relevant branch or service is open. Holidays and exceptional closures also need to be considered."

---

# 16. Why Model Working Hours Separately?

> "Because operational availability is business data. A clinic might be open Monday through Friday but closed on a holiday, or one service may have different hours from another. If the system doesn't model that explicitly, appointment availability becomes unreliable."

---

# 17. What Are Your Main Database Entities?

> "The core entities are User, Role, Organization, Branch, Service, ServiceCounter, StaffAssignment, Appointment, Queue, QueueEntry, Notification, WorkingHour, Holiday, and AuditLog."

---

# 18. Why PostgreSQL?

> "The core domain is relational. Appointments, users, branches, services, staff assignments and queue entries have strong relationships and business constraints. PostgreSQL gives us transactions, constraints, indexes, and mature SQL capabilities that fit this domain well."

---

# 19. How Would You Prevent N+1 Queries?

> "First I'd identify the query pattern rather than blindly changing fetch types. Depending on the use case, I could use fetch joins, entity graphs, DTO projections, or carefully designed repository queries. I would also avoid making large collections eagerly loaded by default."

---

# 20. Why Use DTOs Instead of Entities in APIs?

> "DTOs separate the external API contract from the persistence model. They prevent accidental exposure of internal fields, make validation clearer, and allow the database model to evolve without necessarily breaking the API."

---

# 21. Where Should Validation Happen?

> "Basic request validation can happen at the API boundary using Bean Validation. Business validation belongs in the service/domain layer. For example, @NotNull can validate a required request field, while checking whether a service is open at the requested time is business logic."

---

# 22. How Would You Handle Invalid State Transitions?

> "I'd centralize the allowed transitions rather than scattering if-statements across controllers. For example, WAITING can move to CALLED, CANCELLED or SKIPPED depending on the business rules, while COMPLETED should normally be terminal. Invalid transitions should result in a business conflict rather than silently changing state."

---

# 23. How Would You Test Concurrent Booking?

> "I'd create an integration test that uses a real PostgreSQL instance, ideally through Testcontainers, and sends concurrent booking attempts against the same capacity. The assertion should verify that the business invariant is preserved and that we don't create more successful bookings than the available capacity."

---

# 24. Why Testcontainers?

> "For database behavior where transaction isolation, constraints, locking, or SQL behavior matter, an in-memory database can behave differently from PostgreSQL. Testcontainers lets the integration test run against an actual PostgreSQL environment."

---

# 25. What Happens If a WebSocket Connection Drops?

> "The client should reconnect and fetch the current state through REST. WebSocket messages are not the durable source of truth. The backend state is persisted, so a temporary connection loss doesn't lose the queue or appointment state."

---

# 26. What If a Scheduled Job Runs Twice?

> "The job should be designed around safe state transitions and idempotent operations where appropriate. For example, if a job processes expired appointments, it should update only records that are still eligible for that transition."

---

# 27. Why Not Put Everything in Redis?

> "Because Redis is optimized for fast in-memory access, while PostgreSQL provides durable relational consistency. Appointment history, queue records, users and audit information are business data that should survive cache restarts and support relational constraints."

---

# 28. What Are the Main Failure Scenarios?

Be ready to discuss:
- Double booking
- Concurrent queue operations
- Staff calling the same customer
- Customer cancellation during a state transition
- Redis unavailable
- WebSocket disconnect
- Stale cache
- Duplicate scheduled-job execution
- Database failures
- Invalid role access
- Invalid queue state transitions

---

# 29. What Would You Improve at Larger Scale?

> "If the system became large enough, I would consider extracting domains such as appointment, queue, and notification into separate services. I could introduce asynchronous messaging for notification and event-driven workflows, add an API gateway, and scale individual domains independently. But I'd make those changes based on actual bottlenecks and organizational requirements rather than adding infrastructure prematurely."

---

# 30. Questions an Interviewer May Ask Next

### Architecture
1. Why modular monolith?
2. When would you choose microservices?
3. How would you split this into microservices?
4. Where are your transaction boundaries?
5. What happens during partial failure?

### Java/Spring
6. Why Spring Boot?
7. How does @Transactional work?
8. What happens if a transactional method calls another method?
9. Why constructor injection?
10. How does Spring Security authenticate a JWT?

### Database
11. How do you prevent double booking?
12. Optimistic vs pessimistic locking?
13. What indexes would you create?
14. How do you detect N+1?
15. What isolation level would you choose?

### Redis
16. Cache-aside vs write-through?
17. How do you invalidate cache?
18. What if cache contains stale data?
19. When should you not cache?

### WebSocket
20. WebSocket vs polling?
21. How do subscriptions work?
22. What happens after reconnect?
23. How do you scale WebSocket connections?

### Queue
24. How does priority work?
25. How do you prevent starvation?
26. How do you estimate wait time?
27. How do multiple counters affect the algorithm?

### Testing
28. How would you test race conditions?
29. Unit vs integration tests?
30. Why Testcontainers?

---

# 31. Key Design Tradeoffs to Understand

| Decision | Benefit | Tradeoff |
|---|---|---|
| Modular monolith | Simpler deployment and transactions | Less independent scaling |
| PostgreSQL | Strong relational consistency | Not ideal for every high-volume transient workload |
| Redis | Fast access | Cache invalidation/staleness |
| WebSocket | Real-time updates | Connection management |
| JWT | Stateless authentication | Token lifecycle/revocation considerations |
| Testcontainers | Real integration environment | Slower tests |
| Priority queue | Supports urgent cases | Potential starvation |
| Database locking | Strong concurrency protection | Can increase contention |

---

# 32. Most Important Interview Principle

Do not say:

> "I used technology X because it's popular."

Instead explain:

> **Problem → Requirement → Design choice → Tradeoff**

Example:

> "I used WebSocket because customers need near-real-time queue updates. Polling would generate repeated requests and still introduce delay. WebSocket keeps a connection open so the server can push relevant changes. The tradeoff is that connection lifecycle and scaling need additional consideration."

This style demonstrates engineering judgment rather than tool memorization.
