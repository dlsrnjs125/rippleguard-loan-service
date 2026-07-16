package dev.rippleguard.loan.infrastructure.persistence;

import dev.rippleguard.loan.domain.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    List<OutboxEventEntity> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAt(
            List<OutboxStatus> statuses, Instant now, Pageable pageable);
}
