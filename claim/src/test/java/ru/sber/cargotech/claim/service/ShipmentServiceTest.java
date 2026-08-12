package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.dto.ShipmentRequest;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ContractStatus;
import ru.sber.cargotech.claim.enums.ShipmentStatus;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimShipmentRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    @Mock private ClaimShipmentRepository shipmentRepository;
    @Mock private PartyService partyService;
    @Mock private ContractService contractService;
    @Mock private ClaimOutboxWriter outboxWriter;

    @Test
    void provisionalContractCannotBeUsedForShipment() {
        UUID organizationId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        when(partyService.getEntity(organizationId, clientId)).thenReturn(new ClaimParty());
        when(partyService.getEntity(organizationId, organizationId)).thenReturn(new ClaimParty());
        ClaimContract provisional = new ClaimContract();
        provisional.setId(contractId);
        provisional.setOrganizationId(organizationId);
        provisional.setClientId(clientId);
        provisional.setExpeditorId(organizationId);
        provisional.setStatus(ContractStatus.DRAFT);
        when(contractService.getEntity(organizationId, contractId)).thenReturn(provisional);

        ShipmentRequest request = new ShipmentRequest(
            "РЕЙС-1", clientId, organizationId, contractId,
            null, null, null, null, null, null, null,
            BigDecimal.TEN, "RUB", ShipmentStatus.CREATED, null
        );

        ShipmentService service = new ShipmentService(
            shipmentRepository, partyService, contractService, outboxWriter
        );

        assertThatThrownBy(() -> service.create(user, request))
            .isInstanceOf(ClaimException.class)
            .hasMessage("Рейс можно привязать только к подтверждённому действующему договору");
        verify(shipmentRepository, never()).save(any(ClaimShipment.class));
    }
}
