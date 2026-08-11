package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.dto.ClaimCalculationResponse;
import ru.sber.cargotech.claim.entity.ClaimCalculation;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimCalculationRepository;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaimCalculationService {
    private final ClaimRepository claimRepository;
    private final ClaimCalculationRepository calculationRepository;
    private final PaymentClient paymentClient;
    private final ShipmentService shipmentService;
    private final ContractService contractService;
    private final ClaimOutboxWriter outboxWriter;
    private final Article395RateProvider article395RateProvider;

    @Transactional(readOnly = true)
    public ClaimCalculationResponse getLatest(CurrentClaimUser user, UUID claimId) {
        log.debug("Получение последнего расчёта: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        ClaimEntity claim = getClaim(user, claimId);
        return calculationRepository.findFirstByClaimIdOrderByCalculationVersionDesc(claim.getId())
            .map(this::toResponse)
            .orElseThrow(() -> ClaimException.notFound("Расчёт по претензии не найден"));
    }

    @Transactional
    public ClaimCalculationResponse recalculate(CurrentClaimUser user, UUID claimId) {
        log.debug("Запуск перерасчёта: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        ClaimEntity claim = getClaim(user, claimId);
        ClaimShipment shipment = shipmentService.getEntity(user.organizationId(), claim.getShipmentId());
        ClaimContract contract = contractService.getEntity(user.organizationId(), claim.getContractId());

        // The shipment is the only source of truth for the original obligation.
        // Never carry a client-supplied or previously mutated claim balance into a
        // new calculation: doing so makes partial payments compound incorrectly.
        BigDecimal principalDebt = money(shipment.getServiceAmount());
        if (principalDebt.signum() <= 0) {
            throw ClaimException.validation(
                "Сумма завершённого рейса должна быть больше нуля"
            );
        }
        PaymentClient.PaymentStateResponse paymentState =
                paymentClient.getPaymentState(
                        claim.getId(),
                        shipment.getId(),
                        principalDebt
                );

        BigDecimal paidAmount = money(paymentState.paidAmount());

        LocalDate calculationDate = LocalDate.now();
        LocalDate overdueStartDate = OverdueDateCalculator.overdueStartDate(shipment, contract);
        int overdueDays = OverdueDateCalculator.overdueDays(overdueStartDate, calculationDate);
        PenaltyType penaltyType = contract.getPenaltyType() == null
            ? PenaltyType.ARTICLE_395
            : contract.getPenaltyType();
        BigDecimal penaltyRate = contract.getPenaltyRate() == null
            ? (penaltyType == PenaltyType.ARTICLE_395
                ? new BigDecimal("18.00")
                : BigDecimal.ZERO)
            : contract.getPenaltyRate();
        List<PenaltyScheduleCalculator.Allocation> paymentAllocations =
            paymentState.allocations() == null
                ? List.of()
                : paymentState.allocations().stream()
                    .map(allocation -> new PenaltyScheduleCalculator.Allocation(
                        allocation.paymentDate(),
                        allocation.amount()
                    ))
                    .toList();
        BigDecimal accruedPenaltyAmount = PenaltyScheduleCalculator.calculate(
            principalDebt,
            overdueStartDate,
            calculationDate,
            penaltyType,
            penaltyRate,
            paymentAllocations,
            penaltyType == PenaltyType.ARTICLE_395
                ? article395RateProvider.periods(overdueStartDate, calculationDate)
                : List.of()
        );
        ClaimPaymentAllocationCalculator.AllocationResult paymentAllocation =
                ClaimPaymentAllocationCalculator.allocate(
                        principalDebt,
                        accruedPenaltyAmount,
                        paidAmount
                );
        BigDecimal remainingDebt = money(paymentAllocation.remainingPrincipal());
        BigDecimal paidPenaltyAmount = money(paymentAllocation.paidPenalty());
        BigDecimal penaltyAmount = money(paymentAllocation.remainingPenalty());
        BigDecimal totalAmount = money(remainingDebt.add(penaltyAmount));
        log.debug("Расчёт выполнен: claimId={}, principalDebt={}, paidAmount={}, remainingDebt={}, overdueStartDate={}, overdueDays={}, penaltyType={}, penaltyRate={}, penaltyAmount={}, totalAmount={}", claim.getId(), principalDebt, paidAmount, remainingDebt, overdueStartDate, overdueDays, penaltyType, penaltyRate, penaltyAmount, totalAmount);

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
        calculation.setFormula(buildFormula(
            penaltyType,
            penaltyRate,
            overdueDays,
            paymentAllocations.size()
        ));
        calculation.setInputSnapshot(Map.of(
            "shipmentId", shipment.getId().toString(),
            "contractId", contract.getId().toString(),
            "serviceAmount", principalDebt,
            "paidAmount", paidAmount,
            "accruedPenaltyAmount", accruedPenaltyAmount,
            "paidPenaltyAmount", paidPenaltyAmount,
            "paymentAllocations", paymentAllocations.stream()
                .map(allocation -> Map.of(
                    "paymentDate", allocation.paymentDate().toString(),
                    "amount", allocation.amount()
                ))
                .toList(),
            "paymentStartEvent", contract.getPaymentStartEvent() == null ? "" : contract.getPaymentStartEvent().name(),
            "paymentDays", contract.getPaymentDays() == null ? 0 : contract.getPaymentDays()
        ));
        calculation.setCreatedBy(user.userId());
        ClaimCalculation saved = calculationRepository.save(calculation);
        log.debug("Расчёт сохранён: claimId={}, calculationId={}, version={}", claim.getId(), saved.getId(), saved.getCalculationVersion());

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
                "paidPenaltyAmount", paidPenaltyAmount,
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

    private String buildFormula(
        PenaltyType type,
        BigDecimal rate,
        int days,
        int paymentCount
    ) {
        if (type == PenaltyType.NONE) {
            return "Неустойка не начисляется";
        }
        String base = paymentCount > 0
            ? "Остаток долга по периодам между платежами"
            : "Остаток долга";
        if (type == PenaltyType.ARTICLE_395) {
            return base + " × " + rate + "% × " + days + " дней / 365";
        }
        return base + " × " + rate + "% × " + days + " дней";
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
