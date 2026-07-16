package dev.rippleguard.loan.application;

import dev.rippleguard.loan.infrastructure.persistence.FinancialSnapshotEntity;
import dev.rippleguard.loan.infrastructure.persistence.FinancialSnapshotRepository;
import dev.rippleguard.loan.infrastructure.persistence.LoanApplicationEntity;
import dev.rippleguard.loan.infrastructure.persistence.LoanApplicationRepository;
import dev.rippleguard.loan.infrastructure.persistence.MonthlyIncomeEntity;
import dev.rippleguard.loan.infrastructure.persistence.MonthlyIncomeRepository;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventEntity;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventRepository;
import dev.rippleguard.loan.interfaces.rest.LoanApplicationCreateRequest;
import dev.rippleguard.loan.interfaces.rest.LoanApplicationResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationCreationTransactions {
    private static final String EVENT_SCHEMA_VERSION = "1.1.0";

    private final LoanApplicationRepository applications;
    private final FinancialSnapshotRepository snapshots;
    private final MonthlyIncomeRepository monthlyIncomes;
    private final OutboxEventRepository outbox;
    private final JsonSupport json;
    private final Clock clock;

    public LoanApplicationCreationTransactions(LoanApplicationRepository applications,
                                               FinancialSnapshotRepository snapshots,
                                               MonthlyIncomeRepository monthlyIncomes,
                                               OutboxEventRepository outbox,
                                               JsonSupport json,
                                               Clock clock) {
        this.applications = applications;
        this.snapshots = snapshots;
        this.monthlyIncomes = monthlyIncomes;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<LoanApplicationResponse> findExisting(String idempotencyKey, String requestHash) {
        return applications.findByIdempotencyKey(idempotencyKey)
                .map(existing -> existingIdempotentResponse(existing, requestHash));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoanApplicationResponse createNew(LoanApplicationCreateRequest request, String requestHash) {
        Instant now = clock.instant();
        UUID applicationId = UUID.randomUUID();
        LoanApplicationEntity application = applications.saveAndFlush(new LoanApplicationEntity(
                applicationId,
                request.idempotencyKey(),
                requestHash,
                money(request.requestedAmount()),
                request.currency(),
                request.applicantReference(),
                now
        ));

        storeSnapshot(application, 1, FinancialSnapshotInput.fromCreateRequest(request), now);
        outbox.save(submittedEvent(applicationId, request, now));
        return toResponse(application);
    }

    private LoanApplicationResponse existingIdempotentResponse(LoanApplicationEntity existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ConflictException("Idempotency key was reused with a different payload");
        }
        return toResponse(existing);
    }

    private void storeSnapshot(LoanApplicationEntity application, int version,
                               FinancialSnapshotInput input, Instant now) {
        String canonicalPayload = json.canonicalJson(input);
        FinancialSnapshotEntity snapshot = snapshots.save(new FinancialSnapshotEntity(
                UUID.randomUUID(),
                application,
                version,
                input.applicantReference(),
                money(input.requestedAmount()),
                input.currency(),
                money(input.debtSummary().totalOutstandingAmount()),
                money(input.debtSummary().monthlyPaymentAmount()),
                input.delinquencySummary().delinquencyCount(),
                input.delinquencySummary().daysPastDueMaximum(),
                input.platformSettlementSummary().period(),
                money(input.platformSettlementSummary().grossSettlementAmount()),
                json.canonicalJson(input.debtSummary().sourceReferences()),
                json.canonicalJson(input.delinquencySummary().sourceReferences()),
                json.canonicalJson(input.platformSettlementSummary().sourceReferences()),
                json.canonicalJson(input.riskSignalReferences()),
                canonicalPayload,
                now
        ));

        input.incomeHistory().forEach(income -> monthlyIncomes.save(new MonthlyIncomeEntity(
                UUID.randomUUID(),
                snapshot,
                income.period(),
                money(income.amount()),
                income.sourceReference()
        )));
    }

    private OutboxEventEntity submittedEvent(UUID applicationId, LoanApplicationCreateRequest request, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", applicationId.toString());
        payload.put("applicantId", request.applicantReference());
        payload.put("inputSnapshotVersion", "snapshot-v1");
        payload.put("submittedAt", now.toString());
        payload.put("submissionChannel", "PARTNER_API");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "loan.application.submitted.v1");
        envelope.put("schemaVersion", EVENT_SCHEMA_VERSION);
        envelope.put("occurredAt", now.toString());
        envelope.put("producer", "loan-service");
        envelope.put("applicationId", applicationId.toString());
        envelope.put("caseId", applicationId.toString());
        envelope.put("evaluationRunId", null);
        envelope.put("correlationId", applicationId.toString());
        envelope.put("causationId", null);
        envelope.put("payload", payload);

        return event("loan.application.submitted.v1", applicationId, applicationId.toString(), null, envelope, now);
    }

    private OutboxEventEntity event(String eventType, UUID aggregateId, String correlationId, UUID causationId,
                                    Map<String, Object> envelope, Instant now) {
        UUID eventId = UUID.fromString((String) envelope.get("eventId"));
        return new OutboxEventEntity(
                eventId,
                eventType,
                EVENT_SCHEMA_VERSION,
                aggregateId,
                correlationId,
                causationId,
                json.canonicalJson(envelope),
                now
        );
    }

    private LoanApplicationResponse toResponse(LoanApplicationEntity application) {
        return new LoanApplicationResponse(
                "1.0.0",
                application.getApplicationId(),
                application.getStatus(),
                "snapshot-v" + application.getSnapshotVersion(),
                application.getCreatedAt(),
                application.getUpdatedAt()
        );
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
