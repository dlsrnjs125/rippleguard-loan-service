package dev.rippleguard.loan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface LoanApplicationRepository extends JpaRepository<LoanApplicationEntity, UUID> {
    Optional<LoanApplicationEntity> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.OPTIMISTIC)
    Optional<LoanApplicationEntity> findWithLockByApplicationId(UUID applicationId);
}
