package ru.sber.cargotech.claim.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.claim.dto.OrganizationProjectionRequest;
import ru.sber.cargotech.claim.dto.PartyResponse;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.OrganizationProjectionService;

@RestController
@RequestMapping("/internal/api/v1/organization-projections")
@RequiredArgsConstructor
public class InternalOrganizationProjectionController {

    private final OrganizationProjectionService projectionService;
    private final CurrentClaimUserProvider currentUserProvider;

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PartyResponse synchronize(
        @Valid @RequestBody OrganizationProjectionRequest request
    ) {
        return projectionService.synchronize(
            request,
            currentUserProvider.getRequiredUser().userId()
        );
    }
}
