package dev.rippleguard.loan.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonSupport {
    private final ObjectMapper objectMapper;

    public JsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String canonicalJson(Object value) {
        try {
            JsonNode tree = sortObjectFields(objectMapper.valueToTree(value));
            ObjectMapper canonicalMapper = objectMapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
            return canonicalMapper.writeValueAsString(tree);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize JSON", exception);
        }
    }

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fromJsonObject(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    private JsonNode sortObjectFields(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode sortedArray = objectMapper.createArrayNode();
            node.forEach(child -> sortedArray.add(sortObjectFields(child)));
            return sortedArray;
        }
        ObjectNode sortedObject = objectMapper.createObjectNode();
        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        fieldNames.stream().sorted().forEach(fieldName ->
                sortedObject.set(fieldName, sortObjectFields(node.get(fieldName))));
        return sortedObject;
    }
}
