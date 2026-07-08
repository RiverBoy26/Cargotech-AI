package ru.sber.cargotech.claim.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI claimOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("CargoTech Claim API")
                .version("v1")
                .description("Реестр претензий, расчёты, версии текста, комментарии и статусы"))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
            .components(new Components().addSecuritySchemes(
                BEARER_SCHEME,
                new SecurityScheme()
                    .name(BEARER_SCHEME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
            ));
    }
}
