package ru.sber.cargotech.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI authOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("CargoTech Auth API")
                .version("v1")
                .description("Авторизация, JWT, refresh tokens, пользователи и роли"))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
            .components(new Components().addSecuritySchemes(
                BEARER_AUTH,
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
            ));
    }
}
