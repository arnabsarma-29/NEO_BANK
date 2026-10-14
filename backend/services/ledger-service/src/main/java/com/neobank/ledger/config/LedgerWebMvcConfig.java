package com.neobank.ledger.config;

import com.neobank.ledger.security.TransferServiceTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers internal service authentication interceptor for /internal/ledger/** routes.
 */
@Configuration
public class LedgerWebMvcConfig implements WebMvcConfigurer {

    private final TransferServiceTokenInterceptor tokenInterceptor;

    public LedgerWebMvcConfig(TransferServiceTokenInterceptor tokenInterceptor) {
        this.tokenInterceptor = tokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor)
                .addPathPatterns("/internal/ledger/**");
    }
}
