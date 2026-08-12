package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.sber.cargotech.claim.dto.ContractExtractionCandidateRequest;
import ru.sber.cargotech.claim.dto.ContractIntakeRequest;
import ru.sber.cargotech.claim.dto.SubmitContractExtractionRequest;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ContractExtractedValue;
import ru.sber.cargotech.claim.enums.ContractExtractionField;
import ru.sber.cargotech.claim.enums.ContractExtractionStatus;
import ru.sber.cargotech.claim.enums.ContractStatus;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimContractClauseRepository;
import ru.sber.cargotech.claim.repository.ClaimContractRepository;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ContractExtractedValueRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    @Mock private ClaimContractRepository contractRepository;
    @Mock private ContractExtractedValueRepository extractedValueRepository;
    @Mock private ClaimContractClauseRepository contractClauseRepository;
    @Mock private PartyService partyService;
    @Mock private ClaimOutboxWriter outboxWriter;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Test
    void createsDraftContractFromClientAndRequiredDocumentOnly() {
        UUID organizationId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        when(partyService.getEntity(organizationId, clientId)).thenReturn(party(clientId, "Клиент"));
        when(partyService.getEntity(organizationId, organizationId)).thenReturn(party(organizationId, "Экспедитор"));
        when(contractRepository.save(any())).thenAnswer(invocation -> {
            ClaimContract value = invocation.getArgument(0);
            if (value.getId() == null) value.setId(UUID.randomUUID());
            return value;
        });

        ContractService service = service();
        var response = service.create(user, new ContractIntakeRequest(clientId, documentId));

        assertThat(response.number()).isNull();
        assertThat(response.status()).isEqualTo(ContractStatus.DRAFT);
        assertThat(response.documentId()).isEqualTo(documentId);
        assertThat(response.extractionStatus()).isEqualTo(ContractExtractionStatus.PENDING);
        verify(eventPublisher).publishEvent(any(ContractExtractionRequestedEvent.class));
    }

    @Test
    void editedCandidateSurvivesSaveAndConfirmedContractCanBeReviewedAgain() {
        UUID organizationId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);
        ClaimContract contract = contract(contractId, organizationId, ContractExtractionStatus.CONFIRMED);
        List<ContractExtractedValue> stored = new ArrayList<>();

        when(contractRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(contractId, organizationId))
            .thenReturn(Optional.of(contract));
        when(contractRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(extractedValueRepository.saveAll(any())).thenAnswer(invocation -> {
            stored.clear();
            invocation.<Iterable<ContractExtractedValue>>getArgument(0).forEach(stored::add);
            return stored;
        });
        when(extractedValueRepository.findByContractIdOrderByCreatedAtAsc(contractId))
            .thenAnswer(invocation -> List.copyOf(stored));

        var response = service().submitExtraction(
            user,
            contractId,
            reviewRequest("Д-42", "30", true)
        );

        assertThat(response.status()).isEqualTo(ContractExtractionStatus.REVIEW_REQUIRED);
        assertThat(response.candidates())
            .filteredOn(value -> value.field() == ContractExtractionField.PAYMENT_DAYS)
            .singleElement()
            .satisfies(value -> {
                assertThat(value.value()).isEqualTo("30");
                assertThat(value.manuallyEdited()).isTrue();
            });
    }

    @Test
    void confirmAppliesHumanValuesAndDoesNotFabricateMissingClause() {
        UUID organizationId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);
        ClaimContract contract = contract(contractId, organizationId, ContractExtractionStatus.REVIEW_REQUIRED);
        List<ContractExtractedValue> values = extractedValues("Д-42", "30");

        when(contractRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(contractId, organizationId))
            .thenReturn(Optional.of(contract));
        when(extractedValueRepository.findByContractIdOrderByCreatedAtAsc(contractId)).thenReturn(values);

        var response = service().confirmExtraction(user, contractId);

        assertThat(response.status()).isEqualTo(ContractExtractionStatus.CONFIRMED);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(contract.getNumber()).isEqualTo("Д-42");
        assertThat(contract.getSignedAt()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(contract.getPaymentDays()).isEqualTo(30);
        assertThat(contract.getPaymentStartEvent()).isEqualTo(PaymentStartEvent.ACT_SIGNED);
        assertThat(contract.getPenaltyType()).isEqualTo(PenaltyType.CONTRACT_PENALTY);
        verify(contractClauseRepository, never()).save(any());
    }

    @Test
    void duplicateFinalContractNumberIsRejectedBeforeConfirmation() {
        UUID organizationId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);
        ClaimContract contract = contract(contractId, organizationId, ContractExtractionStatus.REVIEW_REQUIRED);

        when(contractRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(contractId, organizationId))
            .thenReturn(Optional.of(contract));
        when(extractedValueRepository.findByContractIdOrderByCreatedAtAsc(contractId))
            .thenReturn(extractedValues("ДУБЛЬ-1", "30"));
        when(contractRepository.existsByOrganizationIdAndNumberAndDeletedAtIsNullAndIdNot(
            organizationId, "ДУБЛЬ-1", contractId
        )).thenReturn(true);

        assertThatThrownBy(() -> service().confirmExtraction(user, contractId))
            .isInstanceOf(ClaimException.class)
            .hasMessage("Договор с таким номером уже существует");
    }

    private ContractService service() {
        return new ContractService(
            contractRepository,
            extractedValueRepository,
            contractClauseRepository,
            partyService,
            outboxWriter,
            eventPublisher
        );
    }

    private ClaimContract contract(UUID id, UUID organizationId, ContractExtractionStatus extractionStatus) {
        ClaimContract contract = new ClaimContract();
        contract.setId(id);
        contract.setOrganizationId(organizationId);
        contract.setClientId(UUID.randomUUID());
        contract.setExpeditorId(organizationId);
        contract.setDocumentId(UUID.randomUUID());
        contract.setStatus(ContractStatus.DRAFT);
        contract.setExtractionStatus(extractionStatus);
        return contract;
    }

    private ClaimParty party(UUID id, String name) {
        ClaimParty party = new ClaimParty();
        party.setId(id);
        party.setName(name);
        return party;
    }

    private SubmitContractExtractionRequest reviewRequest(String number, String paymentDays, boolean manuallyEdited) {
        return new SubmitContractExtractionRequest(Arrays.stream(ContractExtractionField.values())
            .filter(field -> field != ContractExtractionField.EXACT_CLAUSE)
            .map(field -> new ContractExtractionCandidateRequest(
                field,
                switch (field) {
                    case CONTRACT_NUMBER -> number;
                    case SIGNED_AT -> "2026-08-12";
                    case PAYMENT_DAYS -> paymentDays;
                    case PAYMENT_START_EVENT -> "ACT_SIGNED";
                    case PENALTY_TYPE -> "CONTRACT_PENALTY";
                    case PENALTY_RATE -> "0.1";
                    case CLAIM_RESPONSE_DAYS -> "10";
                    case JURISDICTION -> "Арбитражный суд Новосибирской области";
                    case EXACT_CLAUSE -> null;
                },
                manuallyEdited ? null : "Источник",
                manuallyEdited ? null : 1,
                manuallyEdited ? null : new BigDecimal("0.90"),
                null,
                null,
                manuallyEdited
            ))
            .toList());
    }

    private List<ContractExtractedValue> extractedValues(String number, String paymentDays) {
        return reviewRequest(number, paymentDays, true).candidates().stream().map(candidate -> {
            ContractExtractedValue value = new ContractExtractedValue();
            value.setField(candidate.field());
            value.setValue(candidate.value());
            value.setManuallyEdited(candidate.manuallyEdited());
            return value;
        }).toList();
    }
}
