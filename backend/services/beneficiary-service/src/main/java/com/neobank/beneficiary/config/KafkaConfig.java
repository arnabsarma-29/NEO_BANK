
package com.neobank.beneficiary.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.apache.kafka.common.serialization.Serializer;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory(
        org.springframework.core.env.Environment environment,
        ObjectMapper objectMapper) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            environment.getProperty("spring.kafka.bootstrap-servers", "localhost:9092"));

        config.put(ProducerConfig.ACKS_CONFIG,
            environment.getProperty("spring.kafka.producer.acks", "all"));

        config.put(
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
            environment.getProperty(
                "spring.kafka.producer.properties.enable.idempotence",
                Boolean.class,
                true
            )
        );

        Serializer<Object> jsonSerializer = new Serializer<>() {
            @Override
            public byte[] serialize(String topic, Object data) {
                if (data == null) {
                    return null;
                }
                try {
                    return objectMapper.writeValueAsBytes(data);
                } catch (Exception e) {
                    throw new IllegalStateException(
                        "Failed to serialize Kafka event", e);
                }
            }
        };

        return new DefaultKafkaProducerFactory<>(
            config,
            new StringSerializer(),
            jsonSerializer
        );
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(
        ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
