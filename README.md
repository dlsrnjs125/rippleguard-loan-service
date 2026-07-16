# RippleGuard Loan Service

대출 신청의 생명주기와 최종 대출 상태를 관리하는 핵심 도메인 서비스입니다.

## Phase 1 역할

- 대출 신청 접수와 조회
- 신청 시점의 금융 스냅샷 버전 저장
- Governance Service가 발행한 review/decision 이벤트 수신
- Loan 상태 전이와 최종 의사결정 반영
- transactional outbox 기반 도메인 이벤트 발행

AI 판단 검증과 실행 통제는 Governance Service가 담당합니다.

## Contract baseline

- Contracts repository: `dlsrnjs125/rippleguard-contracts`
- Baseline commit: `29f6c348fd93633476438ee36b3f93a3d036e165`
- Phase 1 event schema version: `1.1.0`
- REST create/get schema version: `1.0.0`

## API

- `POST /api/v1/loan-applications`
  - request `schemaVersion`: `1.0.0`
  - `idempotencyKey` 기준 중복 요청 방지
  - 같은 key + 같은 payload는 기존 application 반환
  - 같은 key + 다른 payload는 `409 Conflict`
- `GET /api/v1/loan-applications/{applicationId}`

## Events

Published through `outbox_event`:

- `loan.application.submitted.v1` / `schemaVersion=1.1.0`
- `loan.decision.finalized.v1` / `schemaVersion=1.1.0`

Consumed idempotently through `inbox_event`:

- `governance.review.started.v1`
- `governance.evidence.requested.v1`
- `loan.decision.commanded.v1`

Phase 1 correlation policy is `correlationId == applicationId`; execution scope is separated by `evaluationRunId` and `causationId`.

## Runtime configuration

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/rippleguard_loan` |
| `DB_USERNAME` | `rippleguard` |
| `DB_PASSWORD` | `rippleguard` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `KAFKA_CONSUMER_GROUP` | `rippleguard-loan-service` |
| `LOAN_KAFKA_ENABLED` | `true` |
| `LOAN_KAFKA_TOPIC` | `rippleguard.events` |
| `OUTBOX_BATCH_SIZE` | `50` |

## Run and test

```bash
./mvnw test
./mvnw spring-boot:run
./mvnw package
docker build -t rippleguard-loan-service:phase1 .
```

Health endpoints:

- `/actuator/health/liveness`
- `/actuator/health/readiness`

## Documents

- [Architecture](docs/architecture.md)
- [Domain model](docs/domain-model.md)
- [Event flow](docs/event-flow.md)
- [Testing](docs/testing.md)
- [Troubleshooting](docs/troubleshooting.md)
