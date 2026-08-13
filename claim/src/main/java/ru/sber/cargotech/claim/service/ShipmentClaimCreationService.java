package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.CreateClaimRequest;
import ru.sber.cargotech.claim.dto.ShipmentRequest;
import ru.sber.cargotech.claim.dto.ShipmentResponse;
import ru.sber.cargotech.claim.enums.ClaimType;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

@Service
@RequiredArgsConstructor
public class ShipmentClaimCreationService {
    private final ShipmentService shipmentService;
    private final ClaimService claimService;

    @Transactional
    public ShipmentResponse create(CurrentClaimUser user, ShipmentRequest request) {
        ShipmentResponse shipment = shipmentService.create(user, request);
        claimService.createDraftForShipment(
            user,
            new CreateClaimRequest(
                null,
                shipment.id(),
                null,
                null,
                ClaimType.PAYMENT_DELAY,
                null,
                null,
                null,
                null,
                false,
                null,
                null
            )
        );
        return shipment;
    }
}
