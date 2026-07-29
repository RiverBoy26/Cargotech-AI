package ru.sber.cargotech.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.auth.dto.CreateOrganizationRequest;
import ru.sber.cargotech.auth.dto.OrganizationResponse;
import ru.sber.cargotech.auth.dto.PageResponse;
import ru.sber.cargotech.auth.dto.UpdateOrganizationRequest;
import ru.sber.cargotech.auth.enums.OrganizationStatus;
import ru.sber.cargotech.auth.security.CurrentUserProvider;
import ru.sber.cargotech.auth.service.OrganizationService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('ORGANIZATION_READ')")
    public PageResponse<OrganizationResponse> findAll(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) OrganizationStatus status,
        @PageableDefault(size = 50, sort = "name") Pageable pageable
    ) {
        return organizationService.findAll(search, status, pageable);
    }

    @GetMapping("/{organizationId}")
    @PreAuthorize("hasAuthority('ORGANIZATION_READ')")
    public OrganizationResponse get(
        @PathVariable UUID organizationId
    ) {
        return organizationService.get(organizationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORGANIZATION_CREATE')")
    public OrganizationResponse create(
        @Valid @RequestBody CreateOrganizationRequest request
    ) {
        return organizationService.create(
            request,
            currentUserProvider.getRequiredUser()
        );
    }

    @PatchMapping("/{organizationId}")
    @PreAuthorize("hasAuthority('ORGANIZATION_UPDATE')")
    public OrganizationResponse update(
        @PathVariable UUID organizationId,
        @Valid @RequestBody UpdateOrganizationRequest request
    ) {
        return organizationService.update(
            organizationId,
            request,
            currentUserProvider.getRequiredUser()
        );
    }

    @PostMapping("/{organizationId}/synchronize")
    @PreAuthorize("hasAuthority('ORGANIZATION_UPDATE')")
    public OrganizationResponse synchronize(
        @PathVariable UUID organizationId
    ) {
        return organizationService.synchronize(
            organizationId,
            currentUserProvider.getRequiredUser()
        );
    }
}
