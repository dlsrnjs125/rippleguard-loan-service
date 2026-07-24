package dev.rippleguard.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rippleguard.loan.application.ContractSchemaValidator;
import dev.rippleguard.loan.application.ConflictException;
import dev.rippleguard.loan.application.EvidenceUpdateCommand;
import dev.rippleguard.loan.application.EventEnvelope;
import dev.rippleguard.loan.application.FinancialSnapshotInput;
import dev.rippleguard.loan.application.InvalidStateTransitionException;
import dev.rippleguard.loan.application.LoanApplicationService;
import dev.rippleguard.loan.application.Phase2FeatureSnapshotService;
import dev.rippleguard.loan.domain.LoanApplicationStatus;
import dev.rippleguard.loan.infrastructure.persistence.LoanFeatureSnapshotRepository;
import dev.rippleguard.loan.infrastructure.persistence.LoanApplicationRepository;
import dev.rippleguard.loan.infrastructure.persistence.LoanDecisionRepository;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventRepository;
import dev.rippleguard.loan.infrastructure.persistence.InboxEventEntity;
import dev.rippleguard.loan.infrastructure.persistence.InboxEventRepository;
import dev.rippleguard.loan.interfaces.rest.LoanApplicationCreateRequest;
import dev.rippleguard.loan.interfaces.rest.LoanApplicationResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "debug=false")
class LoanApplicationServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("rippleguard_loan")
            .withUsername("rippleguard_loan")
            .withPassword("rippleguard_loan");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    LoanApplicationService service;

    @Autowired
    Phase2FeatureSnapshotService featureSnapshots;

    @Autowired
    ContractSchemaValidator contracts;

    @Autowired
    LoanApplicationRepository applications;

    @Autowired
    LoanFeatureSnapshotRepository featureSnapshotRepository;

    @Autowired
    OutboxEventRepository outbox;

    @Autowired
    LoanDecisionRepository decisions;

    @Autowired
    InboxEventRepository inbox;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from evidence_update_request");
        jdbc.update("delete from loan_feature_snapshot");
        jdbc.update("delete from monthly_income");
        jdbc.update("delete from financial_snapshot");
        jdbc.update("delete from loan_decision");
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from inbox_event");
        jdbc.update("delete from loan_application");
    }

    @Test
    void createsApplicationSnapshotAndSubmittedOutboxAtomically() {
        LoanApplicationResponse response = service.create(validRequest("loan-create-001", "25000000.00"));

        assertThat(response.status()).isEqualTo(LoanApplicationStatus.SUBMITTED);
        assertThat(response.snapshotVersion()).isEqualTo("snapshot-v1");
        assertThat(applications.findById(response.applicationId())).isPresent();
        assertThat(featureSnapshotRepository.findByApplicationApplicationIdAndSnapshotVersion(
                response.applicationId(), "snapshot-v1")).isPresent();
        assertThat(outbox.findAll()).hasSize(1);
        assertThat(outbox.findAll().get(0).getEventType()).isEqualTo("loan.application.submitted.v1");
    }

    @Test
    void preservesPublicV1CreateRequestWithoutPhase2FeatureSource() {
        LoanApplicationResponse response = service.create(validRequestWithoutPhase2("loan-create-v1-compat"));

        assertThat(response.status()).isEqualTo(LoanApplicationStatus.SUBMITTED);
        assertThat(applications.findById(response.applicationId())).isPresent();
        assertThat(featureSnapshotRepository.count()).isZero();
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void returnsStoredPhase2FeatureSnapshotWithoutRecomputingApplicationState() throws Exception {
        LoanApplicationResponse created = service.create(validRequest("loan-create-feature-001", "25000000.00"));
        var firstSnapshot = featureSnapshots.get(created.applicationId(), "snapshot-v1");

        assertThat(firstSnapshot.snapshotVersion()).isEqualTo("snapshot-v1");
        assertThat(firstSnapshot.featurePayloadDigest()).startsWith("sha256:");
        assertThat(firstSnapshot.featurePayload())
                .containsEntry("featurePayloadDigest", firstSnapshot.featurePayloadDigest());
        contracts.validate(ContractSchemaValidator.FEATURE_PAYLOAD_SCHEMA, firstSnapshot.featurePayload());
        contracts.validate(ContractSchemaValidator.SNAPSHOT_REFERENCE_SCHEMA, firstSnapshot.snapshotReference());
        assertThat(firstSnapshot.snapshotReference())
                .containsEntry("snapshotCreatedAt", firstSnapshot.createdAt().toString())
                .containsEntry("snapshotDigest", firstSnapshot.featurePayloadDigest());
        assertThat((Map<String, Object>) firstSnapshot.featurePayload().get("features"))
                .containsKeys(
                        "annualIncome",
                        "monthlyIncomeMean",
                        "monthlyIncomeVolatility",
                        "debtToIncomeRatio",
                        "existingDebtAmount",
                        "delinquencyCount",
                        "platformSettlementMonths",
                        "platformSettlementMean",
                        "platformSettlementVolatility",
                        "contractDurationMonths",
                        "incomeDeclarationAvailable",
                        "telecomPaymentDelinquencyCount");

        service.handleGovernanceReviewStarted(reviewStarted(created.applicationId()));
        EventEnvelope evidenceRequested = event(
                "governance.evidence.requested.v1",
                "governance-service",
                created.applicationId(),
                UUID.fromString("30000000-0000-4000-8000-000000000016"),
                UUID.randomUUID(),
                Map.of(
                        "requestId", "20000000-0000-4000-8000-000000000016",
                        "decisionCaseId", "case-1001",
                        "applicationId", created.applicationId().toString(),
                        "evaluationRunId", "30000000-0000-4000-8000-000000000016",
                        "requestedEvidenceTypes", List.of("TRANSACTION_EXPLANATION"),
                        "reasonCodes", List.of("UNCONFIRMED_TRANSACTION_ANOMALY")
                )
        );
        service.handleEvidenceRequested(evidenceRequested);
        service.updateEvidence(
                new EvidenceUpdateCommand(
                        created.applicationId(),
                        "case-1001",
                        evidenceRequested.eventId(),
                        List.of("evidence://transaction-explanation/1")
                ),
                FinancialSnapshotInput.fromCreateRequest(
                        validRequest("loan-evidence-feature-001", "30000000.00", "6100000.00"))
        );

        var storedFirstSnapshot = featureSnapshots.get(created.applicationId(), "snapshot-v1");
        var secondSnapshot = featureSnapshots.get(created.applicationId(), "snapshot-v2");
        assertThat(secondSnapshot.snapshotVersion()).isEqualTo("snapshot-v2");
        assertThat(storedFirstSnapshot.createdAt()).isEqualTo(firstSnapshot.createdAt());
        assertThat(storedFirstSnapshot.snapshotReference()).isEqualTo(firstSnapshot.snapshotReference());
        assertThat(secondSnapshot.snapshotReference())
                .containsEntry("snapshotCreatedAt", secondSnapshot.createdAt().toString())
                .containsEntry("snapshotVersion", "snapshot-v2")
                .containsEntry("snapshotDigest", secondSnapshot.featurePayloadDigest());
        assertThat(storedFirstSnapshot.featurePayloadDigest()).isEqualTo(firstSnapshot.featurePayloadDigest());
        assertThat(secondSnapshot.featurePayloadDigest()).isNotEqualTo(firstSnapshot.featurePayloadDigest());
        assertThat(secondSnapshot.snapshotReference()).isNotEqualTo(firstSnapshot.snapshotReference());
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
    void rejectsFeatureSourceOutsideOfficialContractAndRollsBackSubmission() {
        assertThatThrownBy(() -> service.create(requestWithContractDuration("loan-create-feature-range", 241)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Contract validation failed");

        assertThat(applications.count()).isZero();
        assertThat(featureSnapshotRepository.count()).isZero();
        assertThat(outbox.count()).isZero();
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
                FinancialSnapshotInput.fromCreateRequest(validRequest("loan-evidence-006", "25000000.00"))
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
        UUID evidenceRequestId = UUID.randomUUID();
        inbox.save(new InboxEventEntity(
                evidenceRequestId,
                "governance.evidence.requested.v1",
                null,
                created.applicationId(),
                "case-1001",
                "0".repeat(64),
                Instant.now()
        ));

        assertThatThrownBy(() -> service.updateEvidence(
                new EvidenceUpdateCommand(
                        created.applicationId(),
                        "case-1001",
                        evidenceRequestId,
                        List.of("evidence://transaction-explanation/1")
                ),
                FinancialSnapshotInput.fromCreateRequest(validRequest("loan-evidence-010", "25000000.00"))
        )).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectsEvidenceUpdateWithUnknownCausation() {
        LoanApplicationResponse created = service.create(validRequest("loan-create-011", "25000000.00"));

        assertThatThrownBy(() -> service.updateEvidence(
                new EvidenceUpdateCommand(
                        created.applicationId(),
                        "case-1001",
                        UUID.randomUUID(),
                        List.of("evidence://transaction-explanation/1")
                ),
                FinancialSnapshotInput.fromCreateRequest(validRequest("loan-evidence-011", "25000000.00"))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("causationId was not received");
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
        return validRequest(idempotencyKey, requestedAmount, "5200000.00");
    }

    private LoanApplicationCreateRequest validRequest(String idempotencyKey, String requestedAmount,
                                                      String monthlyIncomeAmount) {
        return new LoanApplicationCreateRequest(
                "1.0.0",
                "synthetic:applicant-001",
                requestedAmount,
                "KRW",
                List.of(new LoanApplicationCreateRequest.MonthlyIncomeRequest("2026-06", monthlyIncomeAmount, "masked:income-2026-06")),
                new LoanApplicationCreateRequest.DebtSummaryRequest("4000000.00", "350000.00", List.of("masked:debt-summary-001")),
                new LoanApplicationCreateRequest.DelinquencySummaryRequest(0, 0, List.of("masked:delinquency-001")),
                new LoanApplicationCreateRequest.PlatformSettlementSummaryRequest("2026-Q2", "18000000.00", List.of("synthetic:settlement-q2")),
                phase2FeatureSource(36),
                List.of("synthetic:risk-signal-001"),
                idempotencyKey
        );
    }

    private LoanApplicationCreateRequest requestWithContractDuration(String idempotencyKey, int contractDurationMonths) {
        return new LoanApplicationCreateRequest(
                "1.0.0",
                "synthetic:applicant-001",
                "25000000.00",
                "KRW",
                List.of(new LoanApplicationCreateRequest.MonthlyIncomeRequest("2026-06", "5200000.00", "masked:income-2026-06")),
                new LoanApplicationCreateRequest.DebtSummaryRequest("4000000.00", "350000.00", List.of("masked:debt-summary-001")),
                new LoanApplicationCreateRequest.DelinquencySummaryRequest(0, 0, List.of("masked:delinquency-001")),
                new LoanApplicationCreateRequest.PlatformSettlementSummaryRequest("2026-Q2", "18000000.00", List.of("synthetic:settlement-q2")),
                phase2FeatureSource(contractDurationMonths),
                List.of("synthetic:risk-signal-001"),
                idempotencyKey
        );
    }

    private LoanApplicationCreateRequest validRequestWithoutPhase2(String idempotencyKey) {
        return new LoanApplicationCreateRequest(
                "1.0.0",
                "synthetic:applicant-001",
                "25000000.00",
                "KRW",
                List.of(new LoanApplicationCreateRequest.MonthlyIncomeRequest("2026-06", "5200000.00", "masked:income-2026-06")),
                new LoanApplicationCreateRequest.DebtSummaryRequest("4000000.00", "350000.00", List.of("masked:debt-summary-001")),
                new LoanApplicationCreateRequest.DelinquencySummaryRequest(0, 0, List.of("masked:delinquency-001")),
                new LoanApplicationCreateRequest.PlatformSettlementSummaryRequest("2026-Q2", "18000000.00", List.of("synthetic:settlement-q2")),
                null,
                List.of("synthetic:risk-signal-001"),
                idempotencyKey
        );
    }

    private LoanApplicationCreateRequest.Phase2FeatureSourceRequest phase2FeatureSource(int contractDurationMonths) {
        Instant observedAt = Instant.parse("2026-07-21T10:00:00Z");
        return new LoanApplicationCreateRequest.Phase2FeatureSourceRequest(
                new LoanApplicationCreateRequest.SettlementVolatilitySourceRequest(
                        "0.081", "masked:settlement-history-001", "SETTLEMENT_HISTORY", observedAt),
                new LoanApplicationCreateRequest.ContractDurationSourceRequest(
                        contractDurationMonths, "masked:contract-001", "CONTRACT_EVIDENCE", observedAt),
                new LoanApplicationCreateRequest.IncomeDeclarationSourceRequest(
                        true, "masked:income-declaration-001", "INCOME_DECLARATION", observedAt),
                new LoanApplicationCreateRequest.TelecomDelinquencySourceRequest(
                        0, "masked:telecom-history-001", "TELECOM_HISTORY", observedAt)
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
