package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.dto.AccountantClaimSubmissionRequest;
import ru.sber.cargotech.claim.dto.ClaimDetailsResponse;
import ru.sber.cargotech.claim.dto.CreateClaimRequest;
import ru.sber.cargotech.claim.enums.ClaimType;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountantOverdueClaimServiceTest {
    @Mock private ClaimService claimService;

    @Test
    void createsDraftAndSubmitsItToLegalReviewInOneOperation() {
        UUID shipmentId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(
            UUID.randomUUID(), UUID.randomUUID(), "Иван", "Иванов", null, List.of("ACCOUNTANT")
        );
        AccountantClaimSubmissionRequest request = new AccountantClaimSubmissionRequest(
            "  Просрочка оплаты по договору  ",
            "  Требуем погасить задолженность  "
        );
        ClaimDetailsResponse created = mock(ClaimDetailsResponse.class);
        ClaimDetailsResponse submitted = mock(ClaimDetailsResponse.class);
        when(created.id()).thenReturn(claimId);
        when(claimService.create(any(), any())).thenReturn(created);
        when(claimService.submitToLegalReview(user, claimId, request)).thenReturn(submitted);

        AccountantOverdueClaimService service = new AccountantOverdueClaimService(claimService);
        ClaimDetailsResponse result = service.confirmNonPayment(user, shipmentId, request);

        assertThat(result).isSameAs(submitted);
        ArgumentCaptor<CreateClaimRequest> createCaptor = ArgumentCaptor.forClass(CreateClaimRequest.class);
        InOrder order = inOrder(claimService);
        order.verify(claimService).create(org.mockito.ArgumentMatchers.eq(user), createCaptor.capture());
        order.verify(claimService).submitToLegalReview(user, claimId, request);
        CreateClaimRequest createRequest = createCaptor.getValue();
        assertThat(createRequest.shipmentId()).isEqualTo(shipmentId);
        assertThat(createRequest.claimType()).isEqualTo(ClaimType.PAYMENT_DELAY);
        assertThat(createRequest.reason()).isEqualTo("Просрочка оплаты по договору");
        assertThat(createRequest.nonPaymentConfirmed()).isFalse();
        assertThat(createRequest.draftContent()).isNull();
    }
}
