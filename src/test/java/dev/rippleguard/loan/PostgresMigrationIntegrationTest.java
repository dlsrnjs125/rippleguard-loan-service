package dev.rippleguard.loan;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import dev.rippleguard.loan.domain.OutboxStatus;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventEntity;
import dev.rippleguard.loan.infrastructure.persistence.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "debug=false",
        "rippleguard.kafka.enabled=false",
        "management.health.kafka.enabled=false"
})
class PostgresMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("rippleguard_loan")
            .withUsername("rippleguard_loan")
            .withPassword("rippleguard_loan");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    OutboxEventRepository outbox;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void appliesFlywayMigrationOnPostgreSql() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
            var result = statement.executeQuery("select count(*) from outbox_event")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void claimsOutboxRowsWithPostgreSqlSkipLockedQuery() {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        outbox.save(new OutboxEventEntity(
                eventId,
                "loan.application.submitted.v1",
                "1.1.0",
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                null,
                "{\"eventType\":\"loan.application.submitted.v1\"}",
                now
        ));

        var claimed = transactions.execute(status -> outbox.findClaimable(now, 10));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getEventId()).isEqualTo(eventId);
        claimed.get(0).markProcessing(now);
        transactions.executeWithoutResult(status -> outbox.save(claimed.get(0)));

        assertThat(outbox.findById(eventId)).get()
                .extracting(OutboxEventEntity::getStatus)
                .isEqualTo(OutboxStatus.PROCESSING);
    }
}
