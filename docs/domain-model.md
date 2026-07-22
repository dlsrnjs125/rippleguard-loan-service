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
- `DECISION_RECEIVED -> FINALIZED`
- `FINALIZED -> CLOSED`

Invalid transitions are rejected and logged without financial details.

## Stored entities

- `loan_application`: application identity, status, idempotency key, request hash, optimistic lock version.
- `financial_snapshot`: immutable versioned financial snapshot for the application request.
- `monthly_income`: monthly income entries attached to a snapshot.
- `loan_feature_snapshot`: immutable Phase 2 feature payload and snapshot reference materialized from a financial snapshot.
- `loan_decision`: final Governance command applied once per application.
- `outbox_event`: pending/published/failed outbound event rows.
- `inbox_event`: consumed event and command identifiers for idempotency.

Financial logs must use identifiers only. Raw income, debt, delinquency, and settlement values are persisted but not logged.

## Phase 2 feature snapshot

Loan Service owns versioned immutable feature snapshots for Governance and Agent Runtime evaluation. A feature snapshot is created from the submitted or evidence-updated `FinancialSnapshot` and then stored in `loan_feature_snapshot`; later reads return the stored payload and never recompute from the current application state.

Feature source mapping:

| Feature | Loan source field | Type | Unit | Null policy | Transformation |
| --- | --- | --- | --- | --- | --- |
| `annualIncome` | `incomeHistory[].amount` | number | KRW/year | income history required | monthly income mean multiplied by 12 |
| `monthlyIncomeMean` | `incomeHistory[].amount` | number | KRW/month | income history required | arithmetic mean |
| `monthlyIncomeVolatility` | `incomeHistory[].amount` | number | ratio | income history required | coefficient of variation; a single month has volatility `0` |
| `debtToIncomeRatio` | `debtSummary.monthlyPaymentAmount`, `incomeHistory[].amount` | number | ratio | positive monthly income required | monthly debt payment divided by monthly income mean |
| `existingDebtAmount` | `debtSummary.totalOutstandingAmount` | number | KRW | required | direct mapping |
| `delinquencyCount` | `delinquencySummary.delinquencyCount` | integer | count | required | direct mapping |
| `platformSettlementMonths` | `platformSettlementSummary.period` | integer | months | supported period required | `PnM` and `n-months` parse directly; `YYYY-Qn` maps to 3 months |
| `platformSettlementMean` | `platformSettlementSummary.grossSettlementAmount`, `platformSettlementSummary.period` | number | KRW/month | settlement amount and supported period required | gross settlement divided by parsed months |
| `platformSettlementVolatility` | `phase2FeatureSource.platformSettlementVolatility.value` with source reference/type/observed time | number | ratio | verified settlement history source required | direct mapping from settlement-history source summary |
| `contractDurationMonths` | `phase2FeatureSource.contractDuration.value` with source reference/type/observed time | integer | months | verified contract source required | direct mapping from contract evidence summary |
| `incomeDeclarationAvailable` | `phase2FeatureSource.incomeDeclaration.available` with source reference/type/observed time | boolean | n/a | income declaration source required | direct mapping from income declaration evidence summary |
| `telecomPaymentDelinquencyCount` | `phase2FeatureSource.telecomDelinquency.value` with source reference/type/observed time | integer | count | telecom history source required | direct mapping from telecom evidence summary |

Required Phase 2 feature sources must be supplied explicitly. The service does not fill unsupported features with zero, means, or fallback application state.

Generated feature payloads and snapshot references are validated against pinned copies of the official RippleGuard Contracts JSON Schemas before persistence. Contract validation failure rolls back the snapshot and outbox transaction.
