package com.project.authservice.web;
import com.project.authservice.dto.ApiResponse;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/**
 * Single place where a {@link ResponseEntity} (status + headers + body) is assembled.
 *
 * <p>The status sent on the wire and the {@code status} field inside the body are both
 * derived from the same {@link HttpStatusCode}, so they can never disagree.
 * Stateless, so it is a plain utility class, not a Spring bean.
 *
 * <p>For {@code 204 No Content} do not use this class: that status must not carry a body,
 * use {@code ResponseEntity.noContent().build()}. For errors, return a {@code ProblemDetail}.
 */
public final class Responses {

    private Responses() {
    }

    /** 200 with a message only. */
    public static ResponseEntity<ApiResponse<Void>> ok(String message) {
        return of(HttpStatus.OK, message, null);
    }

    /** 200 with a payload. */
    public static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return of(HttpStatus.OK, message, data);
    }

    /** 200 with a payload and custom headers, e.g. {@code Set-Cookie}. */
    public static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data, HttpHeaders headers) {
        return of(HttpStatus.OK, message, data, headers);
    }

    /** 201 with a {@code Location} header pointing at the newly created resource. */
    public static <T> ResponseEntity<ApiResponse<T>> created(URI location, String message, T data) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(location);
        return of(HttpStatus.CREATED, message, data, headers);
    }

    /** Any status, no extra headers. */
    public static <T> ResponseEntity<ApiResponse<T>> of(
            HttpStatusCode status, String message, @Nullable T data) {
        return of(status, message, data, HttpHeaders.EMPTY);
    }

    /** Any status, with headers. Every other factory method ends up here. */
    public static <T> ResponseEntity<ApiResponse<T>> of(
            HttpStatusCode status, String message, @Nullable T data, HttpHeaders headers) {
        return ResponseEntity.status(status)
                .headers(headers)
                .body(ApiResponse.of(status.value(), message, data));
    }
}