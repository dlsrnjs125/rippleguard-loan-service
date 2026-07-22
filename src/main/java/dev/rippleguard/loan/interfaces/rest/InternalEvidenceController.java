package dev.rippleguard.loan.interfaces.rest;

import dev.rippleguard.loan.application.EvidenceUpdateCommand;
import dev.rippleguard.loan.application.FinancialSnapshotInput;
import dev.rippleguard.loan.application.InternalApiProperties;
import dev.rippleguard.loan.application.LoanApplicationService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/api/v1/loan-applications")
public class InternalEvidenceController {
    private static final String SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private final LoanApplicationService service;
    private final InternalApiProperties properties;

    public InternalEvidenceController(LoanApplicationService service, InternalApiProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/{applicationId}/evidence")
    LoanApplicationResponse updateEvidence(@PathVariable UUID applicationId,
                                           @RequestHeader(name = SERVICE_TOKEN_HEADER, required = false) String serviceToken,
                                           @Valid @RequestBody EvidenceUpdateRequest request) {
        requireServiceToken(serviceToken);
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

    private void requireServiceToken(String providedToken) {
        byte[] expected = properties.serviceToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = providedToken == null ? new byte[0] : providedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service token");
        }
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
                new FinancialSnapshotInput.Phase2FeatureSourceInput(
                        request.phase2FeatureSource().platformSettlementVolatility(),
                        request.phase2FeatureSource().contractDurationMonths(),
                        request.phase2FeatureSource().telecomPaymentDelinquencyCount()
                ),
                request.riskSignalReferences()
        );
    }
}
