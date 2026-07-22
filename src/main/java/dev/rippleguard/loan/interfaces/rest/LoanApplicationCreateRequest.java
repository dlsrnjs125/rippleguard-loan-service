package dev.rippleguard.loan.interfaces.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record LoanApplicationCreateRequest(
        @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
        @Pattern(regexp = "^(synthetic|masked):[A-Za-z0-9._-]+$") String applicantReference,
        @Pattern(regexp = "^(?=.*[1-9])(0|[1-9][0-9]*)(\\.[0-9]{1,2})?$") String requestedAmount,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotEmpty List<@Valid MonthlyIncomeRequest> incomeHistory,
        @Valid @NotNull DebtSummaryRequest debtSummary,
        @Valid @NotNull DelinquencySummaryRequest delinquencySummary,
        @Valid @NotNull PlatformSettlementSummaryRequest platformSettlementSummary,
        @Valid @NotNull Phase2FeatureSourceRequest phase2FeatureSource,
        @NotEmpty List<@Pattern(regexp = "^(synthetic|masked):[A-Za-z0-9._-]+$") String> riskSignalReferences,
        @NotBlank @Size(min = 8, max = 128) String idempotencyKey
) {
    public record MonthlyIncomeRequest(
            @Pattern(regexp = "^[0-9]{4}-(0[1-9]|1[0-2])$") String period,
            @Pattern(regexp = "^(0|[1-9][0-9]*)(\\.[0-9]{1,2})?$") String amount,
            @Pattern(regexp = "^(synthetic|masked):[A-Za-z0-9._-]+$") String sourceReference
    ) {
    }

    public record DebtSummaryRequest(
            @Pattern(regexp = "^(0|[1-9][0-9]*)(\\.[0-9]{1,2})?$") String totalOutstandingAmount,
            @Pattern(regexp = "^(0|[1-9][0-9]*)(\\.[0-9]{1,2})?$") String monthlyPaymentAmount,
            @NotEmpty List<@Pattern(regexp = "^(synthetic|masked):[A-Za-z0-9._-]+$") String> sourceReferences
    ) {
    }

    public record DelinquencySummaryRequest(
            @Min(0) int delinquencyCount,
            @Min(0) int daysPastDueMaximum,
            @NotEmpty List<@Pattern(regexp = "^(synthetic|masked):[A-Za-z0-9._-]+$") String> sourceReferences
    ) {
    }

    public record PlatformSettlementSummaryRequest(
            @NotBlank String period,
            @Pattern(regexp = "^(0|[1-9][0-9]*)(\\.[0-9]{1,2})?$") String grossSettlementAmount,
            @NotEmpty List<@Pattern(regexp = "^(synthetic|masked):[A-Za-z0-9._-]+$") String> sourceReferences
    ) {
    }

    public record Phase2FeatureSourceRequest(
            @Pattern(regexp = "^(0|[1-9][0-9]*)(\\.[0-9]{1,6})?$") String platformSettlementVolatility,
            @Min(1) int contractDurationMonths,
            @Min(0) int telecomPaymentDelinquencyCount
    ) {
    }
}
