package dev.rippleguard.loan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialSnapshotRepository extends JpaRepository<FinancialSnapshotEntity, UUID> {
    Optional<FinancialSnapshotEntity> findByApplicationApplicationIdAndSnapshotVersion(
            UUID applicationId, String snapshotVersion);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select snapshot from FinancialSnapshotEntity snapshot where snapshot.snapshotId = :snapshotId")
    Optional<FinancialSnapshotEntity> findLockedBySnapshotId(@Param("snapshotId") UUID snapshotId);
}
