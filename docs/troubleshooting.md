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

## Docker build cannot find the jar

Build the application before building the image:

```bash
./mvnw package
docker build -t rippleguard-loan-service:phase1 .
```
