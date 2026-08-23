package com.example.demo.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestConfig {

    @Bean
    public RestTemplate restTemplate(
        RestTemplateBuilder builder,
        @Value("${app.internal-http.connect-timeout:5s}") Duration connectTimeout,
        @Value("${app.internal-http.read-timeout:15s}") Duration readTimeout
    ) {
        return builder
            .connectTimeout(connectTimeout)
            .readTimeout(readTimeout)
            .build();
    }
}
