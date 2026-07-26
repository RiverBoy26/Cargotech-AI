package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.ClaimPaymentContextResponse;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimPartyRepository;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.repository.ClaimShipmentRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalClaimPaymentService {

    private static final List<ClaimStatus> CLOSED_STATUSES = List.of(
            ClaimStatus.PAID,
            ClaimStatus.CANCELLED,
            ClaimStatus.CLOSED_IN_COURT
    );

    private final ClaimRepository claimRepository;
    private final ClaimShipmentRepository shipmentRepository;
    private final ClaimPartyRepository partyRepository;

    @Transactional(readOnly = true)
    public ClaimPaymentContextResponse getPaymentContext(
            UUID organizationId,
            UUID claimId
    ) {
        log.debug("Получение payment-контекста: organizationId={}, claimId={}", organizationId, claimId);

        ClaimEntity claim = claimRepository
                .findByIdAndOrganizationId(claimId, organizationId)
                .orElseThrow(() -> ClaimException.notFound(
                        "Претензия не найдена"
                ));

        return toResponse(claim, organizationId);
    }

    @Transactional(readOnly = true)
    public List<ClaimPaymentContextResponse> findOpenByPayerInn(
            UUID organizationId,
            String payerInn
    ) {
        log.debug("Поиск открытых претензий по ИНН: organizationId={}, payerInn={}", organizationId, payerInn);

        if (payerInn == null || payerInn.isBlank()) {
            return List.of();
        }

        return claimRepository.findAllByOrganizationId(organizationId)
                .stream()
                .filter(claim -> !CLOSED_STATUSES.contains(claim.getStatus()))
                .map(claim -> toResponse(claim, organizationId))
                .filter(response ->
                        payerInn.equals(response.debtorInn())
                )
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimPaymentContextResponse> findMentionedInPurpose(
            UUID organizationId,
            String purpose
    ) {
        log.debug("Поиск претензий по назначению платежа: organizationId={}, purposeLength={}", organizationId, purpose == null ? 0 : purpose.length());

        if (purpose == null || purpose.isBlank()) {
            return List.of();
        }

        String normalized = purpose.toLowerCase();

        return claimRepository.findAllByOrganizationId(organizationId)
                .stream()
                .filter(claim -> !CLOSED_STATUSES.contains(claim.getStatus()))
                .map(claim -> toResponse(claim, organizationId))
                .filter(response ->
                        containsIgnoreCase(
                                normalized,
                                response.claimNumber()
                        )
                                || containsIgnoreCase(
                                normalized,
                                response.shipmentOrderNumber()
                        )
                )
                .toList();
    }

    @Transactional
    public void updateLastPaymentCheck(
            UUID organizationId,
            UUID claimId,
            UUID checkId,
            UUID userId
    ) {
        log.debug("Обновление последней проверки оплаты: organizationId={}, claimId={}, checkId={}, userId={}", organizationId, claimId, checkId, userId);

        ClaimEntity claim = claimRepository
                .findByIdAndOrganizationId(claimId, organizationId)
                .orElseThrow(() -> ClaimException.notFound(
                        "Претензия не найдена"
                ));

        claim.setLastPaymentCheckId(checkId);
        claim.setUpdatedBy(userId);

        claimRepository.save(claim);
    }

    private ClaimPaymentContextResponse toResponse(
            ClaimEntity claim,
            UUID organizationId
    ) {
        ClaimShipment shipment = shipmentRepository
                .findByIdAndOrganizationId(
                        claim.getShipmentId(),
                        organizationId
                )
                .orElseThrow(() -> ClaimException.notFound(
                        "Рейс претензии не найден"
                ));

        ClaimParty debtor = partyRepository
                .findByIdAndOrganizationId(
                        claim.getDebtorId(),
                        organizationId
                )
                .orElseThrow(() -> ClaimException.notFound(
                        "Должник претензии не найден"
                ));

        return new ClaimPaymentContextResponse(
                claim.getId(),
                shipment.getId(),
                claim.getClaimNumber(),
                debtor.getInn(),
                shipment.getOrderNumber(),
                shipment.getServiceAmount(),
                claim.getStatus().toString()
        );
    }

    private boolean containsIgnoreCase(
            String normalizedText,
            String value
    ) {
        return value != null
                && !value.isBlank()
                && normalizedText.contains(value.toLowerCase());
    }
}