package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.dto.PaymentPreflightResponse;
import ru.sber.cargotech.claim.dto.StatusChangeRequest;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimQueryRepository;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.repository.ClaimStatusHistoryRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
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
    void checksPaymentBeforeLoadingClaim() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        CurrentClaimUser user = new CurrentClaimUser(UUID.randomUUID(), organizationId);

        PaymentPreflightResponse preflight = new PaymentPreflightResponse();
        preflight.setCanSend(true);

        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(organizationId);
        claim.setStatus(ClaimStatus.DRAFT);

        when(paymentClient.preflightCheck(claimId, "Проверка оплаты перед отправкой претензии"))
                .thenReturn(preflight);
        when(claimRepository.findByIdAndOrganizationId(claimId, organizationId))
                .thenReturn(Optional.of(claim));

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

        InOrder order = inOrder(paymentClient, claimRepository);
        order.verify(paymentClient).preflightCheck(
                claimId,
                "Проверка оплаты перед отправкой претензии"
        );
        order.verify(claimRepository).findByIdAndOrganizationId(
                claimId,
                organizationId
        );
    }
}
