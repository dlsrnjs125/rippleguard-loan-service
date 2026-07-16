package dev.rippleguard.loan.interfaces.rest;

import dev.rippleguard.loan.application.EvidenceUpdateCommand;
import dev.rippleguard.loan.application.FinancialSnapshotInput;
import dev.rippleguard.loan.application.LoanApplicationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/api/v1/loan-applications")
public class InternalEvidenceController {
    private final LoanApplicationService service;

    public InternalEvidenceController(LoanApplicationService service) {
        this.service = service;
    }

    @PostMapping("/{applicationId}/evidence")
    LoanApplicationResponse updateEvidence(@PathVariable UUID applicationId,
                                           @Valid @RequestBody EvidenceUpdateRequest request) {
        return service.updateEvidence(
                new EvidenceUpdateCommand(
                        applicationId,
                        request.decisionCaseId(),
                        request.causationId(),
                        request.evidenceRefs()
                ),
                snapshot(request.snapshot())
        );
    }

    private FinancialSnapshotInput snapshot(EvidenceUpdateRequest.SnapshotRequest request) {
        return new FinancialSnapshotInput(
                request.applicantReference(),
                request.requestedAmount(),
                request.currency(),
                request.incomeHistory().stream()
                        .map(income -> new FinancialSnapshotInput.MonthlyIncomeInput(
                                income.period(), income.amount(), income.sourceReference()))
                        .toList(),
                new FinancialSnapshotInput.DebtSummaryInput(
                        request.debtSummary().totalOutstandingAmount(),
                        request.debtSummary().monthlyPaymentAmount(),
                        request.debtSummary().sourceReferences()
                ),
                new FinancialSnapshotInput.DelinquencySummaryInput(
                        request.delinquencySummary().delinquencyCount(),
                        request.delinquencySummary().daysPastDueMaximum(),
                        request.delinquencySummary().sourceReferences()
                ),
                new FinancialSnapshotInput.PlatformSettlementSummaryInput(
                        request.platformSettlementSummary().period(),
                        request.platformSettlementSummary().grossSettlementAmount(),
                        request.platformSettlementSummary().sourceReferences()
                ),
                request.riskSignalReferences()
        );
    }
}
