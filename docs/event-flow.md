# Event flow

## Application submission

1. Client calls `POST /api/v1/loan-applications`.
2. Loan Service hashes the canonical request body and checks `idempotencyKey`.
3. Loan Service stores `LoanApplication` as `SUBMITTED`.
4. Loan Service stores `FinancialSnapshot` version `snapshot-v1`.
5. Loan Service writes `loan.application.submitted.v1` to `outbox_event`.
6. Outbox publisher sends the event to Kafka and marks it `PUBLISHED`.

## Governance review

`governance.review.started.v1` moves the application from `SUBMITTED` to `UNDER_GOVERNANCE_REVIEW`. Duplicate `eventId` values are ignored.

`governance.evidence.requested.v1` moves the application to `EVIDENCE_REQUIRED`.

An application-level evidence update command stores a new `FinancialSnapshot`, increments `snapshotVersion`, emits `loan.evidence.updated.v1`, and moves the application back to `UNDER_GOVERNANCE_REVIEW`. No public REST evidence-update endpoint is exposed yet; adapters can call the application service command path.

## Decision command

`loan.decision.commanded.v1` is accepted only when:

- envelope `schemaVersion` is `1.1.0`;
- envelope and payload `applicationId` match;
- envelope and payload `evaluationRunId` match;
- `evaluationRunStatus` is `COMPLETED`;
- `assuranceResult` is `ASSURANCE_COMPLETE`;
- `finalDecision` is `APPROVE` or `REJECT`;
- `eventId`, `commandId`, and application decision are not already processed.
- the current application status is exactly `UNDER_GOVERNANCE_REVIEW`.

Successful processing transitions to `DECISION_RECEIVED`, then `FINALIZED`, and writes `loan.decision.finalized.v1` to the outbox. The service does not auto-repair missing `governance.review.started.v1` or missing evidence update events.

Phase 1 correlation rule:

- `correlationId` is the root `applicationId`.
- `evaluationRunId` separates execution attempts.
- `causationId` links finalized events to the decision command event.
