package dev.rippleguard.loan.infrastructure.kafka;

import dev.rippleguard.loan.domain.OutboxStatus;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventEntity;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix = "rippleguard.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final long leaseSeconds;
    private final String instanceId;

    public OutboxPublisher(OutboxEventRepository outbox,
                           KafkaTemplate<String, String> kafka,
                           Clock clock,
                           TransactionTemplate transactions,
                           @Value("${rippleguard.outbox.batch-size}") int batchSize,
                           @Value("${rippleguard.outbox.lease-seconds}") long leaseSeconds,
                           @Value("${rippleguard.outbox.instance-id}") String instanceId) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
        this.transactions = transactions;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.instanceId = instanceId;
    }

    @Scheduled(fixedDelayString = "${OUTBOX_PUBLISHER_DELAY_MS:5000}")
    public void publishPending() {
        List<OutboxEventEntity> events = claimBatch();
        for (OutboxEventEntity event : events) {
            try {
                kafka.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload()).get();
                markPublished(event);
                log.info("Published outbox event eventId={} eventType={}", event.getEventId(), event.getEventType());
            } catch (Exception exception) {
                markFailed(event);
                log.warn("Outbox publish failed eventId={} eventType={} reason={}",
                        event.getEventId(), event.getEventType(), exception.toString());
            }
        }
    }

    private List<OutboxEventEntity> claimBatch() {
        return transactions.execute(status -> {
            Instant now = clock.instant();
            List<OutboxEventEntity> events = outbox.findClaimable(now, batchSize);
            Instant leaseUntil = now.plusSeconds(leaseSeconds);
            events.forEach(event -> event.markProcessing(now, leaseUntil, instanceId));
            return List.copyOf(events);
        });
    }

    private void markPublished(OutboxEventEntity event) {
        transactions.executeWithoutResult(status -> {
            OutboxEventEntity managed = outbox.findById(event.getEventId()).orElseThrow();
            managed.markPublished(clock.instant());
        });
    }

    private void markFailed(OutboxEventEntity event) {
        transactions.executeWithoutResult(status -> {
            OutboxEventEntity managed = outbox.findById(event.getEventId()).orElseThrow();
            managed.markFailed(clock.instant());
        });
    }
}
