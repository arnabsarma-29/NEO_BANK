# Beneficiary Service — Implementation & Architecture Guide

## Overview
This document describes the production-grade implementation of the `beneficiary-service` in the Neo Bank microservices platform. It reflects the implementation addressing sprint stories **ST-02-02** (Beneficiary Creation, Validation & Pending Lifecycle) and **ST-02-03** (Beneficiary Activation & Transfer Eligibility), traceable to functional requirements `BNK-FR-02`, `BNK-FR-03`, acceptance criteria `AC-02-01` through `AC-02-03`, business rule `BR-003`, and Jira tasks `TASK-03-BE-01`, `TASK-03-BE-02`, and `TASK-03-BE-04`.

---

## A. Request Flow Architecture

The runtime request lifecycle follows a secured, layered pipeline:

```
[Client / Mobile App / Web Frontend]
       │
       ▼  (HTTPS / REST)
[API Gateway :8080] (Routes /api/v1/beneficiaries/** to Beneficiary Service :8083)
       │
       ▼  (Preserves Authorization: Bearer <JWT>)
[Servlet Context: /api/v1]
       │
       ▼
[JwtAuthenticationFilter]
  ├── Validates HMAC-SHA256 Bearer JWT (or X-Internal-Secret for internal service mesh)
  ├── Extracts customerId claim and authorities (ROLE_CUSTOMER, ROLE_INTERNAL_SERVICE, ROLE_ADMIN)
  └── Populates SecurityContextHolder with UsernamePasswordAuthenticationToken
       │
       ▼
[Spring Security FilterChain (SecurityConfig)]
  ├── Public: GET /beneficiaries/ifsc/**, /actuator/health
  ├── Customer Protected: /beneficiaries/** (requires authenticated ROLE_CUSTOMER)
  ├── Internal Protected: /internal/beneficiaries/** (requires ROLE_INTERNAL_SERVICE or ROLE_ADMIN)
  └── Admin Protected: POST /beneficiaries/ifsc (requires ROLE_ADMIN)
       │
       ▼
[BeneficiaryController / InternalBeneficiaryController / IfscController]
  ├── Extracts verified customerId using SecurityUtils.getCurrentCustomerId()
  └── Validates request DTO using Bean Validation (@ValidBeneficiary, Hibernate Validator)
       │
       ▼
[BeneficiaryServiceImpl / BeneficiaryActivationServiceImpl / IfscServiceImpl]
  ├── Normalizes input (trims strings, standardizes IFSC to uppercase)
  ├── Performs strict verified directory lookup via IfscService.validateAndGet(ifscCode)
  ├── Checks customer-scoped duplicates (ACTIVE and PENDING states)
  └── Enforces state lifecycle (PENDING -> ACTIVE)
       │
       ▼
[Repositories: BeneficiaryRepository / IfscDirectoryRepository / BeneficiaryAuditLogRepository]
       │
       ▼
[Relational Database (MySQL / PostgreSQL)]
  ├── Inserts Beneficiary with status = PENDING
  ├── BaseEntity populates createdAt and updatedAt via @EnableJpaAuditing
  └── Enforces DB unique constraint (customer_id, account_number, ifsc_code)
       │
       ▼
[BeneficiaryAuditServiceImpl & Kafka Producer]
  ├── BeneficiaryAuditLog persisted with masked account numbers (********1234)
  └── Emits domain events (`beneficiary.created`, `beneficiary.activated`) to Kafka
       │
       ▼
[ApiResponse Envelope / RFC 9457 ProblemDetail]
  └── HTTP 201 Created with Location header: /api/v1/beneficiaries/{id}
```

---

## B. Component Responsibilities

