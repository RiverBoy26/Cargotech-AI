package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.dto.AccountantClaimSubmissionRequest;
import ru.sber.cargotech.claim.dto.ClaimDetailsResponse;
import ru.sber.cargotech.claim.dto.ClaimCalculationResponse;
import ru.sber.cargotech.claim.dto.ClaimVersionResponse;
import ru.sber.cargotech.claim.dto.CreateClaimVersionRequest;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.ClaimVersionSource;
import ru.sber.cargotech.claim.enums.ShipmentStatus;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimQueryRepository;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.repository.ClaimStatusHistoryRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceAccountantSubmissionTest {
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
    void savesAccountantTextAndReturnsPendingLegalReviewStatus() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(
            UUID.randomUUID(), organizationId, "Анна", "Смирнова", null, List.of("ACCOUNTANT")
        );
        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(organizationId);
        claim.setShipmentId(shipmentId);
        claim.setStatus(ClaimStatus.DRAFT);
        ClaimShipment shipment = new ClaimShipment();
        shipment.setStatus(ShipmentStatus.COMPLETED);
        ClaimVersionResponse version = mock(ClaimVersionResponse.class);
        ClaimDetailsResponse details = mock(ClaimDetailsResponse.class);
        ClaimCalculationResponse calculation = mock(ClaimCalculationResponse.class);

        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
            .thenReturn(Optional.of(claim));
        when(shipmentService.getEntity(organizationId, shipmentId)).thenReturn(shipment);
        when(claimRepository.save(any(ClaimEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionService.create(any(), any(), any())).thenReturn(version);
        when(calculation.remainingDebt()).thenReturn(new java.math.BigDecimal("100.00"));
        when(calculationService.recalculate(user, claimId)).thenReturn(calculation);
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
        AccountantClaimSubmissionRequest request = new AccountantClaimSubmissionRequest(
            "  Неисполнение обязанности по оплате  ",
            "  Просим оплатить задолженность по завершённому рейсу.  "
        );

        ClaimDetailsResponse result = service.submitToLegalReview(user, claimId, request);

        assertThat(result).isSameAs(details);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PENDING_LEGAL_REVIEW);
        assertThat(claim.getReason()).isEqualTo("Неисполнение обязанности по оплате");
        assertThat(claim.isNonPaymentConfirmed()).isTrue();
        assertThat(claim.getNonPaymentConfirmedBy()).isEqualTo(user.userId());
        assertThat(claim.getFinalVersionId()).isNull();

        ArgumentCaptor<CreateClaimVersionRequest> versionCaptor =
            ArgumentCaptor.forClass(CreateClaimVersionRequest.class);
        verify(versionService).create(org.mockito.ArgumentMatchers.eq(user),
            org.mockito.ArgumentMatchers.eq(claimId), versionCaptor.capture());
        assertThat(versionCaptor.getValue().source()).isEqualTo(ClaimVersionSource.ACCOUNTANT);
        assertThat(versionCaptor.getValue().content())
            .isEqualTo("Просим оплатить задолженность по завершённому рейсу.");
        assertThat(versionCaptor.getValue().finalVersion()).isFalse();

        InOrder operationOrder = inOrder(calculationService, claimRepository, versionService);
        operationOrder.verify(calculationService).recalculate(user, claimId);
        operationOrder.verify(claimRepository).findByIdAndOrganizationId(claimId, organizationId);
        operationOrder.verify(versionService).create(
            org.mockito.ArgumentMatchers.eq(user),
            org.mockito.ArgumentMatchers.eq(claimId),
            any(CreateClaimVersionRequest.class)
        );
    }

    @Test
    void rejectsNonPaymentConfirmationUntilShipmentIsCompleted() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(
            UUID.randomUUID(), organizationId, "Анна", "Смирнова", null, List.of("ACCOUNTANT")
        );
        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(organizationId);
        claim.setShipmentId(shipmentId);
        claim.setStatus(ClaimStatus.DRAFT);
        ClaimShipment shipment = new ClaimShipment();
        shipment.setStatus(ShipmentStatus.IN_PROGRESS);
        ClaimCalculationResponse calculation = mock(ClaimCalculationResponse.class);

        when(calculation.remainingDebt()).thenReturn(new java.math.BigDecimal("100.00"));
        when(calculationService.recalculate(user, claimId)).thenReturn(calculation);
        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
            .thenReturn(Optional.of(claim));
        when(shipmentService.getEntity(organizationId, shipmentId)).thenReturn(shipment);

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
        AccountantClaimSubmissionRequest request = new AccountantClaimSubmissionRequest(
            "Неоплата",
            "Текст претензии"
        );

        assertThatThrownBy(() -> service.submitToLegalReview(user, claimId, request))
            .isInstanceOf(ClaimException.class)
            .hasMessage("Подтвердить неуплату можно только после завершения рейса");
    }
}
