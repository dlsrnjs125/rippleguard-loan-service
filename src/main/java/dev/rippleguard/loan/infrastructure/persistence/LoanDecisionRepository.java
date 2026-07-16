package dev.rippleguard.loan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanDecisionRepository extends JpaRepository<LoanDecisionEntity, UUID> {
    boolean existsByCommandId(UUID commandId);

    boolean existsByApplicationApplicationId(UUID applicationId);

    Optional<LoanDecisionEntity> findByApplicationApplicationId(UUID applicationId);
}
