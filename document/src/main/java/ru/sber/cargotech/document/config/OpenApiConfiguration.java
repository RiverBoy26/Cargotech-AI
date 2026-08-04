package ru.sber.cargotech.document.config;

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
    OpenAPI documentOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("CargoTech Document API")
                .version("v1")
                .description("Шаблоны претензий, формирование DOCX/PDF и отправка документов"))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
            .components(new Components().addSecuritySchemes(
                BEARER_AUTH,
                new SecurityScheme()
                    .name(BEARER_AUTH)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT access token. Введите только токен, без префикса Bearer.")
            ));
    }
}
