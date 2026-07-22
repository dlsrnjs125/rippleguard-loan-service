package dev.rippleguard.loan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanFeatureSnapshotRepository extends JpaRepository<LoanFeatureSnapshotEntity, UUID> {
    Optional<LoanFeatureSnapshotEntity> findByApplicationApplicationIdAndSnapshotVersion(
            UUID applicationId, String snapshotVersion);

    @Modifying
    @Query(value = """
            insert into loan_feature_snapshot (
                snapshot_id,
                application_id,
                financial_snapshot_id,
                snapshot_version,
                snapshot_schema_version,
                feature_schema_version,
                feature_payload,
                feature_payload_digest,
                snapshot_reference,
                source_loan_application_version,
                created_at
            )
            values (
                :snapshotId,
                :applicationId,
                :financialSnapshotId,
                :snapshotVersion,
                :snapshotSchemaVersion,
                :featureSchemaVersion,
                cast(:featurePayload as jsonb),
                :featurePayloadDigest,
                cast(:snapshotReference as jsonb),
                :sourceLoanApplicationVersion,
                :createdAt
            )
            on conflict (application_id, snapshot_version) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("snapshotId") UUID snapshotId,
                       @Param("applicationId") UUID applicationId,
                       @Param("financialSnapshotId") UUID financialSnapshotId,
                       @Param("snapshotVersion") String snapshotVersion,
                       @Param("snapshotSchemaVersion") String snapshotSchemaVersion,
                       @Param("featureSchemaVersion") String featureSchemaVersion,
                       @Param("featurePayload") String featurePayload,
                       @Param("featurePayloadDigest") String featurePayloadDigest,
                       @Param("snapshotReference") String snapshotReference,
                       @Param("sourceLoanApplicationVersion") int sourceLoanApplicationVersion,
                       @Param("createdAt") java.time.Instant createdAt);
}
