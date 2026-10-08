# NeoBank Core Services Architecture Specification

## 1. Architecture Overview
This document defines the formal architecture, boundaries, ownership, and design decisions for the NeoBank core backend microservices. The architecture is explicitly designed to separate concerns between account management, transfer orchestration, authoritative financial mutations, and scheduled execution. 

## 2. Current Project Scope
The current implementation scope covers the following four microservices:
1. `account-service`
2. `transfer-service`
3. `ledger-service`
4. `schedule-service`

The current approved technical baseline across these services is:
- Java 17 LTS
- Spring Boot 4.1.1
- Maven
- Spring MVC (`spring-boot-starter-webmvc`)
- `spring-boot-starter-test`

Currently, no databases, JPA, Spring Security, event buses (Kafka), or service discovery layers are included.

## 3. Service Boundary Principles
- **Rule 1:** Each service owns its own domain data.
- **Rule 2:** No cross-service database foreign keys.
- **Rule 3:** Do not share JPA entities between services.
- **Rule 4:** Services communicate through APIs/events, not direct database access.
- **Rule 5:** `transfer-service` does not directly mutate financial balances.
- **Rule 6:** `schedule-service` does not directly mutate financial balances.
- **Rule 7:** `ledger-service` is the authoritative financial mutation boundary.
- **Rule 8:** `customer-service` owns customer identity/profile.
- **Rule 9:** `account-service` stores `customerId` as the logical reference to `customer-service`.
- **Rule 10:** Do not introduce additional microservices unless explicitly approved.

## 4. Customer-Service / Account-Service Relationship
`customer-service` and `account-service` represent separate business concepts. 
- A Customer has a 1-to-N relationship with Accounts.
- There must NOT be a cross-microservice database foreign key between `customer-service` and `account-service`. 
- `account-service` will logically store `customerId` (e.g., `C1001`) to map accounts to their owner. 
- `customer-service` is an external dependency/domain service and is not currently part of the four-service scaffolding.

## 5. Account-Service Responsibility
**Purpose:** Answers *"What banking accounts belong to this person?"*

**Owns:**
- Account, account status, and account type
- Customer → Account association (`customerId`)
- Account queries and ownership checks
- Account details
- Account balance/read-model access (subject to final transaction data ownership model)
- Account-related transaction read/query capability (subject to final design)

**Explicitly Does NOT Own:**
- Transfer workflow/orchestration
- Fraud decisions, OTP
- Scheduling
- Ledger execution / authoritative financial state mutation

## 6. Transfer-Service Responsibility
**Purpose:** Answers *"Should this transfer happen, and what stage is the transfer currently in?"*

**Owns:**
- Transfer request, lifecycle, and state
- Transfer validation, preparation, and confirmation
- Transfer reference and result
- OTP workflow integration and Fraud assessment integration
- Idempotency
- Transfer execution orchestration

**Explicitly Does NOT Own:**
- Direct mutation of the authoritative account financial balance. It must request execution from `ledger-service`.

## 7. Ledger-Service Responsibility
**Purpose:** Answers *"How do we safely record the debit, credit and resulting financial state?"*

**Owns:**
- Authoritative financial balance mutation
- Debit and Credit operations
- Ledger entries and transaction execution
- Atomic financial state changes
- Concurrency control, pessimistic locking, and financial consistency

**Important Concept:**
Transfer execution must be atomic (Lock source → Lock destination → Check balance → Debit → Credit → Create entries → Commit). 

## 8. Schedule-Service Responsibility
**Purpose:** Answers *"When should a transfer happen?"*

**Owns:**
- Scheduled transfer and recurring transfer instruction
- Recurrence configuration and next execution time
- Schedule lifecycle and execution history
- Due schedule detection and Scheduler worker

**Explicitly Does NOT Own:**
- Financial balances or ledger debit/credit operations. It must trigger `transfer-service` to orchestrate the actual transfer.

## 9. Service Ownership Matrix

