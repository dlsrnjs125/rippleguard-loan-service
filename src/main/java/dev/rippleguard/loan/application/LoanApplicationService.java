package dev.rippleguard.loan.application;

import dev.rippleguard.loan.domain.FinalDecision;
import dev.rippleguard.loan.domain.LoanApplicationStatus;
import dev.rippleguard.loan.infrastructure.persistence.FinancialSnapshotEntity;
import dev.rippleguard.loan.infrastructure.persistence.FinancialSnapshotRepository;
import dev.rippleguard.loan.infrastructure.persistence.EvidenceUpdateRequestEntity;
import dev.rippleguard.loan.infrastructure.persistence.EvidenceUpdateRequestRepository;
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
import java.util.List;
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
    private final EvidenceUpdateRequestRepository evidenceUpdates;
    private final LoanDecisionRepository decisions;
    private final InboxEventRepository inbox;
    private final OutboxEventRepository outbox;
    private final LoanApplicationCreationTransactions creationTransactions;
    private final Phase2FeatureSnapshotService featureSnapshots;
    private final JsonSupport json;
    private final Clock clock;

    public LoanApplicationService(LoanApplicationRepository applications,
                                  FinancialSnapshotRepository snapshots,
                                  MonthlyIncomeRepository monthlyIncomes,
                                  EvidenceUpdateRequestRepository evidenceUpdates,
                                  LoanDecisionRepository decisions,
                                  InboxEventRepository inbox,
                                  OutboxEventRepository outbox,
                                  LoanApplicationCreationTransactions creationTransactions,
                                  Phase2FeatureSnapshotService featureSnapshots,
                                  JsonSupport json,
                                  Clock clock) {
        this.applications = applications;
        this.snapshots = snapshots;
        this.monthlyIncomes = monthlyIncomes;
        this.evidenceUpdates = evidenceUpdates;
        this.decisions = decisions;
        this.inbox = inbox;
        this.outbox = outbox;
        this.creationTransactions = creationTransactions;
        this.featureSnapshots = featureSnapshots;
        this.json = json;
        this.clock = clock;
    }

    public LoanApplicationResponse create(LoanApplicationCreateRequest request) {
        String canonicalPayload = json.canonicalJson(request);
        String requestHash = json.sha256(canonicalPayload);

        return creationTransactions.findExisting(request.idempotencyKey(), requestHash)
                .orElseGet(() -> {
                    try {
                        return creationTransactions.createNew(request, requestHash);
                    } catch (DataIntegrityViolationException concurrentInsert) {
                        return creationTransactions.findExisting(request.idempotencyKey(), requestHash)
                                .orElseThrow(() -> concurrentInsert);
                    }
                });
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
        if (application.getStatus() != LoanApplicationStatus.UNDER_GOVERNANCE_REVIEW) {
            throw new InvalidStateTransitionException(
                    "Evidence request requires UNDER_GOVERNANCE_REVIEW status but was " + application.getStatus());
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
        rejectConflictingDecision(command);
        if (application.getStatus() != LoanApplicationStatus.UNDER_GOVERNANCE_REVIEW) {
            throw new InvalidStateTransitionException(
                    "Decision command requires UNDER_GOVERNANCE_REVIEW status but was " + application.getStatus());
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

    @Transactional
    public LoanApplicationResponse updateEvidence(EvidenceUpdateCommand command, FinancialSnapshotInput evidenceSnapshot) {
        if (command.applicationId() == null) {
            throw new IllegalArgumentException("Evidence update command applicationId is required");
        }
        if (command.causationId() == null) {
            throw new IllegalArgumentException("Evidence update command causationId is required");
        }
        if (command.evidenceRefs() == null || command.evidenceRefs().isEmpty()) {
            throw new IllegalArgumentException("Evidence update requires at least one evidenceRef");
        }
        validateEvidenceRequestCausation(command);

        String requestHash = json.sha256(json.canonicalJson(Map.of(
                "command", command,
                "snapshot", evidenceSnapshot
        )));
        var existingUpdate = evidenceUpdates.findById(command.causationId());
        if (existingUpdate.isPresent()) {
            EvidenceUpdateRequestEntity existing = existingUpdate.get();
            if (!existing.getApplicationId().equals(command.applicationId()) || !existing.getRequestHash().equals(requestHash)) {
                throw new ConflictException("Evidence request was already processed with a different payload");
            }
            return get(command.applicationId());
        }

        LoanApplicationEntity application = loadForUpdate(command.applicationId());
        if (application.getStatus() != LoanApplicationStatus.EVIDENCE_REQUIRED) {
            throw new InvalidStateTransitionException(
                    "Evidence update requires EVIDENCE_REQUIRED status but was " + application.getStatus());
        }

        Instant now = clock.instant();
        int nextSnapshotVersion = application.incrementSnapshotVersion(now);
        storeSnapshot(application, nextSnapshotVersion, evidenceSnapshot, now);

        transition(application, LoanApplicationStatus.UNDER_GOVERNANCE_REVIEW);
        evidenceUpdates.save(new EvidenceUpdateRequestEntity(
                command.causationId(),
                application.getApplicationId(),
                requestHash,
                nextSnapshotVersion,
                now
        ));
        outbox.save(evidenceUpdatedEvent(application.getApplicationId(), command, nextSnapshotVersion, now));
        log.info("Evidence updated applicationId={} snapshotVersion={}",
                application.getApplicationId(), nextSnapshotVersion);
        return toResponse(application);
    }

    private FinancialSnapshotEntity storeSnapshot(LoanApplicationEntity application, int version,
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
        featureSnapshots.createIfSourcePresent(application, snapshot, input, now);
        return snapshot;
    }

    private LoanApplicationEntity loadForUpdate(UUID applicationId) {
        return applications.findWithLockByApplicationId(applicationId)
                .orElseThrow(() -> new NotFoundException("Loan application not found: " + applicationId));
    }

    private void validateEvidenceRequestCausation(EvidenceUpdateCommand command) {
        InboxEventEntity cause = inbox.findById(command.causationId())
                .orElseThrow(() -> new IllegalArgumentException("Evidence update causationId was not received"));
        if (!"governance.evidence.requested.v1".equals(cause.getEventType())) {
            throw new IllegalArgumentException("Evidence update causationId must reference governance.evidence.requested.v1");
        }
        if (!command.applicationId().equals(cause.getApplicationId())) {
            throw new IllegalArgumentException("Evidence update applicationId does not match evidence request");
        }
        if (!command.decisionCaseId().equals(cause.getCaseId())) {
            throw new IllegalArgumentException("Evidence update decisionCaseId does not match evidence request");
        }
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
        if (!"governance-service".equals(event.producer())) {
            throw new IllegalArgumentException("Decision command producer must be governance-service");
        }
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
        if (!event.caseId().equals(command.decisionCaseId())) {
            throw new IllegalArgumentException("Envelope caseId and command decisionCaseId differ");
        }
        if (!event.applicationId().toString().equals(event.correlationId())) {
            throw new IllegalArgumentException("Phase 1 decision command correlationId must equal applicationId");
        }
        if (command.reasonCodes() == null || command.reasonCodes().isEmpty()) {
            throw new IllegalArgumentException("Decision command requires reasonCodes");
        }
        if (command.issuedAt() == null) {
            throw new IllegalArgumentException("Decision command requires issuedAt");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().length() < 8 || command.idempotencyKey().length() > 128) {
            throw new IllegalArgumentException("Decision command idempotencyKey length must be 8..128");
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
                    event.applicationId(),
                    event.caseId(),
                    json.sha256(event.payload().toString()),
                    clock.instant()
            ));
        } catch (DataIntegrityViolationException ignoredDuplicate) {
            log.info("Duplicate inbox event ignored eventId={} commandId={}", event.eventId(), commandId);
        }
    }

    private void rejectConflictingDecision(DecisionCommandPayload command) {
        decisions.findByApplicationApplicationId(command.applicationId()).ifPresent(existing -> {
            if (!existing.getCommandId().equals(command.commandId())) {
                log.warn("Conflicting decision command rejected applicationId={} existingCommandId={} newCommandId={}",
                        command.applicationId(), existing.getCommandId(), command.commandId());
                throw new ConflictException("Application already has a different decision command");
            }
        });
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

    private OutboxEventEntity evidenceUpdatedEvent(UUID applicationId, EvidenceUpdateCommand command,
                                                   int snapshotVersion, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", applicationId.toString());
        payload.put("decisionCaseId", command.decisionCaseId());
        payload.put("inputSnapshotVersion", "snapshot-v" + snapshotVersion);
        payload.put("evidenceRefs", List.copyOf(command.evidenceRefs()));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "loan.evidence.updated.v1");
        envelope.put("schemaVersion", EVENT_SCHEMA_VERSION);
        envelope.put("occurredAt", now.toString());
        envelope.put("producer", "loan-service");
        envelope.put("applicationId", applicationId.toString());
        envelope.put("caseId", command.decisionCaseId());
        envelope.put("evaluationRunId", null);
        envelope.put("correlationId", applicationId.toString());
        envelope.put("causationId", command.causationId().toString());
        envelope.put("payload", payload);

        return event("loan.evidence.updated.v1", applicationId, applicationId.toString(), command.causationId(), envelope, now);
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
