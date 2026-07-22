package dev.rippleguard.loan.interfaces.rest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Phase2FeatureSnapshotResponse(
        String schemaVersion,
        UUID snapshotId,
        UUID applicationId,
        String snapshotVersion,
        String snapshotSchemaVersion,
        String featureSchemaVersion,
        Map<String, Object> snapshotReference,
        Map<String, Object> featurePayload,
        String featurePayloadDigest,
        int sourceLoanApplicationVersion,
        Instant createdAt
) {
}
