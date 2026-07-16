package dev.rippleguard.loan.application;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
