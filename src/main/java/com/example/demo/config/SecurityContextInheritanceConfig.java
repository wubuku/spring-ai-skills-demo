package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

@Slf4j
@Configuration
public class SecurityContextInheritanceConfig {

    @PostConstruct
    public void init() {
        String strategy = SecurityContextHolder.MODE_INHERITABLETHREADLOCAL;
        SecurityContextHolder.setStrategyName(strategy);
        log.info("[SecurityContextInheritanceConfig] SecurityContextHolder 策略已设置为: {}", strategy);
    }
}