| Domain/Capability | Owner |
|-------------------|-------|
| Customer identity | `customer-service` |
| Account | `account-service` |
| Customer-account association | `account-service` |
| Transfer workflow | `transfer-service` |
| Transfer state | `transfer-service` |
| Transfer idempotency | `transfer-service` |
| Financial mutation | `ledger-service` |
| Debit | `ledger-service` |
| Credit | `ledger-service` |
| Ledger entries | `ledger-service` |
| Account locking for transfer execution | `ledger-service` |
| Scheduled transfer | `schedule-service` |
| Recurrence | `schedule-service` |
| Next execution | `schedule-service` |
| Schedule execution history | `schedule-service` |

## 10. Service-to-Service Dependency Matrix

| Service | Depends On | Reason / Information Required | Sync/Async | Fallback if Unavailable |
|---------|------------|-------------------------------|------------|-------------------------|
| `account-service` | `customer-service` | Validate customer existence / fetch profile | *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |
| `transfer-service` | `account-service` | Validate account exists, fetch metadata | *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |
| `transfer-service` | `beneficiary-service` | Validate external/saved beneficiaries | *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |
| `transfer-service` | `auth/security` | Validate session and security context | *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |
| `transfer-service` | `fraud/AI` | Perform fraud assessment | *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |
| `transfer-service` | `ledger-service` | Execute authoritative financial mutations | *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |
| `ledger-service` | `account-service` | *(Only if required for metadata)* Account validation may happen prior to ledger execution | *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |
| `schedule-service` | `account-service` | Validate account status for scheduled tasks | *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |
| `schedule-service` | `beneficiary-service`| Validate beneficiary status for scheduled tasks| *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |
| `schedule-service` | `transfer-service` | Trigger transfer workflow when due | *ARCHITECTURE DECISION REQUIRED* | *ARCHITECTURE DECISION REQUIRED* |

## 11. API Responsibility Matrix
**transfer-service APIs:**
- Public: `POST /api/v1/transfers/prepare`, `GET /api/v1/transfers/{transferId}`, `GET /api/v1/transfers/{transferId}/status`, `POST /api/v1/transfers/{transferId}/confirm`, `POST /api/v1/transfers/{transferId}/otp/challenge`, `POST /api/v1/transfers/{transferId}/otp/verify`, `POST /api/v1/transfers/{transferId}/additional-confirmation`, `POST /api/v1/transfers/{transferId}/cancel`, `GET /api/v1/transfers`
- Internal: `POST /internal/transfers/validate`, `POST /internal/transfers/{transferId}/execute`, `POST /internal/transfers/{transferId}/fraud-assessment`

**ledger-service APIs:**
- Internal: `POST /internal/ledger/transfers`, `GET /internal/ledger/accounts/{accountId}/balance`, `GET /internal/ledger/transfers/{transferId}/entries`, `GET /internal/ledger/transfers/{transferId}/status`
- *PROPOSED / REQUIRES ARCHITECTURE DECISION:* `POST /internal/ledger/transfers/{transferId}/reverse`

**schedule-service APIs:**
- Public: `POST /api/v1/schedules`, `GET /api/v1/schedules`, `GET /api/v1/schedules/{id}`, `GET /api/v1/schedules/{id}/status`, `POST /api/v1/schedules/{id}/confirm`, `POST /api/v1/schedules/{id}/pause`, `POST /api/v1/schedules/{id}/resume`, `POST /api/v1/schedules/{id}/cancel`, `PATCH /api/v1/schedules/{id}`, `GET /api/v1/schedules/{id}/next-execution`, `GET /api/v1/schedules/{id}/executions`
- Internal: `GET /internal/schedules/due`, `POST /internal/schedules/{id}/execute`, `POST /internal/schedules/{id}/calculate-next-run`

## 12. EPIC-to-Service Mapping
**EPIC-02: Customer Account Management & Transaction Ingestion**
- Primary: `account-service`
- Dependency: `customer-service`
- Potential Support: `ledger-service` (for authoritative financial data)

**EPIC-06: Simulated Fund Transfers**
- Primary: `transfer-service`, `ledger-service`
- Dependencies: `account-service`, `beneficiary-service`, `auth-service`, fraud/AI capability

