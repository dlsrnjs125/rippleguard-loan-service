package dev.rippleguard.loan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evidence_update_request")
public class EvidenceUpdateRequestEntity {
    @Id
    @Column(name = "evidence_request_event_id")
    private UUID evidenceRequestEventId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "snapshot_version", nullable = false)
    private int snapshotVersion;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected EvidenceUpdateRequestEntity() {
    }

    public EvidenceUpdateRequestEntity(UUID evidenceRequestEventId, UUID applicationId, String requestHash,
                                       int snapshotVersion, Instant processedAt) {
        this.evidenceRequestEventId = evidenceRequestEventId;
        this.applicationId = applicationId;
        this.requestHash = requestHash;
        this.snapshotVersion = snapshotVersion;
        this.processedAt = processedAt;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getApplicationId() {
        return applicationId;
    }
}
