package dev.rippleguard.loan.application;

import java.util.List;
import java.util.UUID;

public record EvidenceUpdateCommand(
        UUID applicationId,
        String decisionCaseId,
        UUID causationId,
        List<String> evidenceRefs
) {
}
