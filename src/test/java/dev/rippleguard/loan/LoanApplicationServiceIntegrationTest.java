package dev.rippleguard.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rippleguard.loan.application.ConflictException;
import dev.rippleguard.loan.application.EventEnvelope;
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
        EventEnvelope reviewStarted = event(
                "governance.review.started.v1",
                created.applicationId(),
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                UUID.randomUUID(),
                Map.of(
                        "decisionCaseId", "case-1001",
                        "applicationId", created.applicationId().toString(),
                        "reviewStartedAt", Instant.now().toString()
                )
        );
        service.handleGovernanceReviewStarted(reviewStarted);

        UUID commandId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        Map<String, Object> commandPayload = new LinkedHashMap<>();
        commandPayload.put("commandId", commandId.toString());
        commandPayload.put("decisionCaseId", "case-1001");
        commandPayload.put("applicationId", created.applicationId().toString());
        commandPayload.put("decisionId", "40000000-0000-4000-8000-000000000001");
        commandPayload.put("evaluationRunId", "30000000-0000-4000-8000-000000000001");
        commandPayload.put("evaluationRunStatus", "COMPLETED");
        commandPayload.put("finalDecision", "APPROVE");
        commandPayload.put("assuranceResult", "ASSURANCE_COMPLETE");
        commandPayload.put("reasonCodes", List.of("GOVERNANCE_VERIFIED_PROPOSAL"));
        commandPayload.put("issuedAt", Instant.now().toString());
        commandPayload.put("idempotencyKey", "decision-command-case-1001");

        EventEnvelope command = event(
                "loan.decision.commanded.v1",
                created.applicationId(),
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                reviewStarted.eventId(),
                commandPayload
        );

        service.handleDecisionCommand(command);
        service.handleDecisionCommand(command);

        assertThat(service.get(created.applicationId()).status()).isEqualTo(LoanApplicationStatus.FINALIZED);
        assertThat(decisions.existsByCommandId(commandId)).isTrue();
        assertThat(outbox.findAll()).extracting("eventType")
                .containsExactlyInAnyOrder("loan.application.submitted.v1", "loan.decision.finalized.v1");
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
        return new EventEnvelope(
                UUID.randomUUID(),
                eventType,
                "1.1.0",
                Instant.now(),
                "governance-service",
                applicationId,
                "case-1001",
                evaluationRunId,
                applicationId.toString(),
                causationId,
                objectMapper.valueToTree(payload)
        );
    }
}
