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
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "rippleguard.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final String topic;
    private final int batchSize;

    public OutboxPublisher(OutboxEventRepository outbox,
                           KafkaTemplate<String, String> kafka,
                           Clock clock,
                           @Value("${rippleguard.kafka.topic}") String topic,
                           @Value("${rippleguard.outbox.batch-size}") int batchSize) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${OUTBOX_PUBLISHER_DELAY_MS:5000}")
    @Transactional
    public void publishPending() {
        Instant now = clock.instant();
        List<OutboxEventEntity> events = outbox.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAt(
                List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
                now,
                PageRequest.of(0, batchSize)
        );
        for (OutboxEventEntity event : events) {
            try {
                kafka.send(topic, event.getAggregateId().toString(), event.getPayload()).get();
                event.markPublished(clock.instant());
                log.info("Published outbox event eventId={} eventType={}", event.getEventId(), event.getEventType());
            } catch (Exception exception) {
                event.markFailed(clock.instant());
                log.warn("Outbox publish failed eventId={} eventType={}", event.getEventId(), event.getEventType(), exception);
            }
        }
    }
}
