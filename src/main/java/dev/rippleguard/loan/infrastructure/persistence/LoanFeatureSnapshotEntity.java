package dev.rippleguard.loan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "loan_feature_snapshot", uniqueConstraints = {
        @UniqueConstraint(name = "uq_loan_feature_snapshot_version", columnNames = {"application_id", "snapshot_version"})
})
public class LoanFeatureSnapshotEntity {
    @Id
    @Column(name = "snapshot_id")
    private UUID snapshotId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private LoanApplicationEntity application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "financial_snapshot_id", nullable = false)
    private FinancialSnapshotEntity financialSnapshot;

    @Column(name = "snapshot_version", nullable = false, length = 64)
    private String snapshotVersion;

    @Column(name = "snapshot_schema_version", nullable = false, length = 32)
    private String snapshotSchemaVersion;

    @Column(name = "feature_schema_version", nullable = false, length = 64)
    private String featureSchemaVersion;

    @Column(name = "feature_payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String featurePayload;

    @Column(name = "feature_payload_digest", nullable = false, length = 71)
    private String featurePayloadDigest;

    @Column(name = "snapshot_reference", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String snapshotReference;

    @Column(name = "source_loan_application_version", nullable = false)
    private int sourceLoanApplicationVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanFeatureSnapshotEntity() {
    }

    public LoanFeatureSnapshotEntity(UUID snapshotId, LoanApplicationEntity application,
                                     FinancialSnapshotEntity financialSnapshot, String snapshotVersion,
                                     String snapshotSchemaVersion, String featureSchemaVersion,
                                     String featurePayload, String featurePayloadDigest,
                                     String snapshotReference, int sourceLoanApplicationVersion,
                                     Instant createdAt) {
        this.snapshotId = snapshotId;
        this.application = application;
        this.financialSnapshot = financialSnapshot;
        this.snapshotVersion = snapshotVersion;
        this.snapshotSchemaVersion = snapshotSchemaVersion;
        this.featureSchemaVersion = featureSchemaVersion;
        this.featurePayload = featurePayload;
        this.featurePayloadDigest = featurePayloadDigest;
        this.snapshotReference = snapshotReference;
        this.sourceLoanApplicationVersion = sourceLoanApplicationVersion;
        this.createdAt = createdAt;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public LoanApplicationEntity getApplication() {
        return application;
    }

    public String getSnapshotVersion() {
        return snapshotVersion;
    }

    public String getSnapshotSchemaVersion() {
        return snapshotSchemaVersion;
    }

    public String getFeatureSchemaVersion() {
        return featureSchemaVersion;
    }

    public String getFeaturePayload() {
        return featurePayload;
    }

    public String getFeaturePayloadDigest() {
        return featurePayloadDigest;
    }

    public String getSnapshotReference() {
        return snapshotReference;
    }

    public int getSourceLoanApplicationVersion() {
        return sourceLoanApplicationVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
