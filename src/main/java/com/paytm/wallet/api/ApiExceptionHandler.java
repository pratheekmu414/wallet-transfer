package com.paytm.wallet.api;

import com.paytm.wallet.domain.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return body(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidBody(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fieldName(fe) + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "bad_request", detail.isEmpty() ? "invalid request body" : detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleUnreadable(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", "malformed request: " + rootMessage(ex));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException ex) {
        // e.g. a transfer referencing a wallet id that does not exist (FK), or a check constraint.
        log.warn("data integrity violation: {}", rootMessage(ex));
        return body(HttpStatus.BAD_REQUEST, "bad_request", "request violates a data constraint");
    }

    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<Map<String, Object>> handleTransient(Exception ex) {
        // Deadlock victim that outlived its retries, lock timeout, or the DB briefly unreachable.
        // Nothing was applied (the transaction rolled back) — the client should retry with the same
        // idempotency key.
        log.warn("transient database failure: {}", rootMessage(ex));
        return body(HttpStatus.SERVICE_UNAVAILABLE, "try_again", "temporary database contention, retry");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "internal error");
    }

    private static String fieldName(FieldError fe) {
        return fe.getField();
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        return ResponseEntity.status(status).body(payload);
    }
}
