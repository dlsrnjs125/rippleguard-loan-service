package dev.rippleguard.loan.infrastructure.persistence;

import dev.rippleguard.loan.domain.FinalDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_decision")
public class LoanDecisionEntity {
    @Id
    @Column(name = "decision_record_id")
    private UUID decisionRecordId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private LoanApplicationEntity application;

    @Column(name = "command_id", nullable = false, unique = true)
    private UUID commandId;

    @Column(name = "decision_case_id", nullable = false, length = 128)
    private String decisionCaseId;

    @Column(name = "decision_id", nullable = false)
    private UUID decisionId;

    @Column(name = "evaluation_run_id", nullable = false)
    private UUID evaluationRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_decision", nullable = false, length = 32)
    private FinalDecision finalDecision;

    @Column(name = "reason_codes", nullable = false, columnDefinition = "text")
    private String reasonCodes;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    protected LoanDecisionEntity() {
    }

    public LoanDecisionEntity(UUID decisionRecordId, LoanApplicationEntity application, UUID commandId,
                              String decisionCaseId, UUID decisionId, UUID evaluationRunId,
                              FinalDecision finalDecision, String reasonCodes, Instant issuedAt, Instant appliedAt) {
        this.decisionRecordId = decisionRecordId;
        this.application = application;
        this.commandId = commandId;
        this.decisionCaseId = decisionCaseId;
        this.decisionId = decisionId;
        this.evaluationRunId = evaluationRunId;
        this.finalDecision = finalDecision;
        this.reasonCodes = reasonCodes;
        this.issuedAt = issuedAt;
        this.appliedAt = appliedAt;
    }
}
