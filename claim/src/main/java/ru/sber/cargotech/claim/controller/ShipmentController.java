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
import ru.sber.cargotech.claim.dto.ShipmentRequest;
import ru.sber.cargotech.claim.dto.ShipmentResponse;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.ShipmentService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
@Slf4j
public class ShipmentController {
    private final ShipmentService shipmentService;
    private final CurrentClaimUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public PageResponse<ShipmentResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        log.info("Вызов endpoint: list");
        return PageResponse.from(shipmentService.list(currentUserProvider.getRequiredUser(), pageable));
    }

    @GetMapping("/{shipmentId}")
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public ShipmentResponse get(@PathVariable UUID shipmentId) {
        log.info("Вызов endpoint: get");
        return shipmentService.get(currentUserProvider.getRequiredUser(), shipmentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLAIM_CREATE')")
    public ShipmentResponse create(@Valid @RequestBody ShipmentRequest request) {
        log.info("Вызов endpoint: create");
        return shipmentService.create(currentUserProvider.getRequiredUser(), request);
    }

    @PatchMapping("/{shipmentId}")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ShipmentResponse update(
        @PathVariable UUID shipmentId,
        @Valid @RequestBody ShipmentRequest request
    ) {
        log.info("Вызов endpoint: update");
        return shipmentService.update(currentUserProvider.getRequiredUser(), shipmentId, request);
    }
}
