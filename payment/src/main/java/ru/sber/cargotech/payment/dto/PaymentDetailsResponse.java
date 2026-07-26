package ru.sber.cargotech.payment.dto;

import java.util.List;

public record PaymentDetailsResponse(
    PaymentResponse payment,
    List<PaymentMatchResponse> matches
) {
}
