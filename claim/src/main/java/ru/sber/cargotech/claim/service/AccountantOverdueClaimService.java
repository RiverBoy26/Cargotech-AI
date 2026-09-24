package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.AccountantClaimSubmissionRequest;
import ru.sber.cargotech.claim.dto.ClaimDetailsResponse;
import ru.sber.cargotech.claim.dto.CreateClaimRequest;
import ru.sber.cargotech.claim.enums.ClaimType;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountantOverdueClaimService {
    private final ClaimService claimService;

    @Transactional
    public ClaimDetailsResponse confirmNonPayment(
        CurrentClaimUser user,
        UUID shipmentId,
        AccountantClaimSubmissionRequest request
    ) {
        ClaimDetailsResponse created = claimService.create(
            user,
            new CreateClaimRequest(
                null,
                shipmentId,
                null,
                null,
                ClaimType.PAYMENT_DELAY,
                request.reason().trim(),
                null,
                null,
                null,
                false,
                null,
                null
            )
        );

        return claimService.submitToLegalReview(user, created.id(), request);
    }
}
