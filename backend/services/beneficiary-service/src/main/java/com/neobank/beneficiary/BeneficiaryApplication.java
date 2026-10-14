package com.neobank.beneficiary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients
@EnableScheduling
@EnableJpaAuditing
public class BeneficiaryApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeneficiaryApplication.class, args);
    }
}
