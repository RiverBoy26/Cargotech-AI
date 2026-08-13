package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.dto.CreateClaimRequest;
import ru.sber.cargotech.claim.dto.ShipmentRequest;
import ru.sber.cargotech.claim.dto.ShipmentResponse;
import ru.sber.cargotech.claim.enums.ClaimType;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentClaimCreationServiceTest {
    @Mock private ShipmentService shipmentService;
    @Mock private ClaimService claimService;

    @Test
    void createsPaymentDelayDraftAfterShipment() {
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), UUID.randomUUID());
        ShipmentRequest request = mock(ShipmentRequest.class);
        ShipmentResponse shipment = mock(ShipmentResponse.class);
        UUID shipmentId = UUID.randomUUID();
        when(shipment.id()).thenReturn(shipmentId);
        when(shipmentService.create(user, request)).thenReturn(shipment);

        ShipmentClaimCreationService service = new ShipmentClaimCreationService(
            shipmentService,
            claimService
        );

        ShipmentResponse result = service.create(user, request);

        assertThat(result).isSameAs(shipment);
        ArgumentCaptor<CreateClaimRequest> claimCaptor = ArgumentCaptor.forClass(CreateClaimRequest.class);
        InOrder order = inOrder(shipmentService, claimService);
        order.verify(shipmentService).create(user, request);
        order.verify(claimService).createDraftForShipment(
            org.mockito.ArgumentMatchers.eq(user),
            claimCaptor.capture()
        );
        assertThat(claimCaptor.getValue().shipmentId()).isEqualTo(shipmentId);
        assertThat(claimCaptor.getValue().claimType()).isEqualTo(ClaimType.PAYMENT_DELAY);
        assertThat(claimCaptor.getValue().nonPaymentConfirmed()).isFalse();
        assertThat(claimCaptor.getValue().reason()).isNull();
        assertThat(claimCaptor.getValue().draftContent()).isNull();
    }
}
