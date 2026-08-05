package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.client.AiClient;
import ru.sber.cargotech.claim.entity.ClaimCalculation;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.mapper.ClaimAiRequestMapper;
import ru.sber.cargotech.claim.repository.ClaimCalculationRepository;
import ru.sber.cargotech.claim.repository.ClaimContractRepository;
import ru.sber.cargotech.claim.repository.ClaimPartyRepository;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.repository.ClaimShipmentRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimGenerationServiceTest {

    @Mock private ClaimRepository claimRepository;
    @Mock private ClaimPartyRepository partyRepository;
    @Mock private ClaimContractRepository contractRepository;
    @Mock private ClaimShipmentRepository shipmentRepository;
    @Mock private ClaimCalculationRepository calculationRepository;
    @Mock private ClaimAiRequestMapper requestMapper;
    @Mock private AiClient aiClient;
    @Mock private ClaimVersionService versionService;

    @Test
    void doesNotCallAiBeforeNonPaymentIsConfirmed() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID creditorId = UUID.randomUUID();
        UUID debtorId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(organizationId);
        claim.setStatus(ClaimStatus.DRAFT);
        claim.setCreditorId(creditorId);
        claim.setDebtorId(debtorId);
        claim.setContractId(contractId);
        claim.setShipmentId(shipmentId);
        claim.setNonPaymentConfirmed(false);

        ClaimParty creditor = party(creditorId, "Кредитор");
        ClaimParty debtor = party(debtorId, "Должник");
        ClaimContract contract = new ClaimContract();
        contract.setId(contractId);
        ClaimShipment shipment = new ClaimShipment();
        shipment.setId(shipmentId);
        ClaimCalculation calculation = new ClaimCalculation();
        calculation.setRemainingDebt(new BigDecimal("100.00"));

        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId)).thenReturn(Optional.of(claim));
        when(partyRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(creditorId, organizationId))
                .thenReturn(Optional.of(creditor));
        when(partyRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(debtorId, organizationId))
                .thenReturn(Optional.of(debtor));
        when(contractRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(contractId, organizationId))
                .thenReturn(Optional.of(contract));
        when(shipmentRepository.findByIdAndOrganizationId(shipmentId, organizationId))
                .thenReturn(Optional.of(shipment));
        when(calculationRepository.findFirstByClaimIdOrderByCalculationVersionDesc(claimId))
                .thenReturn(Optional.of(calculation));

        ClaimGenerationService service = new ClaimGenerationService(
                claimRepository,
                partyRepository,
                contractRepository,
                shipmentRepository,
                calculationRepository,
                requestMapper,
                aiClient,
                versionService
        );

        assertThatThrownBy(() -> service.generate(user, claimId))
                .isInstanceOf(ClaimException.class)
                .hasMessage("Сначала бухгалтер должен подтвердить отсутствие оплаты");
        verifyNoInteractions(requestMapper, aiClient, versionService);
    }

    private ClaimParty party(UUID id, String name) {
        ClaimParty party = new ClaimParty();
        party.setId(id);
        party.setName(name);
        return party;
    }
}
