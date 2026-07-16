package dev.rippleguard.loan.application;

import dev.rippleguard.loan.domain.FinalDecision;
import dev.rippleguard.loan.domain.LoanApplicationStatus;
import dev.rippleguard.loan.infrastructure.persistence.FinancialSnapshotEntity;
import dev.rippleguard.loan.infrastructure.persistence.FinancialSnapshotRepository;
import dev.rippleguard.loan.infrastructure.persistence.InboxEventEntity;
import dev.rippleguard.loan.infrastructure.persistence.InboxEventRepository;
import dev.rippleguard.loan.infrastructure.persistence.LoanApplicationEntity;
import dev.rippleguard.loan.infrastructure.persistence.LoanApplicationRepository;
import dev.rippleguard.loan.infrastructure.persistence.LoanDecisionEntity;
import dev.rippleguard.loan.infrastructure.persistence.LoanDecisionRepository;
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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationService {
    private static final Logger log = LoggerFactory.getLogger(LoanApplicationService.class);
    private static final String EVENT_SCHEMA_VERSION = "1.1.0";

    private final LoanApplicationRepository applications;
    private final FinancialSnapshotRepository snapshots;
    private final MonthlyIncomeRepository monthlyIncomes;
    private final LoanDecisionRepository decisions;
    private final InboxEventRepository inbox;
    private final OutboxEventRepository outbox;
    private final JsonSupport json;
    private final Clock clock;

    public LoanApplicationService(LoanApplicationRepository applications,
                                  FinancialSnapshotRepository snapshots,
                                  MonthlyIncomeRepository monthlyIncomes,
                                  LoanDecisionRepository decisions,
                                  InboxEventRepository inbox,
                                  OutboxEventRepository outbox,
                                  JsonSupport json,
                                  Clock clock) {
        this.applications = applications;
        this.snapshots = snapshots;
        this.monthlyIncomes = monthlyIncomes;
        this.decisions = decisions;
        this.inbox = inbox;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public LoanApplicationResponse create(LoanApplicationCreateRequest request) {
        String canonicalPayload = json.canonicalJson(request);
        String requestHash = json.sha256(canonicalPayload);

        return applications.findByIdempotencyKey(request.idempotencyKey())
                .map(existing -> {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new ConflictException("Idempotency key was reused with a different payload");
                    }
                    return toResponse(existing);
                })
                .orElseGet(() -> createNewApplication(request, canonicalPayload, requestHash));
    }

    @Transactional(readOnly = true)
    public LoanApplicationResponse get(UUID applicationId) {
        return applications.findById(applicationId)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("Loan application not found: " + applicationId));
    }

    @Transactional
    public void handleGovernanceReviewStarted(EventEnvelope event) {
        requireEvent(event, "governance.review.started.v1", EVENT_SCHEMA_VERSION);
        if (inbox.existsById(event.eventId())) {
            return;
        }

        UUID applicationId = event.applicationId();
        LoanApplicationEntity application = loadForUpdate(applicationId);
        transition(application, LoanApplicationStatus.UNDER_GOVERNANCE_REVIEW);
        recordInbox(event, null);
        log.info("Governance review started applicationId={} eventId={}", applicationId, event.eventId());
    }

    @Transactional
    public void handleEvidenceRequested(EventEnvelope event) {
        requireEvent(event, "governance.evidence.requested.v1", EVENT_SCHEMA_VERSION);
        if (inbox.existsById(event.eventId())) {
            return;
        }

        UUID applicationId = event.applicationId();
        LoanApplicationEntity application = loadForUpdate(applicationId);
        if (application.getStatus() == LoanApplicationStatus.SUBMITTED) {
            transition(application, LoanApplicationStatus.UNDER_GOVERNANCE_REVIEW);
        }
        transition(application, LoanApplicationStatus.EVIDENCE_REQUIRED);
        recordInbox(event, null);
        log.info("Evidence requested applicationId={} eventId={}", applicationId, event.eventId());
    }

    @Transactional
    public void handleDecisionCommand(EventEnvelope event) {
        requireEvent(event, "loan.decision.commanded.v1", EVENT_SCHEMA_VERSION);
        DecisionCommandPayload command = json.fromJson(event.payload().toString(), DecisionCommandPayload.class);
        validateCommandEnvelope(event, command);
        if (inbox.existsById(event.eventId()) || inbox.existsByCommandId(command.commandId()) || decisions.existsByCommandId(command.commandId())) {
            return;
        }

        LoanApplicationEntity application = loadForUpdate(command.applicationId());
        if (decisions.existsByApplicationApplicationId(command.applicationId())) {
            recordInbox(event, command.commandId());
            return;
        }
        if (application.getStatus() == LoanApplicationStatus.SUBMITTED) {
            transition(application, LoanApplicationStatus.UNDER_GOVERNANCE_REVIEW);
        }
        if (application.getStatus() == LoanApplicationStatus.EVIDENCE_REQUIRED) {
            transition(application, LoanApplicationStatus.UNDER_GOVERNANCE_REVIEW);
        }
        transition(application, LoanApplicationStatus.DECISION_RECEIVED);
        transition(application, LoanApplicationStatus.FINALIZED);

        Instant now = clock.instant();
        decisions.save(new LoanDecisionEntity(
                UUID.randomUUID(),
                application,
                command.commandId(),
                command.decisionCaseId(),
                command.decisionId(),
                command.evaluationRunId(),
                command.finalDecision(),
                json.canonicalJson(command.reasonCodes()),
                command.issuedAt(),
                now
        ));
        recordInbox(event, command.commandId());
        outbox.save(finalizedEvent(application.getApplicationId(), event, command, now));
        log.info("Decision finalized applicationId={} commandId={} eventId={}",
                command.applicationId(), command.commandId(), event.eventId());
    }

    private LoanApplicationResponse createNewApplication(LoanApplicationCreateRequest request, String canonicalPayload, String requestHash) {
        Instant now = clock.instant();
        UUID applicationId = UUID.randomUUID();
        LoanApplicationEntity application = applications.save(new LoanApplicationEntity(
                applicationId,
                request.idempotencyKey(),
                requestHash,
                money(request.requestedAmount()),
                request.currency(),
                request.applicantReference(),
                now
        ));

        FinancialSnapshotEntity snapshot = snapshots.save(new FinancialSnapshotEntity(
                UUID.randomUUID(),
                application,
                1,
                request.applicantReference(),
                money(request.requestedAmount()),
                request.currency(),
                money(request.debtSummary().totalOutstandingAmount()),
                money(request.debtSummary().monthlyPaymentAmount()),
                request.delinquencySummary().delinquencyCount(),
                request.delinquencySummary().daysPastDueMaximum(),
                request.platformSettlementSummary().period(),
                money(request.platformSettlementSummary().grossSettlementAmount()),
                json.canonicalJson(request.debtSummary().sourceReferences()),
                json.canonicalJson(request.delinquencySummary().sourceReferences()),
                json.canonicalJson(request.platformSettlementSummary().sourceReferences()),
                json.canonicalJson(request.riskSignalReferences()),
                canonicalPayload,
                now
        ));

        request.incomeHistory().forEach(income -> monthlyIncomes.save(new MonthlyIncomeEntity(
                UUID.randomUUID(),
                snapshot,
                income.period(),
                money(income.amount()),
                income.sourceReference()
        )));

        outbox.save(submittedEvent(applicationId, request, now));
        return toResponse(application);
    }

    private LoanApplicationEntity loadForUpdate(UUID applicationId) {
        return applications.findWithLockByApplicationId(applicationId)
                .orElseThrow(() -> new NotFoundException("Loan application not found: " + applicationId));
    }

    private void transition(LoanApplicationEntity application, LoanApplicationStatus target) {
        try {
            application.transitionTo(target, clock.instant());
        } catch (IllegalStateException exception) {
            log.warn("Rejected loan status transition applicationId={} target={} reason={}",
                    application.getApplicationId(), target, exception.getMessage());
            throw new InvalidStateTransitionException(exception.getMessage());
        }
    }

    private void validateCommandEnvelope(EventEnvelope event, DecisionCommandPayload command) {
        if (!"COMPLETED".equals(command.evaluationRunStatus())) {
            throw new IllegalArgumentException("Decision command requires COMPLETED evaluationRunStatus");
        }
        if (!"ASSURANCE_COMPLETE".equals(command.assuranceResult())) {
            throw new IllegalArgumentException("Decision command requires ASSURANCE_COMPLETE");
        }
        if (command.finalDecision() != FinalDecision.APPROVE && command.finalDecision() != FinalDecision.REJECT) {
            throw new IllegalArgumentException("Unsupported finalDecision: " + command.finalDecision());
        }
        if (!event.applicationId().equals(command.applicationId())) {
            throw new IllegalArgumentException("Envelope and command applicationId differ");
        }
        if (!event.evaluationRunId().equals(command.evaluationRunId())) {
            throw new IllegalArgumentException("Envelope and command evaluationRunId differ");
        }
    }

    private void requireEvent(EventEnvelope event, String eventType, String schemaVersion) {
        if (!eventType.equals(event.eventType()) || !schemaVersion.equals(event.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported event contract: " + event.eventType() + " " + event.schemaVersion());
        }
    }

    private void recordInbox(EventEnvelope event, UUID commandId) {
        try {
            inbox.save(new InboxEventEntity(
                    event.eventId(),
                    event.eventType(),
                    commandId,
                    json.sha256(event.payload().toString()),
                    clock.instant()
            ));
        } catch (DataIntegrityViolationException ignoredDuplicate) {
            log.info("Duplicate inbox event ignored eventId={} commandId={}", event.eventId(), commandId);
        }
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

    private OutboxEventEntity finalizedEvent(UUID applicationId, EventEnvelope cause, DecisionCommandPayload command, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", command.commandId().toString());
        payload.put("decisionCaseId", command.decisionCaseId());
        payload.put("applicationId", applicationId.toString());
        payload.put("decisionId", command.decisionId().toString());
        payload.put("evaluationRunId", command.evaluationRunId().toString());
        payload.put("finalDecision", command.finalDecision().name());
        payload.put("finalizedAt", now.toString());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "loan.decision.finalized.v1");
        envelope.put("schemaVersion", EVENT_SCHEMA_VERSION);
        envelope.put("occurredAt", now.toString());
        envelope.put("producer", "loan-service");
        envelope.put("applicationId", applicationId.toString());
        envelope.put("caseId", command.decisionCaseId());
        envelope.put("evaluationRunId", command.evaluationRunId().toString());
        envelope.put("correlationId", applicationId.toString());
        envelope.put("causationId", cause.eventId().toString());
        envelope.put("payload", payload);

        return event("loan.decision.finalized.v1", applicationId, applicationId.toString(), cause.eventId(), envelope, now);
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
