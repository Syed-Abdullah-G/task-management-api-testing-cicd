package com.example.taskmanager.exception;

import com.example.taskmanager.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Central place where exceptions become consistent JSON error responses.
 * Exceptions are logged here exactly once; lower layers only throw.
 * Stack traces are logged for server errors but never sent to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String SERVER_ERROR_MESSAGE = "An unexpected error occurred";
    static final String SERVICE_UNAVAILABLE_MESSAGE = "The service is temporarily unavailable. Please try again later";
    static final String MALFORMED_REQUEST_MESSAGE = "Malformed JSON request or invalid field value";

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Object> handleTaskNotFound(TaskNotFoundException ex, WebRequest request) {
        log.warn("Task not found: {}", ex.getMessage());
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    @ExceptionHandler(InvalidTaskException.class)
    public ResponseEntity<Object> handleInvalidTask(InvalidTaskException ex, WebRequest request) {
        log.warn("Invalid task request: {}", ex.getMessage());
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Object> handleDataAccess(DataAccessException ex, WebRequest request) {
        log.error("Database access failure", ex);
        return respond(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE_MESSAGE, request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, SERVER_ERROR_MESSAGE, request, null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            String message = fieldError.getDefaultMessage() == null ? "Invalid value" : fieldError.getDefaultMessage();
            errors.merge(fieldError.getField(), message, (first, second) -> first + "; " + second);
        }
        log.warn("Validation failed for {}: invalid fields {}", path(request), errors.keySet());
        return respond(HttpStatus.BAD_REQUEST, "Validation failed", request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.warn("Unreadable request body for {}", path(request));
        return respond(HttpStatus.BAD_REQUEST, MALFORMED_REQUEST_MESSAGE, request, null);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String parameter = parameterName(ex);
        log.warn("Type mismatch for parameter '{}' on {}", parameter, path(request));
        return respond(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + parameter + "'", request, null);
    }

    /** Fallback for every other standard Spring MVC exception (405, 415, missing parameters, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String message;
        if (status.is5xxServerError()) {
            log.error("Server error while processing {}", path(request), ex);
            message = SERVER_ERROR_MESSAGE;
        } else {
            log.warn("Request rejected with {} for {}", status.value(), path(request));
            message = body instanceof ProblemDetail problem && problem.getDetail() != null
                    ? problem.getDetail()
                    : status.getReasonPhrase();
        }
        return respond(status, message, request, null);
    }

    private static ResponseEntity<Object> respond(
            HttpStatus status, String message, WebRequest request, Map<String, String> validationErrors) {
        ApiError body = new ApiError(
                Instant.now(), status.value(), status.getReasonPhrase(), message, path(request), validationErrors);
        return ResponseEntity.status(status).body(body);
    }

    /** MethodArgumentTypeMismatchException carries the parameter name in getName(), not getPropertyName(). */
    private static String parameterName(TypeMismatchException ex) {
        if (ex instanceof MethodArgumentTypeMismatchException methodArgumentException) {
            return methodArgumentException.getName();
        }
        return String.valueOf(ex.getPropertyName());
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : "unknown";
    }
}
