# Testing

Run:

```bash
./mvnw test
```

Current integration tests cover:

- application creation;
- financial snapshot version creation;
- submitted outbox atomicity;
- idempotent create replay;
- idempotency conflict for same key with different payload;
- governance review transition;
- duplicate review/evidence event handling;
- evidence requested and evidence updated transition;
- decision command validation path;
- duplicate decision command handling;
- decision command state precondition;
- conflicting decision command rejection;
- bad schema version and ID mismatch rejection;
- finalized outbox emission exactly once.
- Outbox publisher success, failure, event-type topic routing, and retry state transitions;
- PostgreSQL `FOR UPDATE SKIP LOCKED` claim and stale `PROCESSING` lease reclaim;
- pinned contract fixture deserialization;
- PostgreSQL Flyway migration through Testcontainers.

Most service integration tests use H2 in PostgreSQL compatibility mode with Flyway migrations for speed. Production keeps Hibernate schema validation enabled. Test Hibernate DDL validation is disabled for H2 because H2 reports PostgreSQL `jsonb` columns as `json`, which creates a false schema-validation mismatch unrelated to runtime PostgreSQL behavior.

`PostgresMigrationIntegrationTest` uses Testcontainers PostgreSQL to verify the actual PostgreSQL migration path.

Recommended follow-ups:

- Add embedded Kafka tests for consumer wiring.
- Add optimistic-lock race tests around concurrent command handling.
