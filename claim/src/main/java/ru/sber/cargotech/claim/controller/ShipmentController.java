package ru.sber.cargotech.claim.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.claim.dto.PageResponse;
import ru.sber.cargotech.claim.dto.AccountantClaimSubmissionRequest;
import ru.sber.cargotech.claim.dto.ClaimDetailsResponse;
import ru.sber.cargotech.claim.dto.OverdueShipmentResponse;
import ru.sber.cargotech.claim.dto.ShipmentRequest;
import ru.sber.cargotech.claim.dto.ShipmentResponse;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.ShipmentService;
import ru.sber.cargotech.claim.service.OverdueShipmentService;
import ru.sber.cargotech.claim.service.AccountantOverdueClaimService;
import ru.sber.cargotech.claim.service.ShipmentClaimCreationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
@Slf4j
public class ShipmentController {
    private final ShipmentService shipmentService;
    private final OverdueShipmentService overdueShipmentService;
    private final AccountantOverdueClaimService accountantOverdueClaimService;
    private final ShipmentClaimCreationService shipmentClaimCreationService;
    private final CurrentClaimUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('SHIPMENT_READ')")
    public PageResponse<ShipmentResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        log.info("Вызов endpoint: list");
        return PageResponse.from(shipmentService.list(currentUserProvider.getRequiredUser(), pageable));
    }

    @GetMapping("/{shipmentId}")
    @PreAuthorize("hasAuthority('SHIPMENT_READ')")
    public ShipmentResponse get(@PathVariable UUID shipmentId) {
        log.info("Вызов endpoint: get");
        return shipmentService.get(currentUserProvider.getRequiredUser(), shipmentId);
    }

    @GetMapping("/overdue")
    @PreAuthorize("hasAuthority('OVERDUE_READ')")
    public List<OverdueShipmentResponse> overdue() {
        log.info("Вызов endpoint: overdue");
        return overdueShipmentService.list(currentUserProvider.getRequiredUser());
    }

    @PostMapping("/{shipmentId}/confirm-non-payment")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('OVERDUE_CONFIRM_NON_PAYMENT')")
    public ClaimDetailsResponse confirmNonPayment(
        @PathVariable UUID shipmentId,
        @Valid @RequestBody AccountantClaimSubmissionRequest request
    ) {
        log.info("Подтверждение неуплаты и создание претензии по рейсу: shipmentId={}", shipmentId);
        return accountantOverdueClaimService.confirmNonPayment(
            currentUserProvider.getRequiredUser(),
            shipmentId,
            request
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SHIPMENT_CREATE')")
    public ShipmentResponse create(@Valid @RequestBody ShipmentRequest request) {
        log.info("Вызов endpoint: create");
        return shipmentClaimCreationService.create(currentUserProvider.getRequiredUser(), request);
    }

    @PatchMapping("/{shipmentId}")
    @PreAuthorize("hasAuthority('SHIPMENT_UPDATE')")
    public ShipmentResponse update(
        @PathVariable UUID shipmentId,
        @Valid @RequestBody ShipmentRequest request
    ) {
        log.info("Вызов endpoint: update");
        return shipmentService.update(currentUserProvider.getRequiredUser(), shipmentId, request);
    }
}
