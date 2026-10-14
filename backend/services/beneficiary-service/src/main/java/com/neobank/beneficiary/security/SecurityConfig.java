package com.neobank.beneficiary.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

//    @Bean
//    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
//        http
//            .csrf(AbstractHttpConfigurer::disable)
//            .cors(AbstractHttpConfigurer::disable)
//            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
//            .exceptionHandling(ex -> ex
//                .authenticationEntryPoint(authenticationEntryPoint())
//                .accessDeniedHandler(accessDeniedHandler())
//            )
//            .authorizeHttpRequests(auth -> auth
//                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
//                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
//                // Public IFSC lookup and search endpoints
//                .requestMatchers(HttpMethod.GET, "/beneficiaries/ifsc/**", "/ifsc/**").permitAll()
//                // Admin-only IFSC directory registration
//                .requestMatchers(HttpMethod.POST, "/beneficiaries/ifsc/**", "/ifsc/**").hasRole("ADMIN")
//                // Internal service endpoints
//                .requestMatchers("/internal/**").hasAnyRole("INTERNAL_SERVICE", "ADMIN")
//                // Customer-facing beneficiary management
//                .requestMatchers("/beneficiaries/**").authenticated()
//                .anyRequest().authenticated()
//            )
//            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
//
//        return http.build();
//    }
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth
            .anyRequest().permitAll()
        );

    return http.build();
}
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication failed. Bearer token missing, invalid, or expired."
            );
            pd.setProperty("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS));
            response.getWriter().write(objectMapper.writeValueAsString(pd));
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to perform this action."
            );
            pd.setProperty("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS));
            response.getWriter().write(objectMapper.writeValueAsString(pd));
        };
    }
}
