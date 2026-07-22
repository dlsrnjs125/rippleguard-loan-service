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
| `platformSettlementVolatility` | `phase2FeatureSource.platformSettlementVolatility` | number | ratio | required | direct mapping from explicit Phase 2 source |
| `contractDurationMonths` | `phase2FeatureSource.contractDurationMonths` | integer | months | required | direct mapping |
| `incomeDeclarationAvailable` | `incomeHistory[]` | boolean | n/a | income history required | true when declared income source was submitted |
| `telecomPaymentDelinquencyCount` | `phase2FeatureSource.telecomPaymentDelinquencyCount` | integer | count | required | direct mapping |

Required Phase 2 feature sources must be supplied explicitly. The service does not fill unsupported features with zero, means, or fallback application state.
