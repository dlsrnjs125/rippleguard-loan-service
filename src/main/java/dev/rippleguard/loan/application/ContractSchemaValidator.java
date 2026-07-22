package dev.rippleguard.loan.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ContractSchemaValidator {
    public static final String FEATURE_PAYLOAD_SCHEMA =
            "contracts/schemas/domain/feature-payload.v1.0.0.schema.json";
    public static final String SNAPSHOT_REFERENCE_SCHEMA =
            "contracts/schemas/domain/snapshot-reference.v1.0.0.schema.json";

    private final ObjectMapper objectMapper;
    private final Map<String, JsonSchema> schemas;

    public ContractSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemas = Map.of(
                FEATURE_PAYLOAD_SCHEMA, load(FEATURE_PAYLOAD_SCHEMA),
                SNAPSHOT_REFERENCE_SCHEMA, load(SNAPSHOT_REFERENCE_SCHEMA)
        );
    }

    public void validate(String schemaPath, Map<String, Object> instance) {
        JsonNode node = objectMapper.valueToTree(instance);
        validate(schemaPath, node);
    }

    public void validate(String schemaPath, JsonNode instance) {
        JsonSchema schema = schemas.get(schemaPath);
        if (schema == null) {
            throw new IllegalStateException("Contract schema is not pinned: " + schemaPath);
        }
        Set<ValidationMessage> failures = schema.validate(instance);
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException("Contract validation failed for " + schemaPath + ": " + failures);
        }
    }

    private JsonSchema load(String schemaPath) {
        try (InputStream input = new ClassPathResource(schemaPath).getInputStream()) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Missing pinned contract schema: " + schemaPath, exception);
        }
    }
}