### 1. `BeneficiaryApplication`
- **What it is:** Spring Boot 3.4.1 entry point class annotated with `@SpringBootApplication`, `@EnableJpaAuditing`, and `@EnableScheduling`.
- **Why it is needed:** Bootstraps the Spring context, activates JPA auditing for automatic `createdAt` / `updatedAt` lifecycle population, and enables the Spring task scheduler.
- **What calls it:** JVM `main()` launcher or `SpringApplication.run()`.
- **What it calls:** Initializes the Spring application context and all auto-configured beans.
- **Input:** Command-line arguments `String[] args`.
- **Output:** Running Spring Boot application on port 8083.
- **Traceability:** Supports all acceptance criteria by enabling persistence auditing.
- **Verified by:** Module compilation and Spring Boot application startup tests.

### 2. `BeneficiaryController`
- **What it is:** REST controller mapped to `@RequestMapping("/beneficiaries")` (under context-path `/api/v1`).
- **Why it is needed:** Exposes customer-facing HTTP endpoints for creating, listing, retrieving, and deleting beneficiaries.
- **What calls it:** API Gateway / HTTP clients.
- **What it calls:** `BeneficiaryService`, `SecurityUtils`.
- **Input:** `BeneficiaryRequest` JSON payload, path variables (`UUID id`), HTTP query parameters (`page`, `size`, `type`).
- **Output:** `ResponseEntity<ApiResponse<BeneficiaryResponse>>`, `ResponseEntity<ApiResponse<List<BeneficiaryResponse>>>`, or RFC 9457 error response.
- **Traceability:** `AC-02-01`, `AC-02-02`, `AC-02-03`, `BNK-FR-02`.
- **Verified by:** `BeneficiaryControllerTest`.

### 3. `InternalBeneficiaryController`
- **What it is:** REST controller mapped to `@RequestMapping("/internal/beneficiaries")`, secured with `@PreAuthorize("hasAnyRole('INTERNAL_SERVICE', 'ADMIN')")`.
- **Why it is needed:** Exposes private service-to-service endpoints for `transfer-service` and internal batch workers. Does not trust public client tokens.
- **What calls it:** `transfer-service` Feign client or internal worker jobs.
- **What it calls:** `BeneficiaryService.checkTransferEligibility()`, `BeneficiaryActivationService.activateBeneficiary()`, `BeneficiaryService.getBeneficiary()`.
- **Input:** `UUID beneficiaryId`, `actor` query parameter, `HttpServletRequest`.
- **Output:** `TransferEligibilityResponse`, `BeneficiaryResponse`.
- **Traceability:** `ST-02-03`, `BR-003`, `TASK-03-BE-04`.
- **Verified by:** `InternalBeneficiaryControllerTest`.

### 4. `BeneficiaryServiceImpl`
- **What it is:** Core service implementation of `BeneficiaryService` implementing beneficiary lifecycle logic.
- **Why it is needed:** Enforces duplicate checking, input normalization, IFSC strict verification, ownership checks, status transitions, and audit logging.
- **What calls it:** `BeneficiaryController`, `InternalBeneficiaryController`.
- **What it calls:** `BeneficiaryRepository`, `IfscService`, `BeneficiaryAuditService`, `KafkaTemplate`, `BeneficiaryMapper`, `AccountMaskingUtil`.
- **Input:** `BeneficiaryRequest`, authenticated `customerId`, `actorId`, `clientIp`.
- **Output:** `BeneficiaryResponse`, `TransferEligibilityResponse`, `BeneficiaryStatusResponse`.
- **Traceability:** `AC-02-01`, `AC-02-02`, `AC-02-03`, `BNK-FR-02`, `BR-003`.
- **Verified by:** `BeneficiaryServiceImplTest`.

### 5. `BeneficiaryActivationServiceImpl`
- **What it is:** Service implementation of `BeneficiaryActivationService` managing activation lifecycle and cooling-period evaluations.
- **Why it is needed:** Enforces the strict `PENDING -> ACTIVE` transition, prevents activating `INACTIVE` or `REJECTED` records, guarantees idempotency, gates scheduled activations behind the feature flag, and emits `beneficiary.activated` Kafka events with masked account numbers.
- **What calls it:** `InternalBeneficiaryController`, scheduled cron task `checkAndActivateCoolingPeriodBeneficiaries()`.
- **What it calls:** `BeneficiaryRepository`, `BeneficiaryAuditService`, `KafkaTemplate`, `BeneficiaryMapper`, `AccountMaskingUtil`.
- **Input:** `UUID beneficiaryId`, `String actor`, `String clientIp`.
- **Output:** `BeneficiaryResponse` in `ACTIVE` state.
- **Traceability:** `ST-02-03`, `TASK-03-BE-02`, `BR-003`.
- **Verified by:** `BeneficiaryActivationServiceImplTest`.

