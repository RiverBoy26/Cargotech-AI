package ru.sber.cargotech.claim.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.claim.dto.PageResponse;
import ru.sber.cargotech.claim.dto.PartyRequest;
import ru.sber.cargotech.claim.dto.PartyResponse;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.PartyService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/parties")
@RequiredArgsConstructor
@Slf4j
public class PartyController {
    private final PartyService partyService;
    private final CurrentClaimUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public PageResponse<PartyResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        log.info("Вызов endpoint: list");
        return PageResponse.from(partyService.list(currentUserProvider.getRequiredUser(), pageable));
    }

    @GetMapping("/{partyId}")
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public PartyResponse get(@PathVariable UUID partyId) {
        log.info("Вызов endpoint: get");
        return partyService.get(currentUserProvider.getRequiredUser(), partyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLAIM_CREATE')")
    public PartyResponse create(@Valid @RequestBody PartyRequest request) {
        log.info("Вызов endpoint: create");
        return partyService.create(currentUserProvider.getRequiredUser(), request);
    }

    @PatchMapping("/{partyId}")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public PartyResponse update(
        @PathVariable UUID partyId,
        @Valid @RequestBody PartyRequest request
    ) {
        log.info("Вызов endpoint: update");
        return partyService.update(currentUserProvider.getRequiredUser(), partyId, request);
    }

    @DeleteMapping("/{partyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CLAIM_DELETE')")
    public void delete(@PathVariable UUID partyId) {
        log.info("Вызов endpoint: delete");
        partyService.delete(currentUserProvider.getRequiredUser(), partyId);
    }
}
