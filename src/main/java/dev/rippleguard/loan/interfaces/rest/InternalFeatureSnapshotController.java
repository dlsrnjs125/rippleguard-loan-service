package dev.rippleguard.loan.interfaces.rest;

import dev.rippleguard.loan.application.InternalApiProperties;
import dev.rippleguard.loan.application.Phase2FeatureSnapshotService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/api/v1/loan-applications")
public class InternalFeatureSnapshotController {
    private static final String SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private final Phase2FeatureSnapshotService snapshots;
    private final InternalApiProperties properties;

    public InternalFeatureSnapshotController(Phase2FeatureSnapshotService snapshots,
                                             InternalApiProperties properties) {
        this.snapshots = snapshots;
        this.properties = properties;
    }

    @GetMapping("/{applicationId}/phase2-feature-snapshots/{snapshotVersion}")
    Phase2FeatureSnapshotResponse getFeatureSnapshot(
            @PathVariable UUID applicationId,
            @PathVariable String snapshotVersion,
            @RequestHeader(name = SERVICE_TOKEN_HEADER, required = false) String serviceToken) {
        requireServiceToken(serviceToken);
        return snapshots.get(applicationId, snapshotVersion);
    }

    private void requireServiceToken(String providedToken) {
        byte[] expected = properties.serviceToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = providedToken == null ? new byte[0] : providedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service token");
        }
    }
}
