package dev.dsatracker.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {
    final HttpStatus status;
    final Map<String, String> fields;

    private ApiException(HttpStatus status, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.fields = fields;
    }

    public static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of(field, message));
    }

    public static ApiException missing(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, Map.of());
    }

    public static ApiException conflict(String field, String message) {
        return new ApiException(HttpStatus.CONFLICT, message, Map.of(field, message));
    }
}
