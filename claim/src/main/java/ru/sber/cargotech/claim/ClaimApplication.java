package ru.sber.cargotech.claim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClaimApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimApplication.class, args);
    }

}
