package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.EnumSet;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClaimDailyRecalculationScheduler {
    private static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);
    private static final EnumSet<ClaimStatus> TERMINAL_STATUSES = EnumSet.of(
        ClaimStatus.PAID,
        ClaimStatus.CANCELLED,
        ClaimStatus.CANCELLED_PAID,
        ClaimStatus.CLOSED_IN_COURT
    );

    private final ClaimRepository claimRepository;
    private final ClaimCalculationService calculationService;

    /** Recalculates debt and penalty every day from source shipments and payments. */
    @Scheduled(cron = "${claim.calculation.daily-cron:0 15 1 * * *}", zone = "${claim.calculation.zone:Europe/Moscow}")
    public void recalculateOpenClaims() {
        for (ClaimEntity claim : claimRepository.findAllByStatusNotIn(TERMINAL_STATUSES)) {
            try {
                calculationService.recalculate(
                    new CurrentClaimUser(SYSTEM_USER_ID, claim.getOrganizationId()),
                    claim.getId()
                );
            } catch (RuntimeException exception) {
                log.error("Не удалось выполнить ежедневный перерасчёт претензии {}: {}",
                    claim.getId(), exception.getMessage());
            }
        }
    }
}
