package dev.rippleguard.loan.application;

import dev.rippleguard.loan.infrastructure.persistence.FinancialSnapshotEntity;
import dev.rippleguard.loan.infrastructure.persistence.LoanApplicationEntity;
import dev.rippleguard.loan.infrastructure.persistence.LoanFeatureSnapshotEntity;
import dev.rippleguard.loan.infrastructure.persistence.LoanFeatureSnapshotRepository;
import dev.rippleguard.loan.interfaces.rest.Phase2FeatureSnapshotResponse;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Phase2FeatureSnapshotService {
    private static final String SNAPSHOT_SCHEMA_VERSION = "1.0.0";
    private static final String FEATURE_SCHEMA_VERSION = "phase-2-loan-features.v1.0.0";
    private static final String FEATURE_PAYLOAD_SCHEMA_VERSION = "1.0.0";
    private static final Pattern ISO_MONTH_PERIOD = Pattern.compile("^P(\\d+)M$");
    private static final Pattern MONTH_COUNT_PERIOD = Pattern.compile("^(\\d+)-months$");
    private static final Pattern QUARTER_PERIOD = Pattern.compile("^\\d{4}-Q[1-4]$");
    private static final int FEATURE_SCALE = 6;

    private final LoanFeatureSnapshotRepository snapshots;
    private final ContractSchemaValidator contracts;
    private final JsonSupport json;
    private final boolean atomicInsertSupported;

    public Phase2FeatureSnapshotService(LoanFeatureSnapshotRepository snapshots,
                                        ContractSchemaValidator contracts,
                                        JsonSupport json,
                                        DataSource dataSource) {
        this.snapshots = snapshots;
        this.contracts = contracts;
        this.json = json;
        this.atomicInsertSupported = supportsAtomicInsert(dataSource);
    }

    @Transactional
    public Optional<LoanFeatureSnapshotEntity> createIfSourcePresent(
            LoanApplicationEntity application, FinancialSnapshotEntity financialSnapshot,
            FinancialSnapshotInput input, Instant now) {
        if (input.phase2FeatureSource() == null) {
            return Optional.empty();
        }
        return Optional.of(insertOrReturnExisting(application, financialSnapshot, input, now));
    }

    private LoanFeatureSnapshotEntity insertOrReturnExisting(
            LoanApplicationEntity application, FinancialSnapshotEntity financialSnapshot,
            FinancialSnapshotInput input, Instant now) {
        PreparedSnapshot prepared = prepare(application, financialSnapshot, input, now);
        return insertPrepared(application, financialSnapshot, prepared);
    }

    private LoanFeatureSnapshotEntity insertPrepared(
            LoanApplicationEntity application, FinancialSnapshotEntity financialSnapshot,
            PreparedSnapshot prepared) {
        if (atomicInsertSupported) {
            snapshots.insertIfAbsent(
                    prepared.snapshotId(),
                    application.getApplicationId(),
                    financialSnapshot.getSnapshotId(),
                    prepared.snapshotVersion(),
                    SNAPSHOT_SCHEMA_VERSION,
                    FEATURE_SCHEMA_VERSION,
                    prepared.featurePayloadJson(),
                    prepared.featurePayloadDigest(),
                    prepared.snapshotReferenceJson(),
                    application.getSnapshotVersion(),
                    prepared.createdAt()
            );
        } else {
            var existing = snapshots.findByApplicationApplicationIdAndSnapshotVersion(
                    application.getApplicationId(), prepared.snapshotVersion());
            if (existing.isEmpty()) {
                snapshots.saveAndFlush(new LoanFeatureSnapshotEntity(
                        prepared.snapshotId(),
                        application,
                        financialSnapshot,
                        prepared.snapshotVersion(),
                        SNAPSHOT_SCHEMA_VERSION,
                        FEATURE_SCHEMA_VERSION,
                        prepared.featurePayloadJson(),
                        prepared.featurePayloadDigest(),
                        prepared.snapshotReferenceJson(),
                        application.getSnapshotVersion(),
                        prepared.createdAt()
                ));
            }
        }
        return snapshots.findByApplicationApplicationIdAndSnapshotVersion(
                        application.getApplicationId(), prepared.snapshotVersion())
                .map(existing -> requireSameDigest(existing, prepared.featurePayloadDigest()))
                .orElseThrow(() -> new IllegalStateException(
                        "Feature snapshot insert did not create or find row: " + application.getApplicationId()
                                + " " + prepared.snapshotVersion()));
    }

    private PreparedSnapshot prepare(LoanApplicationEntity application, FinancialSnapshotEntity financialSnapshot,
                                     FinancialSnapshotInput input, Instant now) {
        String snapshotVersion = financialSnapshot.getSnapshotVersion();
        Map<String, Object> featurePayloadWithoutDigest = featurePayloadWithoutDigest(input);
        String featurePayloadDigest = "sha256:" + json.sha256(json.canonicalJson(featurePayloadWithoutDigest));
        Map<String, Object> featurePayload = new LinkedHashMap<>(featurePayloadWithoutDigest);
        featurePayload.put("featurePayloadDigest", featurePayloadDigest);
        contracts.validate(ContractSchemaValidator.FEATURE_PAYLOAD_SCHEMA, featurePayload);

        UUID snapshotId = UUID.randomUUID();
        Map<String, Object> snapshotReference = new LinkedHashMap<>();
        snapshotReference.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
        snapshotReference.put("snapshotId", snapshotId.toString());
        snapshotReference.put("snapshotVersion", snapshotVersion);
        snapshotReference.put("snapshotSchemaVersion", SNAPSHOT_SCHEMA_VERSION);
        snapshotReference.put("snapshotCreatedAt", now.toString());
        snapshotReference.put("digestAlgorithm", "sha256");
        snapshotReference.put("snapshotDigest", featurePayloadDigest);
        snapshotReference.put("snapshotReference",
                "snapshot://loan-feature/" + application.getApplicationId() + "/" + snapshotVersion);
        snapshotReference.put("referenceType", "MATERIALIZED_FEATURES");
        contracts.validate(ContractSchemaValidator.SNAPSHOT_REFERENCE_SCHEMA, snapshotReference);

        return new PreparedSnapshot(
                snapshotId,
                snapshotVersion,
                json.canonicalJson(featurePayload),
                featurePayloadDigest,
                json.canonicalJson(snapshotReference),
                now
        );
    }

    @Transactional(readOnly = true)
    public Phase2FeatureSnapshotResponse get(UUID applicationId, String snapshotVersion) {
        return snapshots.findByApplicationApplicationIdAndSnapshotVersion(applicationId, snapshotVersion)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException(
                        "Phase 2 feature snapshot not found: " + applicationId + " " + snapshotVersion));
    }

    private Phase2FeatureSnapshotResponse toResponse(LoanFeatureSnapshotEntity snapshot) {
        return new Phase2FeatureSnapshotResponse(
                "1.0.0",
                snapshot.getSnapshotId(),
                snapshot.getApplication().getApplicationId(),
                snapshot.getSnapshotVersion(),
                snapshot.getSnapshotSchemaVersion(),
                snapshot.getFeatureSchemaVersion(),
                json.fromJsonObject(snapshot.getSnapshotReference()),
                json.fromJsonObject(snapshot.getFeaturePayload()),
                snapshot.getFeaturePayloadDigest(),
                snapshot.getSourceLoanApplicationVersion(),
                snapshot.getCreatedAt()
        );
    }

    private Map<String, Object> featurePayloadWithoutDigest(FinancialSnapshotInput input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", FEATURE_PAYLOAD_SCHEMA_VERSION);
        payload.put("featureSchemaVersion", FEATURE_SCHEMA_VERSION);
        payload.put("features", features(input));
        return payload;
    }

    private Map<String, Object> features(FinancialSnapshotInput input) {
        List<BigDecimal> monthlyIncomeAmounts = input.incomeHistory().stream()
                .map(income -> money(income.amount()))
                .toList();
        if (monthlyIncomeAmounts.isEmpty()) {
            throw new IllegalArgumentException("Phase 2 feature snapshot requires income history");
        }

        BigDecimal monthlyIncomeMean = mean(monthlyIncomeAmounts);
        if (monthlyIncomeMean.signum() <= 0) {
            throw new IllegalArgumentException("Phase 2 feature snapshot requires positive monthly income mean");
        }
        BigDecimal monthlyIncomeVolatility = coefficientOfVariation(monthlyIncomeAmounts, monthlyIncomeMean);
        BigDecimal annualIncome = monthlyIncomeMean.multiply(BigDecimal.valueOf(12));
        BigDecimal monthlyDebtPayment = money(input.debtSummary().monthlyPaymentAmount());
        BigDecimal debtToIncomeRatio = divide(monthlyDebtPayment, monthlyIncomeMean);
        int settlementMonths = settlementMonths(input.platformSettlementSummary().period());
        BigDecimal platformSettlementMean = divide(
                money(input.platformSettlementSummary().grossSettlementAmount()),
                BigDecimal.valueOf(settlementMonths));

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("annualIncome", number(annualIncome));
        features.put("monthlyIncomeMean", number(monthlyIncomeMean));
        features.put("monthlyIncomeVolatility", number(monthlyIncomeVolatility));
        features.put("debtToIncomeRatio", number(debtToIncomeRatio));
        features.put("existingDebtAmount", number(money(input.debtSummary().totalOutstandingAmount())));
        features.put("delinquencyCount", input.delinquencySummary().delinquencyCount());
        features.put("platformSettlementMonths", settlementMonths);
        features.put("platformSettlementMean", number(platformSettlementMean));
        features.put("platformSettlementVolatility", number(
                new BigDecimal(input.phase2FeatureSource().platformSettlementVolatility().value())));
        features.put("contractDurationMonths", input.phase2FeatureSource().contractDuration().value());
        features.put("incomeDeclarationAvailable", input.phase2FeatureSource().incomeDeclaration().available());
        features.put("telecomPaymentDelinquencyCount",
                input.phase2FeatureSource().telecomDelinquency().value());
        return features;
    }

    private LoanFeatureSnapshotEntity requireSameDigest(LoanFeatureSnapshotEntity existing, String incomingDigest) {
        if (!existing.getFeaturePayloadDigest().equals(incomingDigest)) {
            throw new ConflictException("FEATURE_SNAPSHOT_CONFLICT");
        }
        return existing;
    }

    private BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return divide(sum, BigDecimal.valueOf(values.size()));
    }

    private BigDecimal coefficientOfVariation(List<BigDecimal> values, BigDecimal mean) {
        if (values.size() == 1) {
            return BigDecimal.ZERO;
        }
        double average = mean.doubleValue();
        double variance = values.stream()
                .map(BigDecimal::doubleValue)
                .mapToDouble(value -> Math.pow(value - average, 2))
                .average()
                .orElse(0.0);
        return BigDecimal.valueOf(Math.sqrt(variance) / average);
    }

    private int settlementMonths(String period) {
        Matcher isoMatcher = ISO_MONTH_PERIOD.matcher(period);
        if (isoMatcher.matches()) {
            return positiveMonthCount(isoMatcher.group(1), period);
        }
        Matcher monthCountMatcher = MONTH_COUNT_PERIOD.matcher(period);
        if (monthCountMatcher.matches()) {
            return positiveMonthCount(monthCountMatcher.group(1), period);
        }
        if (QUARTER_PERIOD.matcher(period).matches()) {
            return 3;
        }
        throw new IllegalArgumentException("Unsupported Phase 2 settlement period: " + period);
    }

    private int positiveMonthCount(String value, String period) {
        int months = Integer.parseInt(value);
        if (months <= 0) {
            throw new IllegalArgumentException("Unsupported Phase 2 settlement period: " + period);
        }
        return months;
    }

    private BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        return dividend.divide(divisor, FEATURE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private BigDecimal number(BigDecimal value) {
        return value.setScale(FEATURE_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private boolean supportsAtomicInsert(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to detect database product", exception);
        }
    }

    private record PreparedSnapshot(UUID snapshotId, String snapshotVersion, String featurePayloadJson,
                                    String featurePayloadDigest, String snapshotReferenceJson, Instant createdAt) {
    }
}
