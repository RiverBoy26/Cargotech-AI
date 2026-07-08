package ru.sber.cargotech.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ru.sber.cargotech.ai.config.GigaChatProperties;
import ru.sber.cargotech.ai.config.QdrantProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        GigaChatProperties.class,
        QdrantProperties.class
})
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}