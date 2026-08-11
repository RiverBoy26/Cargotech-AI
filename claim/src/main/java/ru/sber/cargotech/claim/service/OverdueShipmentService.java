package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.dto.OverdueShipmentResponse;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ClaimStatus;
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

    @Transactional(readOnly = true)
    public List<OverdueShipmentResponse> list(CurrentClaimUser user) {
        LocalDate today = LocalDate.now();
        return shipmentRepository
            .findByOrganizationIdAndStatus(user.organizationId(), ShipmentStatus.COMPLETED)
            .stream()
            .map(shipment -> toOverdue(user, shipment, today))
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparing(OverdueShipmentResponse::overdueStartDate))
            .toList();
    }

    private OverdueShipmentResponse toOverdue(
        CurrentClaimUser user,
        ClaimShipment shipment,
        LocalDate today
    ) {
        var contract = contractService.getEntity(user.organizationId(), shipment.getContractId());
        LocalDate overdueStartDate = OverdueDateCalculator.overdueStartDate(shipment, contract);
        if (overdueStartDate == null || overdueStartDate.isAfter(today)) {
            return null;
        }

        BigDecimal shipmentAmount = money(shipment.getServiceAmount());
        var paymentState = paymentClient.getPaymentState(null, shipment.getId(), shipmentAmount);
        BigDecimal remainingDebt = money(paymentState.remainingAmount());
        if (remainingDebt.signum() <= 0) {
            return null;
        }

        ClaimEntity claim = claimRepository
            .findFirstByOrganizationIdAndShipmentIdAndStatusNotIn(
                user.organizationId(),
                shipment.getId(),
                CLOSED_STATUSES
            )
            .orElse(null);
        var client = partyService.getEntity(user.organizationId(), shipment.getClientId());
        var expeditor = partyService.getEntity(user.organizationId(), shipment.getExpeditorId());

        return new OverdueShipmentResponse(
            shipment.getId(),
            shipment.getOrderNumber(),
            client.getName(),
            expeditor.getName(),
            shipmentAmount,
            money(paymentState.paidAmount()),
            remainingDebt,
            shipment.getCurrency(),
            overdueStartDate.minusDays(1),
            overdueStartDate,
            OverdueDateCalculator.overdueDays(overdueStartDate, today),
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
