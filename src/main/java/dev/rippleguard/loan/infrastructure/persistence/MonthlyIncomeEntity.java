package dev.rippleguard.loan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "monthly_income", uniqueConstraints = @UniqueConstraint(
        name = "uq_monthly_income_period", columnNames = {"snapshot_id", "period"}))
public class MonthlyIncomeEntity {
    @Id
    @Column(name = "monthly_income_id")
    private UUID monthlyIncomeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private FinancialSnapshotEntity snapshot;

    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "source_reference", nullable = false)
    private String sourceReference;

    protected MonthlyIncomeEntity() {
    }

    public MonthlyIncomeEntity(UUID monthlyIncomeId, FinancialSnapshotEntity snapshot, String period,
                               BigDecimal amount, String sourceReference) {
        this.monthlyIncomeId = monthlyIncomeId;
        this.snapshot = snapshot;
        this.period = period;
        this.amount = amount;
        this.sourceReference = sourceReference;
    }
}
