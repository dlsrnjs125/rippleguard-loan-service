package dev.rippleguard.loan.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyIncomeRepository extends JpaRepository<MonthlyIncomeEntity, UUID> {
}
