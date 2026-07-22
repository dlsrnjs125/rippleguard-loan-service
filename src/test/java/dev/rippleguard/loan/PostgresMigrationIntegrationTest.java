package dev.rippleguard.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import dev.rippleguard.loan.application.ConflictException;
import dev.rippleguard.loan.application.EventEnvelope;
import dev.rippleguard.loan.application.FinancialSnapshotInput;
import dev.rippleguard.loan.application.LoanApplicationService;
import dev.rippleguard.loan.application.Phase2FeatureSnapshotService;
import dev.rippleguard.loan.domain.LoanApplicationStatus;
import dev.rippleguard.loan.domain.OutboxStatus;
import dev.rippleguard.loan.infrastructure.persistence.FinancialSnapshotEntity;
import dev.rippleguard.loan.infrastructure.persistence.FinancialSnapshotRepository;
import dev.rippleguard.loan.infrastructure.persistence.LoanApplicationEntity;
import dev.rippleguard.loan.infrastructure.persistence.LoanApplicationRepository;
import dev.rippleguard.loan.infrastructure.persistence.LoanFeatureSnapshotRepository;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventEntity;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventRepository;
import dev.rippleguard.loan.interfaces.rest.LoanApplicationCreateRequest;
import dev.rippleguard.loan.interfaces.rest.LoanApplicationResponse;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "debug=false",
        "rippleguard.kafka.enabled=false",
        "management.health.kafka.enabled=false"
})
class PostgresMigrationIntegrationTest {
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
    DataSource dataSource;

    @Autowired
    OutboxEventRepository outbox;

    @Autowired
    LoanFeatureSnapshotRepository featureSnapshots;

    @Autowired
    Phase2FeatureSnapshotService featureSnapshotService;

    @Autowired
    LoanApplicationRepository applications;

    @Autowired
    FinancialSnapshotRepository financialSnapshots;

    @Autowired
    LoanApplicationService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TransactionTemplate transactions;

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
    void appliesFlywayMigrationOnPostgreSql() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
            var result = statement.executeQuery("select count(*) from outbox_event")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void claimsOutboxRowsWithPostgreSqlSkipLockedQuery() {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        outbox.save(new OutboxEventEntity(
                eventId,
                "loan.application.submitted.v1",
                "1.1.0",
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                null,
                "{\"eventType\":\"loan.application.submitted.v1\"}",
                now
        ));

        var claimed = transactions.execute(status -> outbox.findClaimable(now, 10));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getEventId()).isEqualTo(eventId);
        claimed.get(0).markProcessing(now, now.plusSeconds(60), "test-instance");
        transactions.executeWithoutResult(status -> outbox.save(claimed.get(0)));

