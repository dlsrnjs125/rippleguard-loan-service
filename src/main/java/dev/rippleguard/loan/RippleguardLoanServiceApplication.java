package dev.rippleguard.loan;

import dev.rippleguard.loan.application.InternalApiProperties;
import dev.rippleguard.loan.infrastructure.kafka.OutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties({OutboxProperties.class, InternalApiProperties.class})
@SpringBootApplication
public class RippleguardLoanServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(RippleguardLoanServiceApplication.class, args);
	}

}
