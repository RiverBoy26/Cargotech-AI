package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.ClaimCalculationResponse;
import ru.sber.cargotech.claim.entity.ClaimCalculation;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimCalculationRepository;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimPaymentFactRepository;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClaimCalculationService {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    private final ClaimRepository claimRepository;
    private final ClaimCalculationRepository calculationRepository;
    private final ShipmentService shipmentService;
    private final ContractService contractService;
    private final ClaimPaymentFactRepository paymentFactRepository;
    private final ClaimOutboxWriter outboxWriter;

    @Transactional(readOnly = true)
    public ClaimCalculationResponse getLatest(CurrentClaimUser user, UUID claimId) {
        ClaimEntity claim = getClaim(user, claimId);
        return calculationRepository.findFirstByClaimIdOrderByCalculationVersionDesc(claim.getId())
            .map(this::toResponse)
            .orElseThrow(() -> ClaimException.notFound("Расчёт по претензии не найден"));
    }

    @Transactional
    public ClaimCalculationResponse recalculate(CurrentClaimUser user, UUID claimId) {
        ClaimEntity claim = getClaim(user, claimId);
        ClaimShipment shipment = shipmentService.getEntity(user.organizationId(), claim.getShipmentId());
        ClaimContract contract = contractService.getEntity(user.organizationId(), claim.getContractId());

        BigDecimal principalDebt = money(shipment.getServiceAmount());
        BigDecimal paidAmount = money(paymentFactRepository.sumPaidForClaimOrShipment(
            user.organizationId(),
            claim.getId(),
            shipment.getId()
        ));
        BigDecimal remainingDebt = principalDebt.subtract(paidAmount);
        if (remainingDebt.signum() < 0) {
            remainingDebt = BigDecimal.ZERO;
        }
        remainingDebt = money(remainingDebt);

        LocalDate calculationDate = LocalDate.now();
        LocalDate overdueStartDate = resolveOverdueStartDate(shipment, contract);
        int overdueDays = resolveOverdueDays(overdueStartDate, calculationDate);
        PenaltyType penaltyType = contract.getPenaltyType() == null
            ? PenaltyType.NONE
            : contract.getPenaltyType();
        BigDecimal penaltyRate = contract.getPenaltyRate() == null
            ? BigDecimal.ZERO
            : contract.getPenaltyRate();
        BigDecimal penaltyAmount = calculatePenalty(
            remainingDebt,
            overdueDays,
            penaltyType,
            penaltyRate
        );
        BigDecimal totalAmount = money(remainingDebt.add(penaltyAmount));

        int nextVersion = calculationRepository.findLastCalculationVersion(claim.getId()) + 1;
        ClaimCalculation calculation = new ClaimCalculation();
        calculation.setClaimId(claim.getId());
        calculation.setCalculationVersion(nextVersion);
        calculation.setPrincipalDebt(principalDebt);
        calculation.setPaidAmount(paidAmount);
        calculation.setRemainingDebt(remainingDebt);
        calculation.setOverdueStartDate(overdueStartDate);
        calculation.setCalculationDate(calculationDate);
        calculation.setOverdueDays(overdueDays);
        calculation.setPenaltyType(penaltyType);
        calculation.setPenaltyRate(penaltyRate);
        calculation.setPenaltyAmount(penaltyAmount);
        calculation.setTotalAmount(totalAmount);
        calculation.setFormula(buildFormula(penaltyType, penaltyRate, overdueDays));
        calculation.setInputSnapshot(Map.of(
            "shipmentId", shipment.getId().toString(),
            "contractId", contract.getId().toString(),
            "serviceAmount", principalDebt,
            "paidAmount", paidAmount,
            "paymentStartEvent", contract.getPaymentStartEvent() == null ? "" : contract.getPaymentStartEvent().name(),
            "paymentDays", contract.getPaymentDays() == null ? 0 : contract.getPaymentDays()
        ));
        calculation.setCreatedBy(user.userId());
        ClaimCalculation saved = calculationRepository.save(calculation);

        claim.setPrincipalDebt(remainingDebt);
        claim.setPenaltyAmount(penaltyAmount);
        claim.setUpdatedBy(user.userId());
        claim.normalizeTotals();
        claimRepository.save(claim);

        outboxWriter.write(
            "CLAIM",
            claim.getId(),
            "CLAIM_CALCULATED",
            user.organizationId(),
            user.userId(),
            Map.of(
                "claimId", claim.getId(),
                "calculationId", saved.getId(),
                "remainingDebt", remainingDebt,
                "penaltyAmount", penaltyAmount,
                "totalAmount", totalAmount
            )
        );

        return toResponse(saved);
    }

    private ClaimEntity getClaim(CurrentClaimUser user, UUID claimId) {
        return claimRepository.findByIdAndOrganizationId(claimId, user.organizationId())
            .orElseThrow(() -> ClaimException.notFound("Претензия не найдена"));
    }

    private LocalDate resolveOverdueStartDate(ClaimShipment shipment, ClaimContract contract) {
        LocalDate baseDate = switch (contract.getPaymentStartEvent() == null
            ? PaymentStartEvent.UNLOADING_DATE
            : contract.getPaymentStartEvent()) {
            case ACT_SIGNED -> shipment.getActSignedAt();
            case UNLOADING_DATE, TTN_SIGNED, INVOICE_DATE -> shipment.getUnloadingDate();
        };
        if (baseDate == null) {
            baseDate = shipment.getActSignedAt() != null
                ? shipment.getActSignedAt()
                : shipment.getUnloadingDate();
        }
        if (baseDate == null) {
            return null;
        }
        int paymentDays = contract.getPaymentDays() == null ? 0 : contract.getPaymentDays();
        return baseDate.plusDays(paymentDays + 1L);
    }

    private int resolveOverdueDays(LocalDate overdueStartDate, LocalDate calculationDate) {
        if (overdueStartDate == null || !calculationDate.isAfter(overdueStartDate)) {
            return 0;
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(overdueStartDate, calculationDate));
    }

    private BigDecimal calculatePenalty(
        BigDecimal remainingDebt,
        int overdueDays,
        PenaltyType penaltyType,
        BigDecimal penaltyRate
    ) {
        if (remainingDebt.signum() <= 0 || overdueDays <= 0 || penaltyType == PenaltyType.NONE) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal days = BigDecimal.valueOf(overdueDays);
        BigDecimal percent = penaltyRate.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP);
        BigDecimal value;
        if (penaltyType == PenaltyType.ARTICLE_395) {
            value = remainingDebt.multiply(percent).multiply(days).divide(DAYS_IN_YEAR, 10, RoundingMode.HALF_UP);
        } else {
            value = remainingDebt.multiply(percent).multiply(days);
        }
        return money(value);
    }

    private String buildFormula(PenaltyType type, BigDecimal rate, int days) {
        if (type == PenaltyType.NONE) {
            return "Неустойка не начисляется";
        }
        if (type == PenaltyType.ARTICLE_395) {
            return "Остаток долга × " + rate + "% × " + days + " дней / 365";
        }
        return "Остаток долга × " + rate + "% × " + days + " дней";
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private ClaimCalculationResponse toResponse(ClaimCalculation calculation) {
        return new ClaimCalculationResponse(
            calculation.getId(),
            calculation.getClaimId(),
            calculation.getCalculationVersion(),
            calculation.getPrincipalDebt(),
            calculation.getPaidAmount(),
            calculation.getRemainingDebt(),
            calculation.getOverdueStartDate(),
            calculation.getCalculationDate(),
            calculation.getOverdueDays(),
            calculation.getPenaltyType(),
            calculation.getPenaltyRate(),
            calculation.getPenaltyAmount(),
            calculation.getTotalAmount(),
            calculation.getFormula(),
            calculation.getCreatedBy(),
            calculation.getCreatedAt()
        );
    }
}
