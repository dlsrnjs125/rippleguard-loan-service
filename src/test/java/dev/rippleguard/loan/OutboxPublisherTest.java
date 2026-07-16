package dev.rippleguard.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.rippleguard.loan.domain.OutboxStatus;
import dev.rippleguard.loan.infrastructure.kafka.OutboxPublisher;
import dev.rippleguard.loan.infrastructure.kafka.OutboxProperties;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventEntity;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxPublisherTest {
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void publishesClaimedEventToEventTypeTopicAndMarksPublished() {
        OutboxEventEntity event = event("loan.application.submitted.v1");
        givenTransactionsExecuteCallbacks();
        when(outbox.findClaimable(clock.instant(), 10)).thenReturn(List.of(event));
        when(kafka.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outbox.markPublishedIfClaimed(any(), any(), any())).thenReturn(1);

        publisher().publishPending();

        verify(kafka).send(event.getEventType(), event.getAggregateId().toString(), event.getPayload());
        verify(outbox).markPublishedIfClaimed(event.getEventId(), event.getClaimToken(), clock.instant());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(event.getClaimToken()).isNotNull();
    }

    @Test
    void marksFailedAndBacksOffWhenKafkaSendFails() {
        OutboxEventEntity event = event("loan.decision.finalized.v1");
        givenTransactionsExecuteCallbacks();
        when(outbox.findClaimable(clock.instant(), 10)).thenReturn(List.of(event));
        when(kafka.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka unavailable")));
        when(outbox.markFailedIfClaimed(any(), any(), any(), any())).thenReturn(1);

        publisher().publishPending();

        verify(outbox).markFailedIfClaimed(
                event.getEventId(),
                event.getClaimToken(),
                clock.instant(),
                clock.instant().plusSeconds(5)
        );
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
    }

    @Test
    void claimSetsLeaseMetadataBeforePublishing() {
        OutboxEventEntity event = event("loan.evidence.updated.v1");
        givenTransactionsExecuteCallbacks();
        when(outbox.findClaimable(clock.instant(), 10)).thenReturn(List.of(event));
        when(kafka.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outbox.markPublishedIfClaimed(any(), any(), any())).thenReturn(1);

        publisher().publishPending();

        assertThat(event.getLeaseUntil()).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(event.getClaimToken()).isNotNull();
    }

    private OutboxPublisher publisher() {
        return new OutboxPublisher(outbox, kafka, clock, transactions, new OutboxProperties(10, 60, "test-instance"));
    }

    private void givenTransactionsExecuteCallbacks() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Object> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    private OutboxEventEntity event(String eventType) {
        return new OutboxEventEntity(
                UUID.randomUUID(),
                eventType,
                "1.1.0",
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                null,
                "{\"eventType\":\"" + eventType + "\"}",
                clock.instant()
        );
    }
}
