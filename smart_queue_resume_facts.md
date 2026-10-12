# Smart Queue — Resume Facts

> Status: **In Progress**
>
> This file is the source of truth for resume positioning. Do not convert planned features into past-tense claims until the implementation has been completed and verified.

---

## Project Title

**Smart Queue & Appointment Management Platform**

### Suggested Resume Status

**In Progress**

---

## Project Positioning

A backend platform for appointment and queue management across hospitals, clinics, banks, government offices, and service centers.

The platform supports:
- Customer appointments
- Walk-in queues
- Multiple service counters
- Staff queue operations
- Working hours and holidays
- Approximate wait-time estimation
- Real-time queue updates
- Role-based access
- Notifications
- Operational management

---

## Technology Stack

**Languages:** Java 21, SQL

**Backend:** Spring Boot 3.x, Spring Web, Spring Data JPA, Hibernate, Spring Security, Bean Validation

**Database:** PostgreSQL

**Caching / Fast State:** Redis

**Real-Time:** WebSocket, STOMP

**Security:** JWT, Role-Based Access Control

**Testing:** JUnit 5, Mockito, Spring Boot Test, Testcontainers

**Build / Infrastructure:** Maven, Docker, Docker Compose

**Documentation / Version Control:** OpenAPI, Swagger, Git, GitHub

---

## Architecture

**Initial Architecture:** Modular Monolith

Reason:
- Strong transactional relationship between appointment and queue workflows
- Domain still evolving
- Avoid premature distributed-system complexity
- Easier local development and testing
- Clear internal module boundaries allow future extraction

---

## Core Domain

Entities:
- User
- Role
- Organization
- Branch
- Service
- ServiceCounter
- StaffAssignment
- Appointment
- Queue
- QueueEntry
- Notification
- WorkingHour
- Holiday
- AuditLog

---

## Roles

- CUSTOMER
- STAFF
- MANAGER
- ADMIN

---

## Core Features

### Appointment
- Appointment creation
- Availability validation
- Working-hour validation
- Holiday validation
- Conflict prevention
- Cancellation
- Appointment status handling

### Queue
- Walk-in queue
- Queue entries
- Queue position
- Staff queue operations
- State transitions
- Priority handling
- Starvation avoidance
- Estimated waiting time

### Staff Operations
- Call next customer
- Start service
- Complete service
- Skip customer
- Mark no-show

### Real-Time
- WebSocket/STOMP
- Queue status updates
- Customer status changes
- Staff-facing operational updates

### Security
- JWT authentication
- Role-based authorization
- Ownership checks
- API-level enforcement

### Data
- PostgreSQL as source of truth
- Redis for justified caching/fast operational access

---

## Strong Resume Themes

When complete, the project should demonstrate:

1. **Backend API development**
2. **Spring Boot**
3. **Database design**
4. **Transaction management**
5. **Concurrency handling**
6. **Queue/state-machine design**
7. **Real-time WebSocket communication**
8. **Redis caching**
9. **Spring Security/JWT**
10. **Automated testing**
11. **Dockerized development**
12. **API documentation**

---

## Candidate Resume Bullets — Draft Only

These are intentionally written in future/in-progress form until implementation is verified.

- **Building a role-based queue and appointment management platform** using Java 21, Spring Boot, PostgreSQL, Redis, WebSocket/STOMP, and Spring Security, supporting organizations such as hospitals, clinics, banks, and service centers.

- **Designing appointment and walk-in queue workflows** with working-hour and holiday validation, conflict prevention, controlled queue state transitions, multiple service counters, and approximate wait-time estimation.

- **Implementing concurrency-safe booking and queue operations** using transactional database operations, constraints, and appropriate locking strategies to prevent conflicting appointments and duplicate queue processing.

- **Implementing real-time queue updates** using WebSocket/STOMP so customers and staff can receive near-real-time changes without continuous polling.

- **Developing role-based backend operations** for customers, staff, managers, and administrators using Spring Security, JWT authentication, validation, DTOs, and centralized exception handling.

- **Building a PostgreSQL-backed modular monolith with selective Redis usage**, supported by automated unit/integration testing, Testcontainers, Docker Compose, and OpenAPI/Swagger documentation.

---

## Do Not Claim Yet

Until verified in the codebase, do not claim:
- Production deployment
- Production traffic
- Specific throughput
- Specific latency improvement
- High availability
- Kubernetes
- Kafka
- Elasticsearch
- Cloud deployment
- External SMS/email provider integration
- Exact test coverage percentage
- Specific performance improvement
- Millions of users
- Distributed microservices

---

## Resume Readiness Checklist

Before changing the project from "In Progress" to a completed-project entry:

- [ ] Authentication implemented
- [ ] JWT security implemented
- [ ] Role authorization implemented
- [ ] Organization/branch/service model implemented
- [ ] Working hours implemented
- [ ] Holiday handling implemented
- [ ] Appointment booking implemented
- [ ] Double-booking protection implemented
- [ ] Appointment cancellation implemented
- [ ] Walk-in queue implemented
- [ ] Queue state transitions implemented
- [ ] Staff queue operations implemented
- [ ] Priority handling implemented
- [ ] Starvation handling implemented
- [ ] Wait-time estimation implemented
- [ ] WebSocket/STOMP implemented
- [ ] Redis use implemented and justified
- [ ] Notifications implemented as required
- [ ] Centralized exception handling implemented
- [ ] Database indexes reviewed
- [ ] Unit tests implemented
- [ ] Integration tests implemented
- [ ] Concurrent booking test implemented
- [ ] Testcontainers used where appropriate
- [ ] Docker Compose works
- [ ] OpenAPI/Swagger reflects actual APIs
- [ ] README completed
- [ ] End-to-end workflows manually verified

---

## Final Resume Positioning

Once complete, this project should be positioned as the candidate's **third portfolio project**, after:

1. Freelance Subscription Usage & Overage Management Platform — real client work
2. SourHub — Microservices E-Commerce Platform
3. Smart Queue & Appointment Management Platform

The project should demonstrate depth in:
- Java
- Spring Boot
- PostgreSQL
- Redis
- Security
- Concurrency
- Real-time communication
- Testing
- Backend architecture

It should not be positioned as another generic CRUD application. The strongest technical differentiators are **concurrency-safe appointment booking, queue state management, wait-time estimation, real-time WebSocket updates, and the modular-monolith architecture decision**.
