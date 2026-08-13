package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.dto.ClaimCalculationResponse;
import ru.sber.cargotech.claim.dto.PaymentPreflightResponse;
import ru.sber.cargotech.claim.dto.StatusChangeRequest;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimStatusHistory;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.DocumentValidationStatus;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimQueryRepository;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.repository.ClaimStatusHistoryRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceSendOrderTest {

    @Mock private ClaimRepository claimRepository;
    @Mock private ClaimQueryRepository queryRepository;
    @Mock private ClaimStatusHistoryRepository historyRepository;
    @Mock private ShipmentService shipmentService;
    @Mock private PaymentClient paymentClient;
    @Mock private ContractService contractService;
    @Mock private PartyService partyService;
    @Mock private ClaimCalculationService calculationService;
    @Mock private ClaimVersionService versionService;
    @Mock private ClaimOutboxWriter outboxWriter;

    @Test
    void validatesClaimAndRecalculatesBeforePaymentPreflight() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        PaymentPreflightResponse preflight = new PaymentPreflightResponse();
        preflight.setCanSend(false);

        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(organizationId);
        claim.setStatus(ClaimStatus.LEGAL_APPROVED);
        claim.setPrincipalDebt(new java.math.BigDecimal("100.00"));
        claim.setPenaltyAmount(java.math.BigDecimal.ZERO);
        claim.setCreditorId(UUID.randomUUID());
        claim.setDebtorId(UUID.randomUUID());
        claim.setContractId(UUID.randomUUID());
        claim.setNonPaymentConfirmed(true);
        claim.setFinalVersionId(UUID.randomUUID());
        claim.setDocumentValidationStatus(DocumentValidationStatus.PASSED);

        ClaimCalculationResponse calculation = org.mockito.Mockito.mock(
                ClaimCalculationResponse.class
        );

        when(paymentClient.preflightCheck(claimId, "Проверка оплаты перед отправкой претензии"))
                .thenReturn(preflight);
        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
                .thenReturn(Optional.of(claim));
        when(calculationService.recalculate(user, claimId))
                .thenReturn(calculation);
        when(calculation.totalAmount()).thenReturn(new java.math.BigDecimal("100.00"));

        ClaimService service = new ClaimService(
                claimRepository,
                queryRepository,
                historyRepository,
                shipmentService,
                paymentClient,
                contractService,
                partyService,
                calculationService,
                versionService,
                outboxWriter
        );

        assertThatThrownBy(() -> service.send(
                user,
                claimId,
                new StatusChangeRequest("test", null)
        )).isInstanceOf(ClaimException.class);

        InOrder order = inOrder(claimRepository, calculationService, paymentClient);
        order.verify(claimRepository).findByIdAndOrganizationId(
                claimId,
                organizationId
        );
        order.verify(calculationService).recalculate(user, claimId);
        order.verify(paymentClient).preflightCheck(
                claimId,
                "Проверка оплаты перед отправкой претензии"
        );
    }

    @Test
    void fullPaymentBeforeSendCancelsClaimWithSystemHistory() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(organizationId);
        claim.setStatus(ClaimStatus.LEGAL_APPROVED);

        ClaimCalculationResponse calculation = org.mockito.Mockito.mock(
                ClaimCalculationResponse.class
        );
        when(calculation.totalAmount()).thenReturn(java.math.BigDecimal.ZERO);
        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
                .thenReturn(Optional.of(claim));
        when(calculationService.recalculate(user, claimId)).thenReturn(calculation);
        when(queryRepository.findDetails(organizationId, claimId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(
                        ru.sber.cargotech.claim.dto.ClaimDetailsResponse.class
                )));

        ClaimService service = new ClaimService(
                claimRepository,
                queryRepository,
                historyRepository,
                shipmentService,
                paymentClient,
                contractService,
                partyService,
                calculationService,
                versionService,
                outboxWriter
        );

        service.synchronizePaymentState(user, claimId);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.CANCELLED_PAID);
        assertThat(claim.getPaidAt()).isNotNull();
        assertThat(claim.getCancellationReasonCode()).isEqualTo("FULL_PAYMENT_BEFORE_SEND");
        ArgumentCaptor<ClaimStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(ClaimStatusHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getReason())
                .isEqualTo("Задолженность полностью погашена");
    }
}
