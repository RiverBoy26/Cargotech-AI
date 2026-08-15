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
    void failedAutoValidationDoesNotBlockApprovalOrSendChecklist() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(organizationId);
        claim.setStatus(ClaimStatus.PENDING_LEGAL_REVIEW);
        claim.setPrincipalDebt(new java.math.BigDecimal("100.00"));
        claim.setPenaltyAmount(java.math.BigDecimal.ZERO);
        claim.setCreditorId(UUID.randomUUID());
        claim.setDebtorId(UUID.randomUUID());
        claim.setContractId(UUID.randomUUID());
        claim.setNonPaymentConfirmed(true);
        claim.setFinalVersionId(UUID.randomUUID());
        claim.setDocumentValidationStatus(DocumentValidationStatus.FAILED);
        var details = org.mockito.Mockito.mock(
            ru.sber.cargotech.claim.dto.ClaimDetailsResponse.class
        );

        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
            .thenReturn(Optional.of(claim));
        when(claimRepository.save(org.mockito.ArgumentMatchers.any(ClaimEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(queryRepository.findDetails(organizationId, claimId)).thenReturn(Optional.of(details));

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

        var approved = service.approve(
            user,
            claimId,
            new StatusChangeRequest("Проверено юристом", null)
        );
        var checklist = service.sendChecklist(user, claimId);

        assertThat(approved).isSameAs(details);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.LEGAL_APPROVED);
        assertThat(checklist.readyToSend()).isTrue();
        assertThat(checklist.checks()).doesNotContainKey("validation");
        assertThat(checklist.documentValidationStatus()).isEqualTo(DocumentValidationStatus.FAILED);
    }

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

        InOrder reloadOrder = inOrder(calculationService, claimRepository);
        reloadOrder.verify(calculationService).recalculate(user, claimId);
        reloadOrder.verify(claimRepository).findByIdAndOrganizationId(
                claimId,
                organizationId
        );

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.CANCELLED_PAID);
        assertThat(claim.getPaidAt()).isNotNull();
        assertThat(claim.getCancellationReasonCode()).isEqualTo("FULL_PAYMENT_BEFORE_SEND");
        ArgumentCaptor<ClaimStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(ClaimStatusHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getReason())
                .isEqualTo("Задолженность полностью погашена");
    }

    @Test
    void fullPaymentAfterSendMarksReloadedClaimPaid() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        ClaimEntity reloadedClaim = new ClaimEntity();
        reloadedClaim.setId(claimId);
        reloadedClaim.setOrganizationId(organizationId);
        reloadedClaim.setStatus(ClaimStatus.AWAITING_RESPONSE);
        reloadedClaim.setSentAt(java.time.OffsetDateTime.now().minusDays(1));

        ClaimCalculationResponse calculation = org.mockito.Mockito.mock(
                ClaimCalculationResponse.class
        );
        when(calculation.totalAmount()).thenReturn(java.math.BigDecimal.ZERO);
        when(calculationService.recalculate(user, claimId)).thenReturn(calculation);
        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
                .thenReturn(Optional.of(reloadedClaim));
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

        assertThat(reloadedClaim.getStatus()).isEqualTo(ClaimStatus.PAID);
        assertThat(reloadedClaim.getPaidAt()).isNotNull();
        ArgumentCaptor<ClaimStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(ClaimStatusHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getNewStatus()).isEqualTo(ClaimStatus.PAID);
    }

    @Test
    void deletedPaymentReopensCancelledPaidClaimToPreviousPreSendStatus() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        ClaimEntity reloadedClaim = new ClaimEntity();
        reloadedClaim.setId(claimId);
        reloadedClaim.setOrganizationId(organizationId);
        reloadedClaim.setStatus(ClaimStatus.CANCELLED_PAID);
        reloadedClaim.setPaidAt(java.time.OffsetDateTime.now().minusHours(1));
        reloadedClaim.setCancelledAt(java.time.OffsetDateTime.now().minusHours(1));
        reloadedClaim.setCancellationReasonCode("FULL_PAYMENT_BEFORE_SEND");
        reloadedClaim.setCancellationReason(
            "Задолженность полностью погашена до отправки претензии"
        );

        ClaimStatusHistory paidCancellation = new ClaimStatusHistory();
        paidCancellation.setClaimId(claimId);
        paidCancellation.setPreviousStatus(ClaimStatus.PENDING_LEGAL_REVIEW);
        paidCancellation.setNewStatus(ClaimStatus.CANCELLED_PAID);

        ClaimCalculationResponse calculation = org.mockito.Mockito.mock(
                ClaimCalculationResponse.class
        );
        when(calculation.totalAmount()).thenReturn(new java.math.BigDecimal("100.00"));
        when(calculationService.recalculate(user, claimId)).thenReturn(calculation);
        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
                .thenReturn(Optional.of(reloadedClaim));
        when(historyRepository.findTopByClaimIdAndNewStatusOrderByChangedAtDesc(
                claimId,
                ClaimStatus.CANCELLED_PAID
        )).thenReturn(Optional.of(paidCancellation));

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

        String reason = "Удалён сопоставленный платёж: ошибочная банковская операция";
        service.synchronizePaymentState(user, claimId, reason);

        assertThat(reloadedClaim.getStatus()).isEqualTo(ClaimStatus.PENDING_LEGAL_REVIEW);
        assertThat(reloadedClaim.getPaidAt()).isNull();
        assertThat(reloadedClaim.getCancelledAt()).isNull();
        assertThat(reloadedClaim.getCancellationReasonCode()).isNull();
        assertThat(reloadedClaim.getCancellationReason()).isNull();

        ArgumentCaptor<ClaimStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(ClaimStatusHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getPreviousStatus())
                .isEqualTo(ClaimStatus.CANCELLED_PAID);
        assertThat(historyCaptor.getValue().getNewStatus())
                .isEqualTo(ClaimStatus.PENDING_LEGAL_REVIEW);
        assertThat(historyCaptor.getValue().getReason()).isEqualTo(reason);
        assertThat(historyCaptor.getValue().getChangedBy())
                .isEqualTo(new UUID(0L, 0L));
    }

    @Test
    void cancelledPaidClaimDoesNotGuessPreviousStatusWhenHistoryIsMissing() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        ClaimEntity reloadedClaim = new ClaimEntity();
        reloadedClaim.setId(claimId);
        reloadedClaim.setOrganizationId(organizationId);
        reloadedClaim.setStatus(ClaimStatus.CANCELLED_PAID);
        reloadedClaim.setPaidAt(java.time.OffsetDateTime.now().minusHours(1));
        reloadedClaim.setCancelledAt(java.time.OffsetDateTime.now().minusHours(1));

        ClaimCalculationResponse calculation = org.mockito.Mockito.mock(
                ClaimCalculationResponse.class
        );
        when(calculation.totalAmount()).thenReturn(new java.math.BigDecimal("100.00"));
        when(calculationService.recalculate(user, claimId)).thenReturn(calculation);
        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
                .thenReturn(Optional.of(reloadedClaim));
        when(historyRepository.findTopByClaimIdAndNewStatusOrderByChangedAtDesc(
                claimId,
                ClaimStatus.CANCELLED_PAID
        )).thenReturn(Optional.empty());

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

        assertThatThrownBy(() -> service.synchronizePaymentState(user, claimId))
                .isInstanceOf(ClaimException.class)
                .hasMessageContaining("в истории отсутствует исходный статус");

        assertThat(reloadedClaim.getStatus()).isEqualTo(ClaimStatus.CANCELLED_PAID);
        assertThat(reloadedClaim.getPaidAt()).isNotNull();
        assertThat(reloadedClaim.getCancelledAt()).isNotNull();
    }

    @Test
    void deletedPaymentReopensPaidSentClaimWithReason() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        ClaimEntity reloadedClaim = new ClaimEntity();
        reloadedClaim.setId(claimId);
        reloadedClaim.setOrganizationId(organizationId);
        reloadedClaim.setStatus(ClaimStatus.PAID);
        reloadedClaim.setSentAt(java.time.OffsetDateTime.now().minusDays(2));
        reloadedClaim.setPaidAt(java.time.OffsetDateTime.now().minusDays(1));

        ClaimCalculationResponse calculation = org.mockito.Mockito.mock(
                ClaimCalculationResponse.class
        );
        when(calculation.totalAmount()).thenReturn(new java.math.BigDecimal("100.00"));
        when(calculationService.recalculate(user, claimId)).thenReturn(calculation);
        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
                .thenReturn(Optional.of(reloadedClaim));
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

        String reason = "Удалён сопоставленный платёж: ошибочная банковская операция";
        service.synchronizePaymentState(user, claimId, reason);

        assertThat(reloadedClaim.getStatus()).isEqualTo(ClaimStatus.AWAITING_RESPONSE);
        assertThat(reloadedClaim.getPaidAt()).isNull();
        ArgumentCaptor<ClaimStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(ClaimStatusHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getPreviousStatus()).isEqualTo(ClaimStatus.PAID);
        assertThat(historyCaptor.getValue().getNewStatus()).isEqualTo(ClaimStatus.AWAITING_RESPONSE);
        assertThat(historyCaptor.getValue().getReason()).isEqualTo(reason);
    }
}
