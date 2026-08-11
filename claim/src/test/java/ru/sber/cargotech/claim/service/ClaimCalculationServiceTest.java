package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.entity.ClaimCalculation;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.repository.ClaimCalculationRepository;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimCalculationServiceTest {

    @Mock private ClaimRepository claimRepository;
    @Mock private ClaimCalculationRepository calculationRepository;
    @Mock private PaymentClient paymentClient;
    @Mock private ShipmentService shipmentService;
    @Mock private ContractService contractService;
    @Mock private ClaimOutboxWriter outboxWriter;

    @Test
    void calculatesDebtFromShipmentAmountAndMatchedPayments() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(userId, organizationId);

        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(organizationId);
        claim.setShipmentId(shipmentId);
        claim.setContractId(contractId);
        // A stale value must never become the source for the next calculation.
        claim.setPrincipalDebt(new BigDecimal("15.00"));

        ClaimShipment shipment = new ClaimShipment();
        shipment.setId(shipmentId);
        shipment.setServiceAmount(new BigDecimal("100000.00"));
        shipment.setUnloadingDate(LocalDate.now().minusDays(30));

        ClaimContract contract = new ClaimContract();
        contract.setId(contractId);
        contract.setPaymentDays(0);
        contract.setPenaltyType(PenaltyType.NONE);

        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
            .thenReturn(Optional.of(claim));
        when(shipmentService.getEntity(organizationId, shipmentId))
            .thenReturn(shipment);
        when(contractService.getEntity(organizationId, contractId))
            .thenReturn(contract);
        when(paymentClient.getPaymentState(
            eq(claimId),
            eq(shipmentId),
            eq(new BigDecimal("100000.00"))
        )).thenReturn(new PaymentClient.PaymentStateResponse(
            claimId,
            shipmentId,
            new BigDecimal("100000.00"),
            new BigDecimal("40000.00"),
            new BigDecimal("60000.00"),
            "PARTIALLY_PAID",
            List.of(new PaymentClient.PaymentAllocationResponse(
                LocalDate.now().minusDays(5),
                new BigDecimal("40000.00")
            ))
        ));
        when(calculationRepository.findLastCalculationVersion(claimId))
            .thenReturn(0);
        when(calculationRepository.save(org.mockito.ArgumentMatchers.any(ClaimCalculation.class)))
            .thenAnswer(invocation -> {
                ClaimCalculation calculation = invocation.getArgument(0);
                calculation.setId(UUID.randomUUID());
                return calculation;
            });

        ClaimCalculationService service = new ClaimCalculationService(
            claimRepository,
            calculationRepository,
            paymentClient,
            shipmentService,
            contractService,
            outboxWriter
        );

        var result = service.recalculate(user, claimId);

        assertThat(result.principalDebt()).isEqualByComparingTo("100000.00");
        assertThat(result.paidAmount()).isEqualByComparingTo("40000.00");
        assertThat(result.remainingDebt()).isEqualByComparingTo("60000.00");
        assertThat(claim.getPrincipalDebt()).isEqualByComparingTo("60000.00");
        verify(claimRepository).save(claim);
    }
}
