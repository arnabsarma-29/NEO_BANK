package com.project.authservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/**
 * Success envelope for every JSON response this service sends.
 *
 * <p>Immutable and framework-free. The HTTP status line and the headers live on the
 * {@code ResponseEntity} (see {@code Responses}); this record only carries the body.
 * Errors do not use this type: they are RFC 9457 {@code ProblemDetail} responses.
 *
 * @param <T>       payload type
 * @param status    HTTP status code, mirrored for clients that only look at the body
 * @param message   short human-readable summary
 * @param data      payload; left out of the JSON when {@code null}
 * @param timestamp UTC instant the response was produced (ISO-8601 in JSON)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int status, String message, @Nullable T data, Instant timestamp) {

    public static <T> ApiResponse<T> of(int status, String message, @Nullable T data) {
        return new ApiResponse<>(status, message, data, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
