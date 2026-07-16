package dev.rippleguard.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rippleguard.loan.application.ConflictException;
import dev.rippleguard.loan.application.EvidenceUpdateCommand;
import dev.rippleguard.loan.application.EventEnvelope;
import dev.rippleguard.loan.application.InvalidStateTransitionException;
import dev.rippleguard.loan.application.LoanApplicationService;
import dev.rippleguard.loan.domain.LoanApplicationStatus;
import dev.rippleguard.loan.infrastructure.persistence.LoanApplicationRepository;
import dev.rippleguard.loan.infrastructure.persistence.LoanDecisionRepository;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventRepository;
import dev.rippleguard.loan.interfaces.rest.LoanApplicationCreateRequest;
import dev.rippleguard.loan.interfaces.rest.LoanApplicationResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "debug=false")
@Transactional
class LoanApplicationServiceIntegrationTest {
    @Autowired
    LoanApplicationService service;

    @Autowired
    LoanApplicationRepository applications;

    @Autowired
    OutboxEventRepository outbox;

    @Autowired
    LoanDecisionRepository decisions;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createsApplicationSnapshotAndSubmittedOutboxAtomically() {
        LoanApplicationResponse response = service.create(validRequest("loan-create-001", "25000000.00"));

        assertThat(response.status()).isEqualTo(LoanApplicationStatus.SUBMITTED);
        assertThat(response.snapshotVersion()).isEqualTo("snapshot-v1");
        assertThat(applications.findById(response.applicationId())).isPresent();
        assertThat(outbox.findAll()).hasSize(1);
        assertThat(outbox.findAll().get(0).getEventType()).isEqualTo("loan.application.submitted.v1");
    }

