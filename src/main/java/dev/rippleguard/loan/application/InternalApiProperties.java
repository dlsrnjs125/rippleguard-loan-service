package dev.rippleguard.loan.application;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "rippleguard.internal-api")
public record InternalApiProperties(
        @NotBlank String serviceToken
) {
}
