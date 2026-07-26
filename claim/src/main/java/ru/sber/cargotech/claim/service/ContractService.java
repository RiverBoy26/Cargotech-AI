package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.ContractRequest;
import ru.sber.cargotech.claim.dto.ContractResponse;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.enums.ContractStatus;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimContractRepository;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractService {
    private final ClaimContractRepository contractRepository;
    private final PartyService partyService;
    private final ClaimOutboxWriter outboxWriter;

    @Transactional(readOnly = true)
    public Page<ContractResponse> list(CurrentClaimUser user, Pageable pageable) {
        log.debug("Получение договоров: organizationId={}, page={}, size={}", user.organizationId(), pageable.getPageNumber(), pageable.getPageSize());

        return contractRepository
            .findByOrganizationIdAndDeletedAtIsNull(user.organizationId(), pageable)
            .map(contract -> toResponse(user.organizationId(), contract));
    }

    @Transactional(readOnly = true)
    public ContractResponse get(CurrentClaimUser user, UUID id) {
        log.debug("Получение договора: contractId={}, organizationId={}", id, user.organizationId());

        return toResponse(user.organizationId(), getEntity(user.organizationId(), id));
    }

    @Transactional
    public ContractResponse create(CurrentClaimUser user, ContractRequest request) {
        log.debug("Создание договора: organizationId={}, userId={}, number={}, clientId={}, expeditorId={}, paymentDays={}, penaltyType={}, penaltyRate={}", user.organizationId(), user.userId(), request.number(), request.clientId(), request.expeditorId(), request.paymentDays(), request.penaltyType(), request.penaltyRate());

        if (contractRepository.existsByOrganizationIdAndNumberAndDeletedAtIsNull(
            user.organizationId(), request.number()
        )) {
            throw ClaimException.conflict("Договор с таким номером уже существует");
        }
        validateParties(user.organizationId(), request.clientId(), request.expeditorId());
        ClaimContract contract = new ClaimContract();
        contract.setOrganizationId(user.organizationId());
        contract.setCreatedBy(user.userId());
        contract.setUpdatedBy(user.userId());
        apply(contract, request, user.userId());
        ClaimContract saved = contractRepository.save(contract);
        outboxWriter.write("CONTRACT", saved.getId(), "CONTRACT_CREATED", user.organizationId(), user.userId(), Map.of("contractId", saved.getId()));
        return toResponse(user.organizationId(), saved);
    }

    @Transactional
    public ContractResponse update(CurrentClaimUser user, UUID id, ContractRequest request) {
        log.debug("Обновление договора: contractId={}, organizationId={}, userId={}, number={}, paymentDays={}, penaltyType={}, penaltyRate={}", id, user.organizationId(), user.userId(), request.number(), request.paymentDays(), request.penaltyType(), request.penaltyRate());

        ClaimContract contract = getEntity(user.organizationId(), id);
        validateParties(user.organizationId(), request.clientId(), request.expeditorId());
        apply(contract, request, user.userId());
        ClaimContract saved = contractRepository.save(contract);
        outboxWriter.write("CONTRACT", saved.getId(), "CONTRACT_UPDATED", user.organizationId(), user.userId(), Map.of("contractId", saved.getId()));
        return toResponse(user.organizationId(), saved);
    }

    @Transactional
    public void delete(CurrentClaimUser user, UUID id) {
        log.debug("Удаление договора: contractId={}, organizationId={}, userId={}", id, user.organizationId(), user.userId());

        ClaimContract contract = getEntity(user.organizationId(), id);
        contract.setDeletedAt(OffsetDateTime.now());
        contract.setUpdatedBy(user.userId());
        contractRepository.save(contract);
        outboxWriter.write("CONTRACT", contract.getId(), "CONTRACT_DELETED", user.organizationId(), user.userId(), Map.of("contractId", contract.getId()));
    }

    public ClaimContract getEntity(UUID organizationId, UUID id) {
        return contractRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, organizationId)
            .orElseThrow(() -> ClaimException.notFound("Договор не найден"));
    }

    private void apply(ClaimContract contract, ContractRequest request, UUID userId) {
        contract.setNumber(request.number());
        contract.setClientId(request.clientId());
        contract.setExpeditorId(request.expeditorId());
        contract.setSignedAt(request.signedAt());
        contract.setValidFrom(request.validFrom());
        contract.setValidTo(request.validTo());
        contract.setStatus(request.status() == null ? ContractStatus.ACTIVE : request.status());
        contract.setPaymentDays(request.paymentDays());
        contract.setPaymentStartEvent(request.paymentStartEvent());
        contract.setPenaltyType(request.penaltyType() == null ? PenaltyType.NONE : request.penaltyType());
        contract.setPenaltyRate(request.penaltyRate());
        contract.setClaimResponseDays(request.claimResponseDays());
        contract.setJurisdiction(request.jurisdiction());
        contract.setDocumentId(request.documentId());
        contract.setUpdatedBy(userId);
    }

    private void validateParties(UUID organizationId, UUID clientId, UUID expeditorId) {
        if (clientId.equals(expeditorId)) {
            throw ClaimException.validation("Клиент и экспедитор должны быть разными контрагентами");
        }
        partyService.getEntity(organizationId, clientId);
        partyService.getEntity(organizationId, expeditorId);
    }

    public ContractResponse toResponse(UUID organizationId, ClaimContract contract) {
        ClaimParty client = partyService.getEntity(organizationId, contract.getClientId());
        ClaimParty expeditor = partyService.getEntity(organizationId, contract.getExpeditorId());
        return new ContractResponse(
            contract.getId(),
            contract.getOrganizationId(),
            contract.getNumber(),
            contract.getClientId(),
            client.getName(),
            contract.getExpeditorId(),
            expeditor.getName(),
            contract.getSignedAt(),
            contract.getValidFrom(),
            contract.getValidTo(),
            contract.getStatus(),
            contract.getPaymentDays(),
            contract.getPaymentStartEvent(),
            contract.getPenaltyType(),
            contract.getPenaltyRate(),
            contract.getClaimResponseDays(),
            contract.getJurisdiction(),
            contract.getDocumentId(),
            contract.getCreatedAt(),
            contract.getUpdatedAt()
        );
    }
}
