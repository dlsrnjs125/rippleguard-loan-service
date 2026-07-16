package dev.rippleguard.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rippleguard.loan.application.DecisionCommandPayload;
import dev.rippleguard.loan.application.EventEnvelope;
import dev.rippleguard.loan.domain.FinalDecision;
import dev.rippleguard.loan.interfaces.rest.LoanApplicationCreateRequest;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class ContractFixtureDeserializationTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void deserializesPinnedPhase1EventFixtures() throws Exception {
        EventEnvelope reviewStarted = event("governance.review.started.v1.json");
        EventEnvelope evidenceRequested = event("governance.evidence.requested.v1.json");
        EventEnvelope decisionCommanded = event("loan.decision.commanded.v1.json");
        EventEnvelope evidenceUpdated = event("loan.evidence.updated.v1.json");

        assertThat(reviewStarted.schemaVersion()).isEqualTo("1.1.0");
        assertThat(evidenceRequested.eventType()).isEqualTo("governance.evidence.requested.v1");
        assertThat(evidenceUpdated.producer()).isEqualTo("loan-service");

        DecisionCommandPayload command = objectMapper.treeToValue(decisionCommanded.payload(), DecisionCommandPayload.class);
        assertThat(command.finalDecision()).isEqualTo(FinalDecision.APPROVE);
        assertThat(command.reasonCodes()).containsExactly("GOVERNANCE_VERIFIED_PROPOSAL");
    }

    @Test
    void deserializesPinnedLoanApplicationCreateRequest() throws Exception {
        LoanApplicationCreateRequest request = read(
                "/contracts/rest/loan-application-create-request.json",
                LoanApplicationCreateRequest.class
        );

        assertThat(request.schemaVersion()).isEqualTo("1.0.0");
        assertThat(request.idempotencyKey()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(request.incomeHistory()).isNotEmpty();
    }

    private EventEnvelope event(String name) throws Exception {
        return read("/contracts/events/v1.1.0/" + name, EventEnvelope.class);
    }

    private <T> T read(String path, Class<T> type) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return objectMapper.readValue(stream, type);
        }
    }
}