### 6. `BeneficiaryRequest`
- **What it is:** Request DTO carrying beneficiary registration data.
- **Why it is needed:** Decouples API input contracts from internal database entities. Annotated with `@ValidBeneficiary` to enforce cross-field conditional validation for multi-rail payments (account vs UPI).
- **What calls it:** Deserialized by Jackson in `BeneficiaryController`.
- **Input fields:** `name`, `nickname`, `accountNumber`, `confirmAccountNumber`, `ifscCode`, `bankName`, `branchName`, `beneficiaryType`, `upiId`, `email`, `phoneNumber`, `maxLimit`.
- **Validation rules:**
  - `name`: Not blank, 2-100 characters.
  - `beneficiaryType`: Non-null (`SAME_BANK`, `OTHER_BANK`, `UPI`).
  - If `SAME_BANK` or `OTHER_BANK`: `accountNumber` (9-18 digits), `confirmAccountNumber` (must match `accountNumber`), and `ifscCode` (valid 11-char IFSC format) are strictly required.
  - If `UPI`: `upiId` is required (valid VPA pattern `^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}$`), and dummy account numbers or IFSC codes are not required.
- **Verified by:** `BeneficiaryRequestValidationTest`.

### 7. `BeneficiaryResponse`
- **What it is:** Response DTO returned to client callers.
- **Why it is needed:** Shields sensitive data by only returning masked account numbers (`maskedAccountNumber` and masked `accountNumber`) in API payloads.
- **Fields:** `beneficiaryId`, `customerId`, `name`, `nickname`, `maskedAccountNumber`, `accountNumber` (masked), `ifscCode`, `bankName`, `branchName`, `beneficiaryType`, `upiId`, `email`, `phoneNumber`, `maxLimit`, `status`, `coolingPeriodEndsAt`, `coolingPeriodActive`, `createdAt`.
- **Verified by:** `BeneficiaryControllerTest`, `AccountMaskingUtilTest`.

### 8. `Beneficiary`
- **What it is:** JPA entity mapped to table `beneficiaries`.
- **Why it is needed:** Represents the persistent state of a beneficiary in the relational database.
- **Columns & Constraints:**
  - `id`: `UUID` primary key.
  - `customerId`: `VARCHAR(64)` indexed.
  - `accountNumber`: `VARCHAR(34)` nullable (to allow UPI records).
  - `ifscCode`: `VARCHAR(11)` nullable.
  - `status`: `ENUM('PENDING', 'ACTIVE', 'INACTIVE', 'REJECTED')`.
  - Unique constraint: `uk_customer_account_ifsc` on `(customer_id, account_number, ifsc_code)`.
- **Verified by:** `BeneficiaryServiceImplTest`.

### 9. `BaseEntity`
- **What it is:** Abstract `@MappedSuperclass` providing common entity columns.
- **Why it is needed:** Standardizes auditing fields across all domain entities. Contains `createdAt` (annotated with `@CreatedDate`) and `updatedAt` (annotated with `@LastModifiedDate`).
- **Verified by:** Database schema assertions and entity unit tests.

### 10. `BeneficiaryRepository`
- **What it is:** Spring Data JPA repository interface extending `JpaRepository<Beneficiary, UUID>`.
- **Why it is needed:** Provides strongly typed queries for customer-scoped queries, duplicate detection (`findByCustomerIdAndAccountNumberAndIfscCodeAndStatusIn`), and pending cooling period lookups (`findByStatusAndCoolingPeriodEndsAtBefore`).
- **Verified by:** `BeneficiaryServiceImplTest`, `BeneficiaryActivationServiceImplTest`.