    @Test
    void returnsExistingApplicationForSameIdempotencyPayload() {
        LoanApplicationResponse first = service.create(validRequest("loan-create-002", "25000000.00"));
        LoanApplicationResponse second = service.create(validRequest("loan-create-002", "25000000.00"));

        assertThat(second.applicationId()).isEqualTo(first.applicationId());
        assertThat(applications.count()).isEqualTo(1);
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void rejectsDifferentPayloadForSameIdempotencyKey() {
        service.create(validRequest("loan-create-003", "25000000.00"));

        assertThatThrownBy(() -> service.create(validRequest("loan-create-003", "30000000.00")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void appliesDecisionCommandOnceAndCreatesFinalizedOutbox() throws Exception {
        LoanApplicationResponse created = service.create(validRequest("loan-create-004", "25000000.00"));
        EventEnvelope reviewStarted = reviewStarted(created.applicationId());
        service.handleGovernanceReviewStarted(reviewStarted);

        UUID commandId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        EventEnvelope command = decisionCommand(created.applicationId(), commandId, "APPROVE", reviewStarted.eventId());

        service.handleDecisionCommand(command);
        service.handleDecisionCommand(command);

        assertThat(service.get(created.applicationId()).status()).isEqualTo(LoanApplicationStatus.FINALIZED);
        assertThat(decisions.existsByCommandId(commandId)).isTrue();
        assertThat(outbox.findAll()).extracting("eventType")
                .containsExactlyInAnyOrder("loan.application.submitted.v1", "loan.decision.finalized.v1");
    }

    @Test
    void decisionCommandRequiresReviewStartedState() throws Exception {
        LoanApplicationResponse created = service.create(validRequest("loan-create-005", "25000000.00"));
        EventEnvelope command = decisionCommand(
                created.applicationId(),
                UUID.fromString("50000000-0000-4000-8000-000000000005"),
                "APPROVE",
                UUID.randomUUID()
        );

        assertThatThrownBy(() -> service.handleDecisionCommand(command))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("UNDER_GOVERNANCE_REVIEW");
        assertThat(service.get(created.applicationId()).status()).isEqualTo(LoanApplicationStatus.SUBMITTED);
    }

    @Test
    void evidenceRequestedAndUpdatedReturnsToReviewAndEmitsEvidenceUpdated() throws Exception {
        LoanApplicationResponse created = service.create(validRequest("loan-create-006", "25000000.00"));
        service.handleGovernanceReviewStarted(reviewStarted(created.applicationId()));

        EventEnvelope evidenceRequested = event(
                "governance.evidence.requested.v1",
                "governance-service",
                created.applicationId(),
                UUID.fromString("30000000-0000-4000-8000-000000000006"),
                UUID.randomUUID(),
                Map.of(
                        "requestId", "20000000-0000-4000-8000-000000000006",
                        "decisionCaseId", "case-1001",
                        "applicationId", created.applicationId().toString(),
                        "evaluationRunId", "30000000-0000-4000-8000-000000000006",
                        "requestedEvidenceTypes", List.of("TRANSACTION_EXPLANATION"),
                        "reasonCodes", List.of("UNCONFIRMED_TRANSACTION_ANOMALY")
                )
        );
        service.handleEvidenceRequested(evidenceRequested);
        service.handleEvidenceRequested(evidenceRequested);

        assertThat(service.get(created.applicationId()).status()).isEqualTo(LoanApplicationStatus.EVIDENCE_REQUIRED);

        LoanApplicationResponse updated = service.updateEvidence(
                new EvidenceUpdateCommand(
                        created.applicationId(),
                        "case-1001",
                        evidenceRequested.eventId(),
                        List.of("evidence://transaction-explanation/1")
                ),
                validRequest("loan-evidence-006", "25000000.00")
        );

        assertThat(updated.status()).isEqualTo(LoanApplicationStatus.UNDER_GOVERNANCE_REVIEW);
        assertThat(updated.snapshotVersion()).isEqualTo("snapshot-v2");
        assertThat(outbox.findAll()).extracting("eventType")
                .contains("loan.evidence.updated.v1");
    }

    @Test
    void evidenceRequestRequiresReviewStartedState() throws Exception {
        LoanApplicationResponse created = service.create(validRequest("loan-create-009", "25000000.00"));
        EventEnvelope evidenceRequested = event(
                "governance.evidence.requested.v1",
                created.applicationId(),
                UUID.fromString("30000000-0000-4000-8000-000000000009"),
                UUID.randomUUID(),
                Map.of(
                        "requestId", "20000000-0000-4000-8000-000000000009",
                        "decisionCaseId", "case-1001",
                        "applicationId", created.applicationId().toString(),
                        "requestedEvidenceTypes", List.of("TRANSACTION_EXPLANATION"),
                        "reasonCodes", List.of("UNCONFIRMED_TRANSACTION_ANOMALY")
                )
        );

        assertThatThrownBy(() -> service.handleEvidenceRequested(evidenceRequested))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("UNDER_GOVERNANCE_REVIEW");
    }

    @Test
    void evidenceUpdateRequiresEvidenceRequiredState() {
        LoanApplicationResponse created = service.create(validRequest("loan-create-010", "25000000.00"));

        assertThatThrownBy(() -> service.updateEvidence(
                new EvidenceUpdateCommand(
                        created.applicationId(),
                        "case-1001",
                        UUID.randomUUID(),
                        List.of("evidence://transaction-explanation/1")
                ),
                validRequest("loan-evidence-010", "25000000.00")
        )).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectsBadSchemaVersionAndEnvelopePayloadMismatch() throws Exception {
        LoanApplicationResponse created = service.create(validRequest("loan-create-007", "25000000.00"));
        service.handleGovernanceReviewStarted(reviewStarted(created.applicationId()));

        EventEnvelope badVersion = new EventEnvelope(
                UUID.randomUUID(),
                "loan.decision.commanded.v1",
                "1.0.0",
                Instant.now(),
                "governance-service",
                created.applicationId(),
                "case-1001",
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                created.applicationId().toString(),
                UUID.randomUUID(),
                objectMapper.valueToTree(commandPayload(
                        UUID.fromString("50000000-0000-4000-8000-000000000007"),
                        created.applicationId(),
                        "APPROVE"
                ))
        );
        assertThatThrownBy(() -> service.handleDecisionCommand(badVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported event contract");

        Map<String, Object> mismatchedPayload = commandPayload(
                UUID.fromString("50000000-0000-4000-8000-000000000008"),
                UUID.randomUUID(),
                "APPROVE"
        );
        EventEnvelope mismatch = event(
                "loan.decision.commanded.v1",
                created.applicationId(),
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                UUID.randomUUID(),
                mismatchedPayload
        );
        assertThatThrownBy(() -> service.handleDecisionCommand(mismatch))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDifferentDecisionCommandAfterFinalization() throws Exception {
        LoanApplicationResponse created = service.create(validRequest("loan-create-008", "25000000.00"));
        EventEnvelope reviewStarted = reviewStarted(created.applicationId());
        service.handleGovernanceReviewStarted(reviewStarted);
        service.handleDecisionCommand(decisionCommand(
                created.applicationId(),
                UUID.fromString("50000000-0000-4000-8000-000000000081"),
                "APPROVE",
                reviewStarted.eventId()
        ));

        EventEnvelope conflictingCommand = decisionCommand(
                created.applicationId(),
                UUID.fromString("50000000-0000-4000-8000-000000000082"),
                "REJECT",
                reviewStarted.eventId()
        );

        assertThatThrownBy(() -> service.handleDecisionCommand(conflictingCommand))
                .isInstanceOf(ConflictException.class);
    }

    private LoanApplicationCreateRequest validRequest(String idempotencyKey, String requestedAmount) {
        return new LoanApplicationCreateRequest(
                "1.0.0",
                "synthetic:applicant-001",
                requestedAmount,
                "KRW",
                List.of(new LoanApplicationCreateRequest.MonthlyIncomeRequest("2026-06", "5200000.00", "masked:income-2026-06")),
                new LoanApplicationCreateRequest.DebtSummaryRequest("4000000.00", "350000.00", List.of("masked:debt-summary-001")),
                new LoanApplicationCreateRequest.DelinquencySummaryRequest(0, 0, List.of("masked:delinquency-001")),
                new LoanApplicationCreateRequest.PlatformSettlementSummaryRequest("2026-Q2", "18000000.00", List.of("synthetic:settlement-q2")),
                List.of("synthetic:risk-signal-001"),
                idempotencyKey
        );
    }

    private EventEnvelope event(String eventType, UUID applicationId, UUID evaluationRunId, UUID causationId,
                                Map<String, Object> payload) throws Exception {
        return event(eventType, "governance-service", applicationId, evaluationRunId, causationId, payload);
    }

    private EventEnvelope event(String eventType, String producer, UUID applicationId, UUID evaluationRunId, UUID causationId,
                                Map<String, Object> payload) throws Exception {
        return new EventEnvelope(
                UUID.randomUUID(),
                eventType,
                "1.1.0",
                Instant.now(),
                producer,
                applicationId,
                "case-1001",
                evaluationRunId,
                applicationId.toString(),
                causationId,
                objectMapper.valueToTree(payload)
        );
    }

    private EventEnvelope reviewStarted(UUID applicationId) throws Exception {
        return event(
                "governance.review.started.v1",
                applicationId,
                null,
                UUID.randomUUID(),
                Map.of(
                        "decisionCaseId", "case-1001",
                        "applicationId", applicationId.toString(),
                        "reviewStartedAt", Instant.now().toString()
                )
        );
    }

    private EventEnvelope decisionCommand(UUID applicationId, UUID commandId, String finalDecision, UUID causationId) throws Exception {
        return event(
                "loan.decision.commanded.v1",
                applicationId,
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                causationId,
                commandPayload(commandId, applicationId, finalDecision)
        );
    }

    private Map<String, Object> commandPayload(UUID commandId, UUID applicationId, String finalDecision) {
        Map<String, Object> commandPayload = new LinkedHashMap<>();
        commandPayload.put("commandId", commandId.toString());
        commandPayload.put("decisionCaseId", "case-1001");
        commandPayload.put("applicationId", applicationId.toString());
        commandPayload.put("decisionId", "40000000-0000-4000-8000-000000000001");
        commandPayload.put("evaluationRunId", "30000000-0000-4000-8000-000000000001");
        commandPayload.put("evaluationRunStatus", "COMPLETED");
        commandPayload.put("finalDecision", finalDecision);
        commandPayload.put("assuranceResult", "ASSURANCE_COMPLETE");
        commandPayload.put("reasonCodes", List.of("GOVERNANCE_VERIFIED_PROPOSAL"));
        commandPayload.put("issuedAt", Instant.now().toString());
        commandPayload.put("idempotencyKey", "decision-command-case-1001-" + commandId);
        return commandPayload;
    }
}
