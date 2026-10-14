package com.project.authservice.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into an RFC 9457 {@code application/problem+json} response.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} already covers Spring MVC's own errors
 * (malformed JSON, missing parameter, 404, 405, 415, ...). This class adds field-level detail
 * for validation failures and maps the common application errors. Business errors: throw
 * {@link ApiException}, no handler method needed.
 *
 * <p>Responses never echo rejected values or raw exception messages (PII, internals).
 * Delete the Spring Security and Spring Data sections in projects that do not use them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** One invalid field or parameter in a validation error response. */
    public record FieldViolation(String field, String message) {
    }

    // ---------------------------------------------------------------- validation (400)

    /** {@code @Valid @RequestBody}, and {@code @Valid} form objects. */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ex.getFieldErrors().forEach(e -> errors.add(new FieldViolation(e.getField(), messageOf(e))));
        ex.getGlobalErrors().forEach(e -> errors.add(new FieldViolation(e.getObjectName(), messageOf(e))));
        return validationFailed(ex, errors, headers, status, request);
    }

    /** Constraints placed directly on controller parameters, e.g. {@code @RequestParam @Min(1) int page}. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String parameter = parameterName(result.getMethodParameter());
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError ? fieldError.getField() : parameter;
                errors.add(new FieldViolation(field, messageOf(error)));
            }
        }
        return validationFailed(ex, errors, headers, status, request);
    }

    /** {@code @Validated} beans: class-level {@code @Validated} controllers and the service layer. */
    @ExceptionHandler(ConstraintViolationException.class)
    protected @Nullable ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException ex, WebRequest request) {
        Set<ConstraintViolation<?>> violations = Objects.requireNonNullElse(ex.getConstraintViolations(), Set.of());
        List<FieldViolation> errors = violations.stream()
                .map(violation -> new FieldViolation(leafName(violation), violation.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        return validationFailed(ex, errors, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    // ---------------------------------------------------------------- security (401 / 403)
    // Needed: without these, the catch-all below would turn @PreAuthorize failures into 500s.
    // Exceptions thrown inside security filters (e.g. a JWT filter) never reach this class;
    // handle those with an AuthenticationEntryPoint / AccessDeniedHandler.

    @ExceptionHandler(AuthenticationException.class)
    protected @Nullable ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed.");
        return respond(ex, problem, headers, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    protected @Nullable ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return respond(ex, HttpStatus.FORBIDDEN, "You do not have permission to perform this action.", request);
    }

    // ---------------------------------------------------------------- data (409)

    /** Typically a unique-constraint hit such as a duplicate email. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    protected @Nullable ResponseEntity<Object> handleDataIntegrity(
            DataIntegrityViolationException ex, WebRequest request) {
        log.debug("Data integrity violation on {}", request.getDescription(false), ex);
        return respond(ex, HttpStatus.CONFLICT, "The request conflicts with existing data.", request);
    }

    // ---------------------------------------------------------------- fallback (500)

    /** Anything unmapped. The client gets an errorId; the stack trace stays in the log under the same id. */
    @ExceptionHandler(Exception.class)
    protected @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled exception [errorId={}] on {}", errorId, request.getDescription(false), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Quote the errorId when contacting support.");
        problem.setProperty("errorId", errorId);
        return respond(ex, problem, new HttpHeaders(), request);
    }

    // ---------------------------------------------------------------- plumbing

    /** Every problem response, including Spring's own, carries the same timestamp field as {@code ApiResponse}. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            problem.setProperty("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private @Nullable ResponseEntity<Object> validationFailed(
            Exception ex, List<FieldViolation> errors, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed.");
        problem.setProperty("errors", errors);
        return respond(ex, problem, headers, request);
    }

    private @Nullable ResponseEntity<Object> respond(
            Exception ex, HttpStatus status, String detail, WebRequest request) {
        return respond(ex, ProblemDetail.forStatusAndDetail(status, detail), new HttpHeaders(), request);
    }

    /** The problem's status is the single source for the HTTP status, so body and status line cannot disagree. */
    private @Nullable ResponseEntity<Object> respond(
            Exception ex, ProblemDetail problem, HttpHeaders headers, WebRequest request) {
        return handleExceptionInternal(ex, problem, headers, HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    private static String parameterName(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
    }

    private static String messageOf(MessageSourceResolvable error) {
        return Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value");
    }

    /** {@code register.request.address.street} becomes {@code street}; internal method names stay out of responses. */
    private static String leafName(ConstraintViolation<?> violation) {
        String name = "";
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getKind() != ElementKind.CONTAINER_ELEMENT && node.getName() != null) {
                name = node.getName();
            }
        }
        return name;
    }
}
