package dev.rippleguard.loan.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum LoanApplicationStatus {
    DRAFT,
    SUBMITTED,
    UNDER_GOVERNANCE_REVIEW,
    EVIDENCE_REQUIRED,
    DECISION_RECEIVED,
    FINALIZED,
    CLOSED;

    private static final Map<LoanApplicationStatus, Set<LoanApplicationStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(SUBMITTED),
            SUBMITTED, EnumSet.of(UNDER_GOVERNANCE_REVIEW),
            UNDER_GOVERNANCE_REVIEW, EnumSet.of(EVIDENCE_REQUIRED, DECISION_RECEIVED),
            EVIDENCE_REQUIRED, EnumSet.of(UNDER_GOVERNANCE_REVIEW),
            DECISION_RECEIVED, EnumSet.of(FINALIZED),
            FINALIZED, EnumSet.of(CLOSED)
    );

    public boolean canTransitionTo(LoanApplicationStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }
}
