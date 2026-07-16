package dev.rippleguard.loan.infrastructure.persistence;

import dev.rippleguard.loan.domain.LoanApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_application")
public class LoanApplicationEntity {
    @Id
    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private LoanApplicationStatus status;

    @Column(name = "snapshot_version", nullable = false)
    private int snapshotVersion;

    @Column(name = "requested_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal requestedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "applicant_reference", nullable = false)
    private String applicantReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected LoanApplicationEntity() {
    }

    public LoanApplicationEntity(UUID applicationId, String idempotencyKey, String requestHash,
                                 BigDecimal requestedAmount, String currency, String applicantReference,
                                 Instant now) {
        this.applicationId = applicationId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = LoanApplicationStatus.SUBMITTED;
        this.snapshotVersion = 1;
        this.requestedAmount = requestedAmount;
        this.currency = currency;
        this.applicantReference = applicantReference;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void transitionTo(LoanApplicationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Transition denied: " + status + " -> " + target);
        }
        status = target;
        updatedAt = now;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public LoanApplicationStatus getStatus() {
        return status;
    }

    public int getSnapshotVersion() {
        return snapshotVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
