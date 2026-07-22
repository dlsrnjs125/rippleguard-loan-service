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
                new Phase2FeatureSourceInput(
                        new DecimalFeatureSourceInput(
                                request.phase2FeatureSource().platformSettlementVolatility().value(),
                                request.phase2FeatureSource().platformSettlementVolatility().sourceReference(),
                                request.phase2FeatureSource().platformSettlementVolatility().sourceType(),
                                request.phase2FeatureSource().platformSettlementVolatility().observedAt()
                        ),
                        new IntegerFeatureSourceInput(
                                request.phase2FeatureSource().contractDuration().value(),
                                request.phase2FeatureSource().contractDuration().sourceReference(),
                                request.phase2FeatureSource().contractDuration().sourceType(),
                                request.phase2FeatureSource().contractDuration().observedAt()
                        ),
                        new BooleanFeatureSourceInput(
                                request.phase2FeatureSource().incomeDeclaration().available(),
                                request.phase2FeatureSource().incomeDeclaration().sourceReference(),
                                request.phase2FeatureSource().incomeDeclaration().sourceType(),
                                request.phase2FeatureSource().incomeDeclaration().observedAt()
                        ),
                        new IntegerFeatureSourceInput(
                                request.phase2FeatureSource().telecomDelinquency().value(),
                                request.phase2FeatureSource().telecomDelinquency().sourceReference(),
                                request.phase2FeatureSource().telecomDelinquency().sourceType(),
                                request.phase2FeatureSource().telecomDelinquency().observedAt()
                        )
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