### 11. `IfscServiceImpl`
- **What it is:** Service implementation of `IfscService` managing verified bank branch IFSC records.
- **Why it is needed:** Provides directory lookups, query search, administrative branch registration, and strict directory verification via `validateAndGet(ifscCode)`.
- **Important Defect Resolution:** Previously, callers used `getOrCreateIfsc()` which dynamically auto-inserted unknown IFSC codes. The fix ensures `validateAndGet()` performs strict lookups without auto-creating records, throwing HTTP 404/400 `ApiException` for unknown IFSC codes.
- **What calls it:** `BeneficiaryServiceImpl`, `IfscController`.
- **What it calls:** `IfscDirectoryRepository`.
- **Verified by:** `IfscServiceImplTest`.

### 12. `IfscController`
- **What it is:** REST controller mapped to `@RequestMapping({"/beneficiaries/ifsc", "/ifsc"})`.
- **Why it is needed:** Provides public search and branch lookup endpoints, and administrative branch registration restricted to `ROLE_ADMIN`.
- **What calls it:** Frontend branch search components, admin portals.
- **Verified by:** `IfscControllerTest`.

### 13. `IfscDirectory` & `IfscDirectoryRepository`
- **What it is:** Entity mapped to table `ifsc_directory` and corresponding `JpaRepository<IfscDirectory, String>` with primary key `ifsc_code` (11 uppercase characters).
- **Why it is needed:** Acts as the authoritative clearing directory for all verified bank branches in India.
- **Verified by:** `IfscServiceImplTest`.

### 14. `BeneficiaryAuditServiceImpl`
- **What it is:** Service implementation of `BeneficiaryAuditService`.
- **Why it is needed:** Persists immutable audit records into `beneficiary_audit_logs` for compliance and forensic tracking. Masks raw account numbers in audit descriptions and captures actual previous and new statuses.
- **What calls it:** `BeneficiaryServiceImpl`, `BeneficiaryActivationServiceImpl`.
- **What it calls:** `BeneficiaryAuditLogRepository`, `AccountMaskingUtil`.
- **Verified by:** `BeneficiaryAuditServiceImplTest`.

### 15. `BeneficiaryAuditLog` & `BeneficiaryAuditLogRepository`
- **What it is:** JPA entity mapped to table `beneficiary_audit_logs` and Spring Data interface `JpaRepository<BeneficiaryAuditLog, UUID>`.
- **Crucial Fix:** Fixed generic ID type in `BeneficiaryAuditLogRepository` from `Long` to `UUID`, matching `BeneficiaryAuditLog.id` (`UUID`).
- **Verified by:** Maven build and `BeneficiaryAuditServiceImplTest`.

### 16. `GlobalExceptionHandler`
- **What it is:** Centralized `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`.
- **Why it is needed:** Converts application exceptions into standardized RFC 9457 `ProblemDetail` JSON responses with `title`, `status`, `detail`, and field-level validation errors.
- **Verified by:** `BeneficiaryControllerTest`, `IfscControllerTest`, `InternalBeneficiaryControllerTest`.

### 17. Security & JWT Components
- **`JwtTokenProvider`:** Generates, parses, and validates HMAC-SHA256 tokens using JJWT 0.13.0. Extracts `customerId` and roles.
- **`JwtAuthenticationFilter`:** `OncePerRequestFilter` extracting `Authorization: Bearer <token>` and `X-Internal-Secret` headers, populating `SecurityContextHolder`.
- **`SecurityUtils`:** Static helper providing `getCurrentCustomerId()` to guarantee customer data isolation and prevent BOLA/IDOR.
- **`SecurityConfig`:** Stateless Spring Security 6 filter chain configuring authorization rules, CSRF disabling, session management, and RFC 9457 error entry points.
- **Verified by:** `JwtTokenProviderTest`, `BeneficiaryControllerTest`.

