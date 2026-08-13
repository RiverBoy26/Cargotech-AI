package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimGenerationTransactionBoundaryTest {

    @Test
    void generateDoesNotHoldDatabaseTransactionAcrossAiCall() throws Exception {
        Method method = ClaimGenerationService.class.getMethod(
            "generate",
            ru.sber.cargotech.claim.security.CurrentClaimUser.class,
            java.util.UUID.class
        );

        assertThat(method.getAnnotation(Transactional.class))
            .as("generate() must not hold a DB transaction during the external AI request")
            .isNull();
    }
}
