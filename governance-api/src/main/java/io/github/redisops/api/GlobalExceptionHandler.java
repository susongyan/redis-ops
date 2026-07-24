package io.github.redisops.api;

import io.github.redisops.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    record ErrorBody(String code, String message, String requestId, List<String> details) {
    }
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorBody> business(BusinessException e, HttpServletRequest request) {
        HttpStatus status = switch (e.code()) {
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "CONCURRENT_MODIFICATION", "RESOURCE_IN_USE", "IDEMPOTENCY_CONFLICT", "REQUEST_IN_PROGRESS" ->
                HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ErrorBody(e.code(), e.getMessage(), requestId(request), List.of()));
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorBody> duplicate(DuplicateKeyException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("DUPLICATE_RESOURCE", "resource already exists", requestId(request), List.of()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(x -> x.getField() + ": " + x.getDefaultMessage()).toList();
        return ResponseEntity.badRequest()
                .body(new ErrorBody("VALIDATION_FAILED", "request validation failed", requestId(request), details));
    }
    private static String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
