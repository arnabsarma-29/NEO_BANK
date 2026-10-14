package com.neobank.transfer.config;

import com.neobank.transfer.security.ScheduleServiceTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers internal service authentication interceptor for /internal/transfers/** routes.
 */
@Configuration
public class TransferWebMvcConfig implements WebMvcConfigurer {

    private final ScheduleServiceTokenInterceptor scheduleTokenInterceptor;

    public TransferWebMvcConfig(ScheduleServiceTokenInterceptor scheduleTokenInterceptor) {
        this.scheduleTokenInterceptor = scheduleTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(scheduleTokenInterceptor)
                .addPathPatterns("/internal/transfers/**");
    }
}