        assertThat(outbox.findById(eventId)).get()
                .extracting(OutboxEventEntity::getStatus)
                .isEqualTo(OutboxStatus.PROCESSING);
    }

    @Test
    void reclaimsProcessingRowsAfterLeaseExpires() {
        Instant now = Instant.now();
        OutboxEventEntity event = new OutboxEventEntity(
                UUID.randomUUID(),
                "loan.decision.finalized.v1",
                "1.1.0",
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                null,
                "{\"eventType\":\"loan.decision.finalized.v1\"}",
                now.minusSeconds(120)
        );
        event.markProcessing(now.minusSeconds(120), now.minusSeconds(60), "dead-instance");
        outbox.saveAndFlush(event);

        var claimed = transactions.execute(status -> outbox.findClaimable(now, 10));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getEventId()).isEqualTo(event.getEventId());
    }

    @Test
    void concurrentCreateWithSameIdempotencyKeyAndPayloadReturnsSameApplicationId() throws Exception {
        var results = runConcurrently(
                () -> service.create(validRequest("postgres-concurrent-same", "25000000.00")),
                () -> service.create(validRequest("postgres-concurrent-same", "25000000.00"))
        );

        assertThat(results).allMatch(LoanApplicationResponse.class::isInstance);
        assertThat(results.stream()
                .map(LoanApplicationResponse.class::cast)
                .map(LoanApplicationResponse::applicationId)
                .distinct()).hasSize(1);
        LoanApplicationResponse response = (LoanApplicationResponse) results.get(0);
        assertThat(featureSnapshots.findByApplicationApplicationIdAndSnapshotVersion(
                response.applicationId(), "snapshot-v1")).isPresent();
        assertThat(featureSnapshots.count()).isEqualTo(1);
    }

    @Test
    void concurrentCreateWithSameIdempotencyKeyAndDifferentPayloadReturnsOneConflict() throws Exception {
        var results = runConcurrently(
                () -> service.create(validRequest("postgres-concurrent-different", "25000000.00")),
                () -> service.create(validRequest("postgres-concurrent-different", "30000000.00"))
        );

        assertThat(results).filteredOn(LoanApplicationResponse.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ConflictException.class::isInstance).hasSize(1);
    }

    @Test
    void concurrentDirectFeatureSnapshotCreateRecoversIdempotentlyOutsideFailedTransaction() throws Exception {
        SnapshotFixture fixture = snapshotFixture("postgres-direct-feature-same");
        FinancialSnapshotInput input = FinancialSnapshotInput.fromCreateRequest(
                validRequest("postgres-direct-feature-input", "25000000.00"));

        var results = runConcurrently(
                () -> featureSnapshotService.createIfSourcePresent(
                        fixture.application(), fixture.financialSnapshot(), input, Instant.now()).orElseThrow(),
                () -> featureSnapshotService.createIfSourcePresent(
                        fixture.application(), fixture.financialSnapshot(), input, Instant.now()).orElseThrow()
        );

        assertThat(results).allMatch(result -> result instanceof dev.rippleguard.loan.infrastructure.persistence.LoanFeatureSnapshotEntity);
        assertThat(featureSnapshots.count()).isEqualTo(1);
        assertThat(results.stream()
                .map(dev.rippleguard.loan.infrastructure.persistence.LoanFeatureSnapshotEntity.class::cast)
                .map(dev.rippleguard.loan.infrastructure.persistence.LoanFeatureSnapshotEntity::getFeaturePayloadDigest)
                .distinct()).hasSize(1);
    }

    @Test
    void directFeatureSnapshotCreateRejectsSameVersionDifferentPayloadWithoutOverwriting() {
        SnapshotFixture fixture = snapshotFixture("postgres-direct-feature-conflict");
        FinancialSnapshotInput first = FinancialSnapshotInput.fromCreateRequest(
                validRequest("postgres-direct-feature-first", "25000000.00"));
        FinancialSnapshotInput second = FinancialSnapshotInput.fromCreateRequest(
                validRequest("postgres-direct-feature-second", "25000000.00", "6100000.00"));

        var created = featureSnapshotService.createIfSourcePresent(
                fixture.application(), fixture.financialSnapshot(), first, Instant.now()).orElseThrow();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> featureSnapshotService.createIfSourcePresent(
                        fixture.application(), fixture.financialSnapshot(), second, Instant.now()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("FEATURE_SNAPSHOT_CONFLICT");
        assertThat(featureSnapshots.count()).isEqualTo(1);
        assertThat(featureSnapshots.findByApplicationApplicationIdAndSnapshotVersion(
                fixture.application().getApplicationId(), "snapshot-v1")).get()
                .extracting(dev.rippleguard.loan.infrastructure.persistence.LoanFeatureSnapshotEntity::getFeaturePayloadDigest)
                .isEqualTo(created.getFeaturePayloadDigest());
    }

    @Test
    void featureSnapshotRejectsReusingFinancialSnapshotForAnotherVersion() {
        SnapshotFixture fixture = snapshotFixture("postgres-financial-snapshot-unique");
        FinancialSnapshotInput input = FinancialSnapshotInput.fromCreateRequest(
                validRequest("postgres-financial-snapshot-unique-input", "25000000.00"));
        var created = featureSnapshotService.createIfSourcePresent(
                fixture.application(), fixture.financialSnapshot(), input, Instant.now()).orElseThrow();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.execute(status ->
                featureSnapshots.insertIfAbsent(
                        UUID.randomUUID(),
                        fixture.application().getApplicationId(),
                        fixture.financialSnapshot().getSnapshotId(),
                        "snapshot-v2",
                        created.getSnapshotSchemaVersion(),
                        created.getFeatureSchemaVersion(),
                        created.getFeaturePayload(),
                        created.getFeaturePayloadDigest(),
                        created.getSnapshotReference(),
                        fixture.application().getSnapshotVersion(),
                        Instant.now()
                )))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(featureSnapshots.count()).isEqualTo(1);
    }

    @Test
    void concurrentDecisionCommandAndEvidenceRequestAppliesOnlyOneStateChange() throws Exception {
        LoanApplicationResponse created = service.create(validRequest("postgres-optimistic-race", "25000000.00"));
        EventEnvelope reviewStarted = reviewStarted(created.applicationId());
        service.handleGovernanceReviewStarted(reviewStarted);

        var results = runConcurrently(
                () -> {
                    service.handleEvidenceRequested(evidenceRequested(created.applicationId(), reviewStarted.eventId()));
                    return null;
                },
                () -> {
                    service.handleDecisionCommand(decisionCommand(created.applicationId(), reviewStarted.eventId()));
                    return null;
                }
        );

        assertThat(results).filteredOn(result -> result == null).hasSize(1);
        assertThat(results).filteredOn(Throwable.class::isInstance).hasSize(1);
        assertThat(service.get(created.applicationId()).status())
                .isIn(LoanApplicationStatus.EVIDENCE_REQUIRED, LoanApplicationStatus.FINALIZED);
    }

    private List<Object> runConcurrently(ThrowingSupplier<?> first, ThrowingSupplier<?> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> left = executor.submit(() -> callAfterStart(first, ready, start));
            Future<Object> right = executor.submit(() -> callAfterStart(second, ready, start));
            ready.await();
            start.countDown();
            List<Object> results = new ArrayList<>();
            results.add(left.get());
            results.add(right.get());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Object callAfterStart(ThrowingSupplier<?> supplier, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return supplier.get();
        } catch (Exception exception) {
            return exception;
        }
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
                phase2FeatureSource(),
                List.of("synthetic:risk-signal-001"),
                idempotencyKey
        );
    }

    private SnapshotFixture snapshotFixture(String idempotencyKey) {
        LoanApplicationResponse response = service.create(validRequestWithoutPhase2(idempotencyKey));
        LoanApplicationEntity application = applications.findById(response.applicationId()).orElseThrow();
        FinancialSnapshotEntity financialSnapshot = financialSnapshots
                .findByApplicationApplicationIdAndSnapshotVersion(response.applicationId(), "snapshot-v1")
                .orElseThrow();
        return new SnapshotFixture(application, financialSnapshot);
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

    private LoanApplicationCreateRequest.Phase2FeatureSourceRequest phase2FeatureSource() {
        Instant observedAt = Instant.parse("2026-07-21T10:00:00Z");
        return new LoanApplicationCreateRequest.Phase2FeatureSourceRequest(
                new LoanApplicationCreateRequest.SettlementVolatilitySourceRequest(
                        "0.081", "masked:settlement-history-001", "SETTLEMENT_HISTORY", observedAt),
                new LoanApplicationCreateRequest.ContractDurationSourceRequest(
                        36, "masked:contract-001", "CONTRACT_EVIDENCE", observedAt),
                new LoanApplicationCreateRequest.IncomeDeclarationSourceRequest(
                        true, "masked:income-declaration-001", "INCOME_DECLARATION", observedAt),
                new LoanApplicationCreateRequest.TelecomDelinquencySourceRequest(
                        0, "masked:telecom-history-001", "TELECOM_HISTORY", observedAt)
        );
    }

    private EventEnvelope reviewStarted(UUID applicationId) {
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

    private EventEnvelope evidenceRequested(UUID applicationId, UUID causationId) {
        return event(
                "governance.evidence.requested.v1",
                applicationId,
                UUID.fromString("30000000-0000-4000-8000-000000000091"),
                causationId,
                Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "decisionCaseId", "case-1001",
                        "applicationId", applicationId.toString(),
                        "evaluationRunId", "30000000-0000-4000-8000-000000000091",
                        "requestedEvidenceTypes", List.of("TRANSACTION_EXPLANATION"),
                        "reasonCodes", List.of("UNCONFIRMED_TRANSACTION_ANOMALY")
                )
        );
    }

    private EventEnvelope decisionCommand(UUID applicationId, UUID causationId) {
        return event(
                "loan.decision.commanded.v1",
                applicationId,
                UUID.fromString("30000000-0000-4000-8000-000000000092"),
                causationId,
                commandPayload(UUID.randomUUID(), applicationId)
        );
    }

    private EventEnvelope event(String eventType, UUID applicationId, UUID evaluationRunId, UUID causationId,
                                Map<String, Object> payload) {
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

    private Map<String, Object> commandPayload(UUID commandId, UUID applicationId) {
        Map<String, Object> commandPayload = new LinkedHashMap<>();
        commandPayload.put("commandId", commandId.toString());
        commandPayload.put("decisionCaseId", "case-1001");
        commandPayload.put("applicationId", applicationId.toString());
        commandPayload.put("decisionId", "40000000-0000-4000-8000-000000000001");
        commandPayload.put("evaluationRunId", "30000000-0000-4000-8000-000000000092");
        commandPayload.put("evaluationRunStatus", "COMPLETED");
        commandPayload.put("finalDecision", "APPROVE");
        commandPayload.put("assuranceResult", "ASSURANCE_COMPLETE");
        commandPayload.put("reasonCodes", List.of("GOVERNANCE_VERIFIED_PROPOSAL"));
        commandPayload.put("issuedAt", Instant.now().toString());
        commandPayload.put("idempotencyKey", "decision-command-case-1001-" + commandId);
        return commandPayload;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record SnapshotFixture(LoanApplicationEntity application, FinancialSnapshotEntity financialSnapshot) {
    }
}