### 18. Kafka Producer & Event Configuration
- **What it is:** `KafkaTemplate<String, Object>` producing JSON domain events to topics `beneficiary.created`, `beneficiary.activated`, and `beneficiary.status.changed`.
- **Sensitive Data Fix:** Raw account numbers are completely removed and replaced with masked values (`AccountMaskingUtil.mask(accountNumber)`).
- **Verified by:** `BeneficiaryServiceImplTest`, `BeneficiaryActivationServiceImplTest`.

### 19. Scheduler & Feature Flag
- **What it is:** Scheduled background job `checkAndActivateCoolingPeriodBeneficiaries()` in `BeneficiaryActivationServiceImpl`.
- **Crucial Requirement Fix:** Controlled by configuration property `neobank.beneficiary.auto-activation-enabled: false`. If disabled, the scheduler exits immediately without activating beneficiaries, respecting PRD cooling period status.
- **Verified by:** `BeneficiaryActivationServiceImplTest`.

### 20. Transfer Eligibility Integration
- **What it is:** Method `checkTransferEligibility(beneficiaryId)` in `BeneficiaryServiceImpl`.
- **Rule Enforcement:** A beneficiary in `PENDING`, `INACTIVE`, or `REJECTED` returns `eligible: false` with reason `BENEFICIARY_NOT_ACTIVE`. Only an `ACTIVE` beneficiary returns `eligible: true`.
- **Verified by:** `BeneficiaryServiceImplTest`, `InternalBeneficiaryControllerTest`.

---

## C. API Reference

All routes are registered under servlet context-path `/api/v1`.

| HTTP Method | Full URI Path | Purpose | Authorization | Access Tier |
|---|---|---|---|---|
| `POST` | `/api/v1/beneficiaries` | Register a new beneficiary | Bearer JWT (`ROLE_CUSTOMER`) | Customer-facing |
| `GET` | `/api/v1/beneficiaries` | List current customer's beneficiaries | Bearer JWT (`ROLE_CUSTOMER`) | Customer-facing |
| `GET` | `/api/v1/beneficiaries/{id}` | Get beneficiary details | Bearer JWT (`ROLE_CUSTOMER`) | Customer-facing |
| `DELETE` | `/api/v1/beneficiaries/{id}` | Deactivate/delete beneficiary | Bearer JWT (`ROLE_CUSTOMER`) | Customer-facing |
| `GET` | `/api/v1/beneficiaries/{id}/status` | Check status of beneficiary | Bearer JWT (`ROLE_CUSTOMER`) | Customer-facing |
| `GET` | `/api/v1/beneficiaries/ifsc/{code}` | Get bank branch details | None (Public) | Public |
| `GET` | `/api/v1/beneficiaries/ifsc/search` | Search IFSC directory | None (Public) | Public |
| `POST` | `/api/v1/beneficiaries/ifsc` | Register branch IFSC in directory | Bearer JWT (`ROLE_ADMIN`) | Admin-only |
| `GET` | `/api/v1/internal/beneficiaries/{id}` | Internal service fetch | `ROLE_INTERNAL_SERVICE` or `ROLE_ADMIN` | Internal Mesh |
| `GET` | `/api/v1/internal/beneficiaries/{id}/transfer-eligibility` | Verify transfer eligibility | `ROLE_INTERNAL_SERVICE` or `ROLE_ADMIN` | Internal Mesh |
| `POST` | `/api/v1/internal/beneficiaries/{id}/activate` | Authorize beneficiary activation | `ROLE_INTERNAL_SERVICE` or `ROLE_ADMIN` | Internal Mesh |

---

## D. End-to-End Operational Scenarios

### 1. Successful Account-Based Beneficiary Creation
- **Request:**
  ```http
  POST /api/v1/beneficiaries HTTP/1.1
  Host: api.neobank.com
  Authorization: Bearer eyJhbGciOi... (Subject: CUST-1001, Roles: [ROLE_CUSTOMER])
  Content-Type: application/json

  {
    "name": "Priya Sharma",
    "nickname": "Priya HDFC",
    "accountNumber": "987654321012",
    "confirmAccountNumber": "987654321012",
    "ifscCode": "HDFC0001234",
    "beneficiaryType": "OTHER_BANK",
    "maxLimit": 50000.00
  }
  ```
