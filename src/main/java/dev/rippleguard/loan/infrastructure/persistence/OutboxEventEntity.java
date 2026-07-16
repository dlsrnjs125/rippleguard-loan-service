package dev.rippleguard.loan.infrastructure.persistence;

import dev.rippleguard.loan.domain.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "schema_version", nullable = false, length = 32)
    private String schemaVersion;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "causation_id")
    private UUID causationId;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(UUID eventId, String eventType, String schemaVersion, UUID aggregateId,
                             String correlationId, UUID causationId, String payload, Instant now) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.aggregateId = aggregateId;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markPublished(Instant now) {
        status = OutboxStatus.PUBLISHED;
        publishedAt = now;
        updatedAt = now;
    }

    public void markFailed(Instant now) {
        attempts++;
        status = OutboxStatus.FAILED;
        nextAttemptAt = now.plusSeconds(Math.min(300, 5L * attempts));
        updatedAt = now;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }
}
