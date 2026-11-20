package dev.dsatracker.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import tools.jackson.databind.DatabindException;

@RestControllerAdvice(basePackages = {"dev.dsatracker.catalog", "dev.dsatracker.planning", "dev.dsatracker.reporting",
        "dev.dsatracker.revision", "dev.dsatracker.exchange", "dev.dsatracker.backup"})
class ApiErrorHandler {
    record ErrorResponse(String code, String message, Map<String, String> fields) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleDomainError(ApiException exception) {
        return response(exception.status, exception.getMessage(), exception.fields);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value.")));
        return response(HttpStatus.BAD_REQUEST, "Check the highlighted fields.", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        if (exception.getCause() instanceof DatabindException error && !error.getPath().isEmpty()) {
            String field = error.getPath().getFirst().getPropertyName();
            if (field != null) {
                return response(HttpStatus.BAD_REQUEST, "Check the highlighted fields.", Map.of(field, "Invalid value."));
            }
        }
        return response(HttpStatus.BAD_REQUEST, "The request body must contain valid JSON.", Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleParameter(MethodArgumentTypeMismatchException exception) {
        return response(HttpStatus.BAD_REQUEST, "Invalid request parameter.",
                Map.of(exception.getName(), "Invalid value."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException exception) {
        return response(HttpStatus.BAD_REQUEST, "Provide the required request parameter.",
                Map.of(exception.getParameterName(), "This parameter is required."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException exception) {
        return response(HttpStatus.BAD_REQUEST, "Provide the required file.",
                Map.of(exception.getRequestPartName(), "This file is required."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleConstraint() {
        return response(HttpStatus.CONFLICT, "The change conflicts with existing tracker data.", Map.of());
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String message, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.name(), message, fields));
    }
}
