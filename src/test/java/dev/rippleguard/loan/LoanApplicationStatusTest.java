package dev.rippleguard.loan;

import static dev.rippleguard.loan.domain.LoanApplicationStatus.CLOSED;
import static dev.rippleguard.loan.domain.LoanApplicationStatus.DECISION_RECEIVED;
import static dev.rippleguard.loan.domain.LoanApplicationStatus.DRAFT;
import static dev.rippleguard.loan.domain.LoanApplicationStatus.EVIDENCE_REQUIRED;
import static dev.rippleguard.loan.domain.LoanApplicationStatus.FINALIZED;
import static dev.rippleguard.loan.domain.LoanApplicationStatus.SUBMITTED;
import static dev.rippleguard.loan.domain.LoanApplicationStatus.UNDER_GOVERNANCE_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rippleguard.loan.domain.LoanApplicationStatus;
import org.junit.jupiter.api.Test;

class LoanApplicationStatusTest {
    @Test
    void allowsOnlyDeclaredTransitions() {
        assertThat(DRAFT.canTransitionTo(SUBMITTED)).isTrue();
        assertThat(SUBMITTED.canTransitionTo(UNDER_GOVERNANCE_REVIEW)).isTrue();
        assertThat(UNDER_GOVERNANCE_REVIEW.canTransitionTo(EVIDENCE_REQUIRED)).isTrue();
        assertThat(UNDER_GOVERNANCE_REVIEW.canTransitionTo(DECISION_RECEIVED)).isTrue();
        assertThat(EVIDENCE_REQUIRED.canTransitionTo(UNDER_GOVERNANCE_REVIEW)).isTrue();
        assertThat(EVIDENCE_REQUIRED.canTransitionTo(DECISION_RECEIVED)).isTrue();
        assertThat(DECISION_RECEIVED.canTransitionTo(FINALIZED)).isTrue();
        assertThat(FINALIZED.canTransitionTo(CLOSED)).isTrue();
    }

    @Test
    void rejectsUndeclaredTransitions() {
        assertThat(SUBMITTED.canTransitionTo(FINALIZED)).isFalse();
        assertThat(EVIDENCE_REQUIRED.canTransitionTo(FINALIZED)).isFalse();
        assertThat(FINALIZED.canTransitionTo(DECISION_RECEIVED)).isFalse();
        for (LoanApplicationStatus status : LoanApplicationStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }
}
