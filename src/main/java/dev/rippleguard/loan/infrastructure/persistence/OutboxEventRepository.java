package dev.rippleguard.loan.infrastructure.persistence;

import dev.rippleguard.loan.domain.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    @Query(
            value = """
                    select *
                    from outbox_event
                    where status in ('PENDING', 'FAILED')
                      and next_attempt_at <= :now
                    order by created_at
                    for update skip locked
                    limit :batchSize
                    """,
            nativeQuery = true
    )
    List<OutboxEventEntity> findClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
