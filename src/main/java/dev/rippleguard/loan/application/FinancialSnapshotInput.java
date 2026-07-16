package dev.rippleguard.loan.application;

import dev.rippleguard.loan.interfaces.rest.LoanApplicationCreateRequest;
import java.util.List;

public record FinancialSnapshotInput(
        String applicantReference,
        String requestedAmount,
        String currency,
        List<MonthlyIncomeInput> incomeHistory,
        DebtSummaryInput debtSummary,
        DelinquencySummaryInput delinquencySummary,
        PlatformSettlementSummaryInput platformSettlementSummary,
        List<String> riskSignalReferences
) {
    public static FinancialSnapshotInput fromCreateRequest(LoanApplicationCreateRequest request) {
        return new FinancialSnapshotInput(
                request.applicantReference(),
                request.requestedAmount(),
                request.currency(),
                request.incomeHistory().stream()
                        .map(income -> new MonthlyIncomeInput(income.period(), income.amount(), income.sourceReference()))
                        .toList(),
                new DebtSummaryInput(
                        request.debtSummary().totalOutstandingAmount(),
                        request.debtSummary().monthlyPaymentAmount(),
                        request.debtSummary().sourceReferences()
                ),
                new DelinquencySummaryInput(
                        request.delinquencySummary().delinquencyCount(),
                        request.delinquencySummary().daysPastDueMaximum(),
                        request.delinquencySummary().sourceReferences()
                ),
                new PlatformSettlementSummaryInput(
                        request.platformSettlementSummary().period(),
                        request.platformSettlementSummary().grossSettlementAmount(),
                        request.platformSettlementSummary().sourceReferences()
                ),
                request.riskSignalReferences()
        );
    }

    public record MonthlyIncomeInput(String period, String amount, String sourceReference) {
    }

    public record DebtSummaryInput(String totalOutstandingAmount, String monthlyPaymentAmount, List<String> sourceReferences) {
    }

    public record DelinquencySummaryInput(int delinquencyCount, int daysPastDueMaximum, List<String> sourceReferences) {
    }

    public record PlatformSettlementSummaryInput(String period, String grossSettlementAmount, List<String> sourceReferences) {
    }
}
