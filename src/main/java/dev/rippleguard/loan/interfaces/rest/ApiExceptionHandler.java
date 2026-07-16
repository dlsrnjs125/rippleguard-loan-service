package dev.rippleguard.loan.interfaces.rest;

import dev.rippleguard.loan.application.ConflictException;
import dev.rippleguard.loan.application.InvalidStateTransitionException;
import dev.rippleguard.loan.application.NotFoundException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
        return error(HttpStatus.BAD_REQUEST, "ValidationError", exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ConflictException exception) {
        return error(HttpStatus.CONFLICT, "Conflict", exception.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ResponseEntity<Map<String, Object>> semantic(InvalidStateTransitionException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "SemanticValidationError", exception.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "NotFound", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "ValidationError", exception.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", code,
                "message", message
        ));
    }
}
