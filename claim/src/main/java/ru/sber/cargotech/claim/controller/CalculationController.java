package ru.sber.cargotech.claim.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.claim.dto.ClaimCalculationResponse;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.ClaimCalculationService;
import ru.sber.cargotech.claim.service.CalculationExportService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/calculations/claim/{claimId}")
@RequiredArgsConstructor
@Slf4j
public class CalculationController {
    private final ClaimCalculationService calculationService;
    private final CalculationExportService exportService;
    private final CurrentClaimUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('CALCULATION_READ')")
    public ClaimCalculationResponse getLatest(@PathVariable UUID claimId) {
        log.info("Вызов endpoint: getLatest");
        return calculationService.getLatest(currentUserProvider.getRequiredUser(), claimId);
    }

    @PostMapping("/recalculate")
    @PreAuthorize("hasAuthority('CALCULATION_GENERATE')")
    public ClaimCalculationResponse recalculate(@PathVariable UUID claimId) {
        log.info("Вызов endpoint: recalculate");
        return calculationService.recalculate(currentUserProvider.getRequiredUser(), claimId);
    }

    @GetMapping("/export/{format}")
    @PreAuthorize("hasAuthority('CALCULATION_READ')")
    public ResponseEntity<byte[]> export(
            @PathVariable UUID claimId,
            @PathVariable String format
    ) {
        var file = exportService.export(
            currentUserProvider.getRequiredUser(),
            claimId,
            format
        );
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType()))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8)
                    .build()
                    .toString()
            )
            .body(file.content());
    }
}
