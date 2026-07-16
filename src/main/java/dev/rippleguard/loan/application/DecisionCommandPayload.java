package dev.rippleguard.loan.application;

import dev.rippleguard.loan.domain.FinalDecision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DecisionCommandPayload(
        UUID commandId,
        String decisionCaseId,
        UUID applicationId,
        UUID decisionId,
        UUID evaluationRunId,
        String evaluationRunStatus,
        FinalDecision finalDecision,
        String assuranceResult,
        List<String> reasonCodes,
        Instant issuedAt,
        String idempotencyKey
) {
}
