package dev.rippleguard.loan.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventRepository extends JpaRepository<InboxEventEntity, UUID> {
    boolean existsByCommandId(UUID commandId);
}
