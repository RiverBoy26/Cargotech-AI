package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimQueryRepository;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.repository.ClaimStatusHistoryRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ClaimServiceCascadeDeletionTest {

    @Mock private ClaimRepository claimRepository;
    @Mock private ClaimQueryRepository queryRepository;
    @Mock private ClaimStatusHistoryRepository historyRepository;
    @Mock private ShipmentService shipmentService;
    @Mock private PaymentClient paymentClient;
    @Mock private ContractService contractService;
    @Mock private PartyService partyService;
    @Mock private ClaimCalculationService calculationService;
    @Mock private ClaimVersionService versionService;
    @Mock private ClaimOutboxWriter outboxWriter;

    @Test
    void deletesPaymentsThenClaimAndShipment() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);
        ClaimEntity claim = claim(claimId, shipmentId, organizationId);

        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
            .thenReturn(Optional.of(claim));
        when(shipmentService.getEntity(organizationId, shipmentId))
            .thenReturn(new ClaimShipment());
        when(claimRepository.existsByOrganizationIdAndShipmentIdAndIdNot(
            organizationId,
            shipmentId,
            claimId
        )).thenReturn(false);

        service().delete(user, claimId);

        InOrder order = inOrder(paymentClient, claimRepository, shipmentService);
        order.verify(paymentClient).deleteForClaimAndShipment(claimId, shipmentId);
        order.verify(claimRepository).delete(claim);
        order.verify(claimRepository).flush();
        order.verify(shipmentService).delete(user, shipmentId);
    }

    @Test
    void keepsEverythingWhenShipmentHasAnotherClaim() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
            .thenReturn(Optional.of(claim(claimId, shipmentId, organizationId)));
        when(shipmentService.getEntity(organizationId, shipmentId))
            .thenReturn(new ClaimShipment());
        when(claimRepository.existsByOrganizationIdAndShipmentIdAndIdNot(
            organizationId,
            shipmentId,
            claimId
        )).thenReturn(true);

        assertThatThrownBy(() -> service().delete(user, claimId))
            .isInstanceOf(ru.sber.cargotech.claim.exception.ClaimException.class)
            .hasMessage("Нельзя удалить рейс: с ним связана другая претензия");

        verify(paymentClient, never()).deleteForClaimAndShipment(claimId, shipmentId);
        verify(claimRepository, never()).delete(org.mockito.ArgumentMatchers.any());
        verify(shipmentService, never()).delete(user, shipmentId);
    }

    private ClaimService service() {
        return new ClaimService(
            claimRepository,
            queryRepository,
            historyRepository,
            shipmentService,
            paymentClient,
            contractService,
            partyService,
            calculationService,
            versionService,
            outboxWriter
        );
    }

    private static ClaimEntity claim(UUID id, UUID shipmentId, UUID organizationId) {
        ClaimEntity claim = new ClaimEntity();
        claim.setId(id);
        claim.setShipmentId(shipmentId);
        claim.setOrganizationId(organizationId);
        claim.setStatus(ClaimStatus.DRAFT);
        return claim;
    }
}