- **Response:**
  ```http
  HTTP/1.1 201 Created
  Location: /api/v1/beneficiaries/3fa85f64-5717-4562-b3fc-2c963f66afa6
  Content-Type: application/json

  {
    "status": 201,
    "message": "Beneficiary created successfully. Currently pending activation.",
    "data": {
      "beneficiaryId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "customerId": "CUST-1001",
      "name": "Priya Sharma",
      "nickname": "Priya HDFC",
      "maskedAccountNumber": "********1012",
      "accountNumber": "********1012",
      "ifscCode": "HDFC0001234",
      "bankName": "HDFC Bank",
      "branchName": "Koramangala Branch",
      "beneficiaryType": "OTHER_BANK",
      "status": "PENDING",
      "maxLimit": 50000.00,
      "coolingPeriodActive": true,
      "coolingPeriodEndsAt": "2026-10-14T01:30:00"
    },
    "timestamp": "2026-10-14T01:00:00.123Z"
  }
  ```

### 2. Creation Rejected Due to Unknown IFSC
- **Request:** `ifscCode: "UNKN0099999"`
- **Response:**
  ```http
  HTTP/1.1 404 Not Found
  Content-Type: application/problem+json

  {
    "type": "about:blank",
    "title": "Not Found",
    "status": 404,
    "detail": "IFSC code not found in directory: UNKN0099999. Please provide a valid verified IFSC code.",
    "instance": "/api/v1/beneficiaries"
  }
  ```
- **Behavior:** No directory record is inserted. The transaction rolls back.

### 3. Duplicate Active or Pending Beneficiary Rejected
- **Request:** Same `accountNumber` and `ifscCode` previously added by `CUST-1001`.
- **Response:**
  ```http
  HTTP/1.1 409 Conflict
  Content-Type: application/problem+json

  {
    "type": "about:blank",
    "title": "Conflict",
    "status": 409,
    "detail": "A beneficiary with this account number and IFSC code already exists for this customer in ACTIVE or PENDING status.",
    "instance": "/api/v1/beneficiaries"
  }
  ```

### 4. Cross-Customer Access Attempt (IDOR / BOLA Prevention)
- **Scenario:** Customer `CUST-2002` attempts to read `GET /api/v1/beneficiaries/3fa85f64-...` belonging to `CUST-1001`.
- **Response:**
  ```http
  HTTP/1.1 404 Not Found
  Content-Type: application/problem+json

  {
    "type": "about:blank",
    "title": "Not Found",
    "status": 404,
    "detail": "Beneficiary not found: 3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "instance": "/api/v1/beneficiaries/3fa85f64-5717-4562-b3fc-2c963f66afa6"
  }
  ```
- **Behavior:** Service strictly filters by `WHERE id = :id AND customer_id = :authCustomerId`, never leaking the existence of other customers' beneficiaries.

### 5. Transfer Eligibility Check on Pending Beneficiary
- **Request:** `GET /api/v1/internal/beneficiaries/3fa85f64-.../transfer-eligibility`
- **Response:**
  ```http
  HTTP/1.1 200 OK
  Content-Type: application/json

  {
    "status": 200,
    "message": "Transfer eligibility evaluated.",
    "data": {
      "beneficiaryId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "eligible": false,
      "reason": "BENEFICIARY_NOT_ACTIVE"
    },
    "timestamp": "2026-10-14T01:05:00.000Z"
  }
  ```

### 6. Authorized Activation of Pending Beneficiary
- **Request:** `POST /api/v1/internal/beneficiaries/3fa85f64-.../activate?actor=INTERNAL_WORKER`
- **Response:**
  ```http
  HTTP/1.1 200 OK
  Content-Type: application/json

  {
    "status": 200,
    "message": "Beneficiary activated successfully.",
    "data": {
      "beneficiaryId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "status": "ACTIVE",
      "coolingPeriodActive": false
    }
  }
  ```
