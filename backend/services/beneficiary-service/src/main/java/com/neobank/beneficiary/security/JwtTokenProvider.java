package com.neobank.beneficiary.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long defaultExpirySeconds;

    public JwtTokenProvider(
        @Value("${neobank.jwt.secret:change-this-secret-to-at-least-32-characters-long}") String secret,
        @Value("${neobank.jwt.expiry-seconds:3600}") long defaultExpirySeconds
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            this.secretKey = Keys.hmacShaKeyFor(padded);
        } else {
            this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        }
        this.defaultExpirySeconds = defaultExpirySeconds;
    }
    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public Authentication getAuthentication(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        String customerId = claims.get("customerId", String.class);
        if (customerId == null || customerId.isBlank()) {
            customerId = claims.getSubject();
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            authorities = list.stream()
                .map(Object::toString)
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        } else {
            Object roleObj = claims.get("role");
            if (roleObj != null) {
                String r = roleObj.toString();
                authorities.add(new SimpleGrantedAuthority(r.startsWith("ROLE_") ? r : "ROLE_" + r));
            } else {
                authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
            }
        }

        User principal = new User(customerId, "", authorities);
        return new UsernamePasswordAuthenticationToken(principal, token, authorities);
    }

    public String generateToken(String customerId, String role, long validitySeconds) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + (validitySeconds * 1000));

        String normalizedRole = role != null && !role.isBlank() ? (role.startsWith("ROLE_") ? role.substring(5) : role) : "CUSTOMER";

        return Jwts.builder()
            .subject(customerId)
            .claim("customerId", customerId)
            .claim("role", normalizedRole)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact();
    }

    public String generateToken(String customerId, String role) {
        return generateToken(customerId, role, defaultExpirySeconds);
    }
}
