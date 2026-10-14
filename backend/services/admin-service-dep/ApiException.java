package com.project.authservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Base class for business errors, e.g. {@code throw ApiException.notFound("User not found")}.
 *
 * <p>Extending {@link ErrorResponseException} means Spring already renders it as an RFC 9457
 * problem response, so {@link GlobalExceptionHandler} needs no extra method for it. Subclass it
 * for domain-specific errors. The {@code detail} text goes to the client as is, so write it
 * for users and keep internals out.
 *
 * <p>For 401 and 403 throw Spring Security's {@code AuthenticationException} and
 * {@code AccessDeniedException} instead; the handler adds the required headers.
 */
public class ApiException extends ErrorResponseException {

    private static final long serialVersionUID = 1L;

    public ApiException(HttpStatus status, String detail) {
        super(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }
}
