package com.neobank.auth.controller;

import com.neobank.auth.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.api.ErrorCode;

import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;

@RestController
@RequiredArgsConstructor
@RequestMapping ("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityProperties securityProperties;
    private final AuditService auditService;
    private final Clock clock;

    @GetMapping ("/csrf")
    public ApiResponse <CsrfInfo> csrf (CsrfToken csrfToken) {
        return ApiResponse.ok (new CsrfInfo (token.getHeaderName (), token.getToken ()));
    }

    @PostMapping ("/login")
    public ApiResponse <SessionInfo> login (@Valid @RequestBody LoginRequest body,
                                            HttpServletRequest req, HttpServletResponse res) {
        Authentication auth;
        try {
            auth = authenticationManager.authenticate (
                UsernamePasswordAuthenticationToken.unauthenticated (body.username (), body.password ()));
        } catch (AuthenticationException ex) {
            // One generic outcome for unknown user / bad password / disabled account: no user enumeration.
            audit.record (AuditEventType.LOGIN_FAILURE, body.username (), req, "Authentication failed");
            throw new BusinessException (ErrorCode.INVALID_CREDENTIALS);
        }

        HttpSession old = req.getSession (false);
        if (old != null) old.invalidate ();              // never reuse a pre-login session (fixation)
        HttpSession session = req.getSession (true);

        SecurityContext context = SecurityContextHolder.createEmptyContext ();
        context.setAuthentication (auth);
        SecurityContextHolder.setContext (context);
        contextRepository.saveContext (context, req, res);
        session.setAttribute (SessionAttributes.LAST_ACTIVITY, clock.millis ());

        audit.record (AuditEventType.LOGIN_SUCCESS, auth.getName (), req, null);
        return ApiResponse.ok (info (auth, session));
    }

    /**
     * Passive status check for the timeout popup. Does NOT extend the session (see IdleSessionFilter).
     */
    @GetMapping ("/session")
    public ApiResponse <SessionInfo> session (Authentication auth, HttpServletRequest req) {
        return ApiResponse.ok (info (auth, req.getSession (false)));
    }

    /**
     * "Continue session" action on the timeout popup. Any non-passive request refreshes activity.
     */
    @PostMapping ("/session/keep-alive")
    public ApiResponse <SessionInfo> keepAlive (Authentication auth, HttpServletRequest req) {
        audit.record (AuditEventType.SESSION_KEEP_ALIVE, auth.getName (), req, null);
        return ApiResponse.ok (info (auth, req.getSession (false)));
    }

    /**
     * Idempotent: succeeds whether or not a live session exists.
     */
    @PostMapping ("/logout")
    public ApiResponse <Void> logout (HttpServletRequest req) {
        HttpSession session = req.getSession (false);
        String user = req.getUserPrincipal () != null ? req.getUserPrincipal ().getName () : null;
        if (session != null) session.invalidate ();
        SecurityContextHolder.clearContext ();
        if (user != null) audit.record (AuditEventType.LOGOUT, user, req, null);
        return ApiResponse.ok (null);
    }

    private SessionInfo info (Authentication auth, HttpSession session) {
        long idle = properties.idleTimeout ().toSeconds ();
        long remaining = idle;
        if (session != null && session.getAttribute (SessionAttributes.LAST_ACTIVITY) instanceof Long last) {
            remaining = Math.max (0, idle - (clock.millis () - last) / 1000);
        }
        return new SessionInfo (auth.getName (),
            auth.getAuthorities ().stream ().map (a -> a.getAuthority ()).sorted ().toList (), idle, remaining);
    }
}
