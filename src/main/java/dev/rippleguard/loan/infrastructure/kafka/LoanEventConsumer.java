package dev.rippleguard.loan.infrastructure.kafka;

import dev.rippleguard.loan.application.EventEnvelope;
import dev.rippleguard.loan.application.JsonSupport;
import dev.rippleguard.loan.application.LoanApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rippleguard.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LoanEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(LoanEventConsumer.class);

    private final JsonSupport json;
    private final LoanApplicationService service;

    public LoanEventConsumer(JsonSupport json, LoanApplicationService service) {
        this.json = json;
        this.service = service;
    }

    @KafkaListener(topics = {
            "${rippleguard.kafka.topics.review-started}",
            "${rippleguard.kafka.topics.evidence-requested}",
            "${rippleguard.kafka.topics.decision-commanded}"
    })
    public void onMessage(String message) {
        EventEnvelope event = json.fromJson(message, EventEnvelope.class);
        switch (event.eventType()) {
            case "governance.review.started.v1" -> service.handleGovernanceReviewStarted(event);
            case "governance.evidence.requested.v1" -> service.handleEvidenceRequested(event);
            case "loan.decision.commanded.v1" -> service.handleDecisionCommand(event);
            default -> log.debug("Ignored eventType={} eventId={}", event.eventType(), event.eventId());
        }
    }
}