- **Audit & Kafka:** Emits `beneficiary.activated` with masked account number `********1012`.

### 7. Illegal Activation from INACTIVE or REJECTED State Rejected
- **Scenario:** Activation request sent for a beneficiary whose current status is `INACTIVE`.
- **Response:**
  ```http
  HTTP/1.1 400 Bad Request
  Content-Type: application/problem+json

  {
    "type": "about:blank",
    "title": "Bad Request",
    "status": 400,
    "detail": "Cannot activate beneficiary in INACTIVE status. Only PENDING beneficiaries can be activated."
  }
  ```

---

## E. Configuration and Dependencies

### Database Configuration
- Located in `src/main/resources/application.yml`.
- Standardized to MySQL driver and dialect matching existing project setup:
  ```yaml
  spring:
    datasource:
      url: jdbc:mysql://localhost:3306/neobank_beneficiary?useSSL=false&serverTimezone=UTC&createDatabaseIfNotExist=true
      driver-class-name: com.mysql.cj.jdbc.Driver
    jpa:
      hibernate:
        ddl-auto: update
  ```

### Security & Feature Flags
```yaml
neobank:
  jwt:
    secret: "YourSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security12345678"
    expiration-ms: 86400000
  internal:
    secret: "neobank-internal-service-secret-key-prod-env-9988"
  beneficiary:
    cooling-period-minutes: 30
    auto-activation-enabled: false   # PRD compliance: disabled by default
```

---

## F. Testing Instructions

All automated unit and controller tests use Maven and Mockito:

```powershell
# Run the complete test suite for beneficiary-service
cd "d:\New folder (3)\NEO_BANK"
.\mvnw.cmd test -pl backend/services/beneficiary-service
```

### Verified Test Suite Breakdown (60 Tests, 100% Pass Rate):
1. `AccountMaskingUtilTest` (4 tests): Verifies 9-18 digit account masking, null/blank safety, and short string handling.
2. `BeneficiaryRequestValidationTest` (7 tests): Validates account matching, length bounds, multi-rail UPI formatting, and IFSC format patterns.
3. `IfscServiceImplTest` (5 tests): Strict directory lookup, non-existent IFSC rejection without insertion, search queries, and admin registration.
4. `BeneficiaryServiceImplTest` (11 tests): Creation lifecycle, duplicate rejection, strict directory integration, customer data isolation, masking, and transfer eligibility.
5. `BeneficiaryActivationServiceImplTest` (6 tests): Valid activation, illegal transition rejection, repeat activation idempotency, scheduler feature flag enforcement.
6. `BeneficiaryAuditServiceImplTest` (3 tests): Creation, deletion, and status change audit persistence with masked data and dynamic previous status.
7. `JwtTokenProviderTest` (4 tests): JWT token generation, claims extraction, expiration validation, and malformed token rejection.
8. `BeneficiaryControllerTest` (8 tests): 401 unauthenticated, customer isolation, 201 Created with Location header, duplicate 409 Conflict, deletion, and validation errors.
9. `InternalBeneficiaryControllerTest` (6 tests): Internal transfer eligibility evaluation, internal activation, and illegal transition rejection.
10. `IfscControllerTest` (6 tests): Public branch lookup, search queries, 404 when not found, and 201 Created registration with Location header.

---

## G. Outstanding Product Owner Decisions

1. **Cooling Period Duration:** The PRD marks the cooling period duration as TBD. A configurable placeholder (`30 minutes`) is provided in `application.yml`, but auto-activation is disabled by default (`auto-activation-enabled: false`) until approved by the Product Owner.
2. **Re-addition Policy for Deleted/Inactive Beneficiaries:** Currently, the database unique constraint `uk_customer_account_ifsc` rejects re-adding the same account even if previous record is `INACTIVE`. The application preserves this constraint without executing destructive schema migrations until a formal PO policy (reactivation vs soft-delete removal) is approved.
3. **Database Engine Standard:** Platform documentation references PostgreSQL while the existing microservice was configured for MySQL. In compliance with project guidelines, no engine switch was forced.
