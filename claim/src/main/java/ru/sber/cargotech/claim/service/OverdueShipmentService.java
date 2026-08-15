package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.dto.OverdueShipmentResponse;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.enums.ShipmentStatus;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.repository.ClaimShipmentRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OverdueShipmentService {
    private static final Collection<ClaimStatus> CLOSED_STATUSES = List.of(
        ClaimStatus.PAID,
        ClaimStatus.CANCELLED,
        ClaimStatus.CANCELLED_PAID,
        ClaimStatus.CLOSED_IN_COURT
    );

    private final ClaimShipmentRepository shipmentRepository;
    private final ClaimRepository claimRepository;
    private final PaymentClient paymentClient;
    private final ContractService contractService;
    private final PartyService partyService;
    private final Article395RateProvider article395RateProvider;

    @Transactional(readOnly = true)
    public List<OverdueShipmentResponse> list(CurrentClaimUser user) {
        LocalDate today = LocalDate.now();
        return shipmentRepository
            .findByOrganizationId(user.organizationId())
            .stream()
            .map(shipment -> toAccountantQueueItem(user, shipment, today))
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparing(
                OverdueShipmentResponse::overdueStartDate,
                Comparator.nullsLast(Comparator.naturalOrder())
            ))
            .toList();
    }

    private OverdueShipmentResponse toAccountantQueueItem(
        CurrentClaimUser user,
        ClaimShipment shipment,
        LocalDate today
    ) {
        ClaimEntity latestClaim = claimRepository
            .findFirstByOrganizationIdAndShipmentIdOrderByCreatedAtDesc(
                user.organizationId(),
                shipment.getId()
            )
            .orElse(null);
        if (latestClaim != null && CLOSED_STATUSES.contains(latestClaim.getStatus())) {
            return null;
        }

        var contract = contractService.getEntity(user.organizationId(), shipment.getContractId());
        LocalDate overdueStartDate = OverdueDateCalculator.overdueStartDate(shipment, contract);
        boolean draftAwaitingAccountant = latestClaim != null
            && latestClaim.getStatus() == ClaimStatus.DRAFT;
        boolean actuallyOverdue = shipment.getStatus() == ShipmentStatus.COMPLETED
            && overdueStartDate != null
            && !overdueStartDate.isAfter(today);
        if (!draftAwaitingAccountant && !actuallyOverdue) {
            return null;
        }

        BigDecimal shipmentAmount = money(shipment.getServiceAmount());
        var paymentState = paymentClient.getPaymentState(
            latestClaim == null ? null : latestClaim.getId(),
            shipment.getId(),
            shipmentAmount
        );
        PenaltyType penaltyType = contract.getPenaltyType() == null
            ? PenaltyType.ARTICLE_395
            : contract.getPenaltyType();
        List<PenaltyScheduleCalculator.RatePeriod> article395RatePeriods =
            actuallyOverdue && penaltyType == PenaltyType.ARTICLE_395
                ? article395RateProvider.periods(overdueStartDate, today)
                : List.of();
        BigDecimal penaltyRate = penaltyType == PenaltyType.ARTICLE_395
            ? (article395RatePeriods.isEmpty()
                ? null
                : article395RatePeriods.get(article395RatePeriods.size() - 1).rate())
            : (contract.getPenaltyRate() == null ? BigDecimal.ZERO : contract.getPenaltyRate());
        List<PenaltyScheduleCalculator.Allocation> paymentAllocations =
            paymentState.allocations() == null
                ? List.of()
                : paymentState.allocations().stream()
                    .map(allocation -> new PenaltyScheduleCalculator.Allocation(
                        allocation.paymentDate(),
                        allocation.amount()
                    ))
                    .toList();
        BigDecimal accruedPenalty = actuallyOverdue
            ? PenaltyScheduleCalculator.calculate(
                shipmentAmount,
                overdueStartDate,
                today,
                penaltyType,
                penaltyRate,
                paymentAllocations,
                article395RatePeriods
            )
            : BigDecimal.ZERO;
        ClaimPaymentAllocationCalculator.AllocationResult allocation =
            ClaimPaymentAllocationCalculator.allocate(
                shipmentAmount,
                accruedPenalty,
                money(paymentState.paidAmount())
            );
        BigDecimal remainingPrincipalDebt = money(allocation.remainingPrincipal());
        BigDecimal remainingPenalty = money(allocation.remainingPenalty());
        BigDecimal totalAmount = money(remainingPrincipalDebt.add(remainingPenalty));
        if (totalAmount.signum() <= 0) {
            return null;
        }

        ClaimEntity claim = latestClaim;
        var client = partyService.getEntity(user.organizationId(), shipment.getClientId());
        var expeditor = partyService.getEntity(user.organizationId(), shipment.getExpeditorId());

        return new OverdueShipmentResponse(
            shipment.getId(),
            shipment.getOrderNumber(),
            client.getName(),
            client.getInn(),
            expeditor.getName(),
            shipmentAmount,
            money(paymentState.paidAmount()),
            totalAmount,
            remainingPrincipalDebt,
            remainingPenalty,
            totalAmount,
            shipment.getCurrency(),
            overdueStartDate == null ? null : overdueStartDate.minusDays(1),
            overdueStartDate,
            actuallyOverdue ? OverdueDateCalculator.overdueDays(overdueStartDate, today) : 0,
            shipment.getStatus(),
            claim == null ? null : claim.getId(),
            claim == null ? null : claim.getClaimNumber(),
            claim == null ? null : claim.getStatus(),
            claim != null && claim.isNonPaymentConfirmed(),
            claim == null ? null : claim.getFinalVersionId()
        );
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
