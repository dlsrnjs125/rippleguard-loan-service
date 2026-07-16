# Architecture

RippleGuard Loan Service owns the loan application aggregate and applies Governance Service decisions to the loan lifecycle. Governance owns AI assurance and execution control; Loan Service persists the application state and emits loan-domain events.

## Components

- REST adapter: accepts `POST /api/v1/loan-applications` and `GET /api/v1/loan-applications/{applicationId}`.
- Application service: enforces idempotency, state transitions, command validation, and transaction boundaries.
- Persistence adapter: JPA repositories backed by PostgreSQL and Flyway migrations.
- Kafka consumer: consumes Governance review, evidence, and decision command events.
- Transactional outbox: stores outbound events in the same transaction as state changes. Kafka publishing is retried asynchronously.
- Inbox table: records consumed `eventId` and `commandId` to make event processing idempotent.

## Transaction boundaries

Application creation persists `loan_application`, `financial_snapshot`, `monthly_income`, and a `loan.application.submitted.v1` outbox row in one transaction.

Decision command processing validates the command envelope, transitions state, persists `loan_decision`, records the inbox row, and writes `loan.decision.finalized.v1` outbox in one transaction.

## Contract baseline

Phase 1 implementation is based on `dlsrnjs125/rippleguard-contracts@29f6c348fd93633476438ee36b3f93a3d036e165`.

Event schemas use `schemaVersion=1.1.0`; REST application create/get uses `schemaVersion=1.0.0`.
