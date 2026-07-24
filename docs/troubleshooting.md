# Troubleshooting

## Application fails at startup with schema validation errors

Check that PostgreSQL has run Flyway migration `V1__loan_core.sql` and that the application is using the expected database configured by `DB_URL`.

## Kafka health is down locally

Kafka health is enabled by default. For local database-only development, run with:

```bash
LOAN_KAFKA_ENABLED=false ./mvnw spring-boot:run
```

If Actuator Kafka health should also be disabled in a local profile, set `management.health.kafka.enabled=false`.

## Duplicate create request returns an existing application

This is expected when `idempotencyKey` and canonical payload hash match. If the payload differs, the service returns `409 Conflict`.

## Decision command is ignored

Check whether the `eventId`, `commandId`, or application already has a recorded decision in `inbox_event` or `loan_decision`. The processor is intentionally idempotent.

## Outbox event remains pending

Confirm:

- `LOAN_KAFKA_ENABLED=true`;
- `KAFKA_BOOTSTRAP_SERVERS` points to a reachable broker;
- the row has `next_attempt_at <= now`;
- repeated failures have not pushed the retry time into the future.

## Outbox event remains PROCESSING

`PROCESSING` rows are lease-based. Check:

- `lease_until`: expired rows are eligible for claim again;
- `claimed_by`: identifies the worker instance that last claimed the row;
- `processing_started_at`: shows when the attempt started.

If Kafka publish succeeded but the service died before marking the row `PUBLISHED`, the event may be published again after lease expiry. This is expected at-least-once behavior; downstream consumers must use `eventId` inbox idempotency.

## Phase 2 snapshot identity is rejected by Governance

Governance compares the immutable Feature Snapshot API identity with `snapshotReference` exactly. Do not relax that validation if Governance reports `SNAPSHOT_DIGEST_MISMATCH`.

Loan Service is the source of truth for Phase 2 Snapshot identity. PostgreSQL stores `timestamp with time zone` values at microsecond precision, while Java `Instant` can carry nanoseconds. If Loan builds `snapshotReference.snapshotCreatedAt` from the original nanosecond `Instant` but persists `created_at` through PostgreSQL, the API `createdAt` and reference identity can diverge.

Feature Snapshot materialization canonicalizes `createdAt` to PostgreSQL microsecond precision before building both the entity `created_at` value and `snapshotReference.snapshotCreatedAt`. The internal Snapshot API returns the persisted entity value and never regenerates timestamps during reads. Concurrent materialization and retries continue to use the immutable row selected by `(application_id, snapshot_version)`; a conflicting payload for an existing version is still rejected instead of overwriting the Snapshot.

Rejected alternatives:

- adding timestamp tolerance in Governance, which would weaken Event-to-Snapshot identity verification;
- omitting timestamp fields from digest or identity checks;
- rebuilding `snapshotReference` during reads, which could hide persisted identity drift.

## Docker build cannot find the jar

Build the application before building the image:

```bash
./mvnw package
docker build -t rippleguard-loan-service:phase1 .
```
