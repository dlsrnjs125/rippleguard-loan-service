package dev.rippleguard.loan.application;

import dev.rippleguard.loan.interfaces.rest.LoanApplicationCreateRequest;
import java.time.Instant;
import java.util.List;

public record FinancialSnapshotInput(
        String applicantReference,
        String requestedAmount,
        String currency,
        List<MonthlyIncomeInput> incomeHistory,
        DebtSummaryInput debtSummary,
        DelinquencySummaryInput delinquencySummary,
        PlatformSettlementSummaryInput platformSettlementSummary,
        Phase2FeatureSourceInput phase2FeatureSource,
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
                phase2FeatureSource(request.phase2FeatureSource()),
                request.riskSignalReferences()
        );
    }

    private static Phase2FeatureSourceInput phase2FeatureSource(
            LoanApplicationCreateRequest.Phase2FeatureSourceRequest request) {
        if (request == null) {
            return null;
        }
        return new Phase2FeatureSourceInput(
                new DecimalFeatureSourceInput(
                        request.platformSettlementVolatility().value(),
                        request.platformSettlementVolatility().sourceReference(),
                        request.platformSettlementVolatility().sourceType(),
                        request.platformSettlementVolatility().observedAt()
                ),
                new IntegerFeatureSourceInput(
                        request.contractDuration().value(),
                        request.contractDuration().sourceReference(),
                        request.contractDuration().sourceType(),
                        request.contractDuration().observedAt()
                ),
                new BooleanFeatureSourceInput(
                        request.incomeDeclaration().available(),
                        request.incomeDeclaration().sourceReference(),
                        request.incomeDeclaration().sourceType(),
                        request.incomeDeclaration().observedAt()
                ),
                new IntegerFeatureSourceInput(
                        request.telecomDelinquency().value(),
                        request.telecomDelinquency().sourceReference(),
                        request.telecomDelinquency().sourceType(),
                        request.telecomDelinquency().observedAt()
                )
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

    public record Phase2FeatureSourceInput(DecimalFeatureSourceInput platformSettlementVolatility,
                                           IntegerFeatureSourceInput contractDuration,
                                           BooleanFeatureSourceInput incomeDeclaration,
                                           IntegerFeatureSourceInput telecomDelinquency) {
    }

    public record DecimalFeatureSourceInput(String value, String sourceReference, String sourceType, Instant observedAt) {
    }

    public record IntegerFeatureSourceInput(int value, String sourceReference, String sourceType, Instant observedAt) {
    }

    public record BooleanFeatureSourceInput(boolean available, String sourceReference, String sourceType, Instant observedAt) {
    }
}
