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
- decision command validation path;
- duplicate decision command handling;
- finalized outbox emission exactly once.

Tests use H2 in PostgreSQL compatibility mode with Flyway migrations. Production keeps Hibernate schema validation enabled. Test Hibernate DDL validation is disabled because H2 reports PostgreSQL `jsonb` columns as `json`, which creates a false schema-validation mismatch unrelated to runtime PostgreSQL behavior.

Recommended follow-ups:

- Add Testcontainers PostgreSQL coverage once CI runtime supports containers.
- Add embedded Kafka tests for consumer wiring and outbox publisher retry behavior.
- Add optimistic-lock race tests around concurrent command handling.