**EPIC-07: Scheduled & Recurring Transfer Engine**
- Primary: `schedule-service`
- Dependencies: `transfer-service`, `account-service`, `beneficiary-service`

## 13. Data Ownership Principles
Each microservice is the sole owner of its underlying datastore and schema. Other services must not read or write directly to another service's database. Redundant data must be handled through eventual consistency or read-models where appropriate, synchronized via events or APIs.

## 14. Financial Mutation Boundary
`ledger-service` acts as the strict and only authoritative financial mutation boundary. No other service (`transfer-service`, `schedule-service`, `account-service`) may directly alter financial balances. Any debit or credit must be routed as a request to the `ledger-service`.

## 15. Transfer Flow
**Customer → Transfer Service → Validate → Prepare → Confirm → OTP → Fraud Assessment → Execute (via Ledger Service) → Result**

## 16. Scheduled Transfer Flow
**Customer → Schedule Service → Create/Validate/Store → Wait until due → Scheduler Worker detects due → Call Transfer Service → Transfer orchestrates Ledger → Update execution result & Calculate next occurrence**

## 17. Account → Customer Mapping
Handled purely via application-level logical relationships. The `account-service` stores a `customerId` identifier. It does not enforce a database-level foreign key constraint to the `customer-service`.

## 18. Authentication/Authorization Dependency
Authentication and security contexts are treated as an upstream/external dependency that `transfer-service` and other services must validate against before processing protected operations.

## 19. Beneficiary Dependency
`transfer-service` and `schedule-service` rely on `beneficiary-service` to validate the existence and integrity of external or saved beneficiaries before initiating a transfer workflow.

## 20. Fraud/AI Dependency
Fraud assessments are orchestrated by the `transfer-service` as an external capability prior to requesting ledger execution.

## 21. Future Infrastructure Dependencies
While currently omitted from the scaffolding, future phases will introduce:
- Relational Databases (PostgreSQL/MySQL) via Spring Data JPA / Hibernate
- Database Migrations (Flyway/Liquibase)
- Message Brokers (Kafka) for async communication
- Caching/Locking (Redis)
- Service Discovery/Routing (Eureka, Consul, API Gateway)
- Resilience Patterns (Resilience4j, OpenFeign)
- Security (Spring Security, JWT, OAuth2)

## 22. Explicit Non-Responsibilities
- `transfer-service` does NOT mutate balances.
- `schedule-service` does NOT mutate balances.
- `account-service` does NOT execute transfer workflows.
- Core services do NOT directly query each other's databases.

## 23. Architecture Decisions
- Segregation of workflow (`transfer-service`) and financial state (`ledger-service`).
- Separation of `customer-service` and `account-service`.
- Enforcement of strict atomic financial execution inside `ledger-service`.

## 24. Open Architecture Decisions
The following items are explicitly marked as **OPEN / REQUIRES DECISION**:
1. Exact transaction read-model ownership between `account-service` and `ledger-service`.
2. Whether `account-service` balance is a read model or direct financial value retrieved dynamically from ledger.
3. Exact `customer-service` API contract.
4. Synchronous vs event-driven propagation of financial transaction updates.
5. Exact idempotency storage location (e.g., Redis, DB tables).
6. Exact schedule concurrency/distributed-locking strategy for scheduler workers.
7. Exact retry strategy between `schedule-service` and `transfer-service`.
8. Exact fraud-service/AI integration contract.
9. Exact OTP ownership/integration workflow.
10. Whether `POST /internal/ledger/transfers/{transferId}/reverse` is required in the ledger.
11. Whether a full double-entry ledger is required or just atomic debit/credit.

## 25. Risks / Potential Conflicts
- Potential network latency and failure cascades due to synchronous REST dependencies across core workflows (e.g., Transfer → Ledger).
- Data inconsistency if read-models in `account-service` fall out of sync with `ledger-service` events.
- Distributed locking failures in `schedule-service` could lead to duplicate transfer executions if idempotency is not strictly enforced in `transfer-service`.
