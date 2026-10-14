package com.neobank.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain (
        ServerHttpSecurity http) {

        return http
            .csrf (ServerHttpSecurity.CsrfSpec :: disable)
            .authorizeExchange (exchange -> exchange
                .pathMatchers ("/api/public/**").permitAll ()
                .pathMatchers ("/api/**").authenticated ()
                .anyExchange ().denyAll ()
            )
            .oauth2ResourceServer (oauth2 -> oauth2
                .jwt (jwt -> {
                })
            )
            .build ();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder (
        @Value ("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
        String issuerUri,
        @Value ("${app.security.jwt.audience:neobank-api}")
        String expectedAudience) {

        NimbusReactiveJwtDecoder decoder =
            NimbusReactiveJwtDecoder
                .withIssuerLocation (issuerUri)
                .build ();

        OAuth2TokenValidator <Jwt> issuerAndTimeValidator =
            JwtValidators.createDefaultWithIssuer (issuerUri);

        OAuth2TokenValidator <Jwt> audienceValidator = jwt -> {
            List <String> audiences = jwt.getAudience ();

            if (audiences != null
                && audiences.contains (expectedAudience)) {
                return OAuth2TokenValidatorResult.success ();
            }

            return OAuth2TokenValidatorResult.failure (
                new OAuth2Error (
                    "invalid_token",
                    "Token audience is not accepted",
                    null
                )
            );
        };

        decoder.setJwtValidator (
            new DelegatingOAuth2TokenValidator <> (
                issuerAndTimeValidator,
                audienceValidator
            )
        );

        return decoder;
    }
}
