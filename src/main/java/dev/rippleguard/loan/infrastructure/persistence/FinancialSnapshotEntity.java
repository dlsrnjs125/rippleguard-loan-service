package dev.rippleguard.loan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "financial_snapshot", uniqueConstraints = @UniqueConstraint(
        name = "uq_financial_snapshot_version", columnNames = {"application_id", "version"}))
public class FinancialSnapshotEntity {
    @Id
    @Column(name = "snapshot_id")
    private UUID snapshotId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private LoanApplicationEntity application;

    @Column(nullable = false)
    private int version;

    @Column(name = "snapshot_version", nullable = false, length = 64)
    private String snapshotVersion;

    @Column(name = "applicant_reference", nullable = false)
    private String applicantReference;

    @Column(name = "requested_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal requestedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "debt_total_outstanding_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal debtTotalOutstandingAmount;

    @Column(name = "debt_monthly_payment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal debtMonthlyPaymentAmount;

    @Column(name = "delinquency_count", nullable = false)
    private int delinquencyCount;

    @Column(name = "days_past_due_maximum", nullable = false)
    private int daysPastDueMaximum;

    @Column(name = "settlement_period", nullable = false, length = 64)
    private String settlementPeriod;

    @Column(name = "gross_settlement_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossSettlementAmount;

    @Column(name = "debt_source_references", nullable = false, columnDefinition = "text")
    private String debtSourceReferences;

    @Column(name = "delinquency_source_references", nullable = false, columnDefinition = "text")
    private String delinquencySourceReferences;

    @Column(name = "settlement_source_references", nullable = false, columnDefinition = "text")
    private String settlementSourceReferences;

    @Column(name = "risk_signal_references", nullable = false, columnDefinition = "text")
    private String riskSignalReferences;

    @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String requestPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FinancialSnapshotEntity() {
    }

    public FinancialSnapshotEntity(UUID snapshotId, LoanApplicationEntity application, int version,
                                   String applicantReference, BigDecimal requestedAmount, String currency,
                                   BigDecimal debtTotalOutstandingAmount, BigDecimal debtMonthlyPaymentAmount,
                                   int delinquencyCount, int daysPastDueMaximum, String settlementPeriod,
                                   BigDecimal grossSettlementAmount, String debtSourceReferences,
                                   String delinquencySourceReferences, String settlementSourceReferences,
                                   String riskSignalReferences, String requestPayload, Instant createdAt) {
        this.snapshotId = snapshotId;
        this.application = application;
        this.version = version;
        this.snapshotVersion = "snapshot-v" + version;
        this.applicantReference = applicantReference;
        this.requestedAmount = requestedAmount;
        this.currency = currency;
        this.debtTotalOutstandingAmount = debtTotalOutstandingAmount;
        this.debtMonthlyPaymentAmount = debtMonthlyPaymentAmount;
        this.delinquencyCount = delinquencyCount;
        this.daysPastDueMaximum = daysPastDueMaximum;
        this.settlementPeriod = settlementPeriod;
        this.grossSettlementAmount = grossSettlementAmount;
        this.debtSourceReferences = debtSourceReferences;
        this.delinquencySourceReferences = delinquencySourceReferences;
        this.settlementSourceReferences = settlementSourceReferences;
        this.riskSignalReferences = riskSignalReferences;
        this.requestPayload = requestPayload;
        this.createdAt = createdAt;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public LoanApplicationEntity getApplication() {
        return application;
    }

    public int getVersion() {
        return version;
    }

    public String getSnapshotVersion() {
        return snapshotVersion;
    }
}
