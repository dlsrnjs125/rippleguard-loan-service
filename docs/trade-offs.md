# Trade-offs

## At-least-once outbox

Choice: Loan Service persists outbound events in PostgreSQL and publishes them asynchronously to Kafka.

Benefit: domain state changes and event persistence are atomic.

Cost: if Kafka publish succeeds but the service stops before marking the row `PUBLISHED`, the event can be published again after the processing lease expires.

Mitigation:

- `PROCESSING` rows have `processing_started_at`, `lease_until`, and `claimed_by`;
- stale leases are claimable again with `FOR UPDATE SKIP LOCKED`;
- consumers must use `eventId` inbox idempotency.

## H2 plus Testcontainers PostgreSQL

Choice: most service tests use H2 in PostgreSQL compatibility mode; PostgreSQL-specific migration, `jsonb`, and `SKIP LOCKED` behavior use Testcontainers.

Benefit: fast feedback for service rules, plus focused coverage for database-specific behavior.

Cost: H2 does not prove PostgreSQL semantics by itself, so PostgreSQL behavior must remain covered by Testcontainers tests.

## Evidence update adapter

Choice: expose evidence update through an internal endpoint, not a public Phase 1 REST contract endpoint.

Reason: the contracts baseline does not define a public evidence update API for Loan Service.

Cost: full external evidence E2E still depends on Governance/Web integration defining the public input path.

## Outbox claim versus aggregate locking

Outbox uses pessimistic row claiming with `FOR UPDATE SKIP LOCKED` because multiple service instances can publish outbox rows concurrently.

Loan aggregate updates use optimistic locking because concurrent commands should conflict at the aggregate boundary instead of serializing all reads.

## Topic per event type

Choice: Kafka topic names match event type names.

Benefit: aligns with Infra Phase 1 topics and lets consumers subscribe only to relevant events.

Cost: adding a new event type requires Infra topic management and service configuration.
