package com.example.demo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Spring AI Skills Demo API")
                .version("1.0")
                .description("Spring AI Skills 示例应用，包含商品与 PetStore API、"
                    + "运行时 Skill 发现、渐进式披露和 Agent 演示"));
    }
}
