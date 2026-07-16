package dev.rippleguard.loan.interfaces.rest;

import dev.rippleguard.loan.application.LoanApplicationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-applications")
public class LoanApplicationController {
    private final LoanApplicationService service;

    public LoanApplicationController(LoanApplicationService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<LoanApplicationResponse> create(@Valid @RequestBody LoanApplicationCreateRequest request) {
        LoanApplicationResponse response = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/loan-applications/" + response.applicationId()))
                .body(response);
    }

    @GetMapping("/{applicationId}")
    LoanApplicationResponse get(@PathVariable UUID applicationId) {
        return service.get(applicationId);
    }
}
