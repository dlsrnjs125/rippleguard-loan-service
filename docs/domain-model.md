# Domain model

## Aggregate

`LoanApplication` is the root aggregate.

States:

- `DRAFT`
- `SUBMITTED`
- `UNDER_GOVERNANCE_REVIEW`
- `EVIDENCE_REQUIRED`
- `DECISION_RECEIVED`
- `FINALIZED`
- `CLOSED`

Allowed transitions:

- `DRAFT -> SUBMITTED`
- `SUBMITTED -> UNDER_GOVERNANCE_REVIEW`
- `UNDER_GOVERNANCE_REVIEW -> EVIDENCE_REQUIRED`
- `UNDER_GOVERNANCE_REVIEW -> DECISION_RECEIVED`
- `EVIDENCE_REQUIRED -> UNDER_GOVERNANCE_REVIEW`
- `EVIDENCE_REQUIRED -> DECISION_RECEIVED`
- `DECISION_RECEIVED -> FINALIZED`
- `FINALIZED -> CLOSED`

Invalid transitions are rejected and logged without financial details.

## Stored entities

- `loan_application`: application identity, status, idempotency key, request hash, optimistic lock version.
- `financial_snapshot`: immutable versioned financial snapshot for the application request.
- `monthly_income`: monthly income entries attached to a snapshot.
- `loan_decision`: final Governance command applied once per application.
- `outbox_event`: pending/published/failed outbound event rows.
- `inbox_event`: consumed event and command identifiers for idempotency.

Financial logs must use identifiers only. Raw income, debt, delinquency, and settlement values are persisted but not logged.
