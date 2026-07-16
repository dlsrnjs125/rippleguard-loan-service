package dev.rippleguard.loan.interfaces.rest;

import dev.rippleguard.loan.domain.LoanApplicationStatus;
import java.time.Instant;
import java.util.UUID;

public record LoanApplicationResponse(
        String schemaVersion,
        UUID applicationId,
        LoanApplicationStatus status,
        String snapshotVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
