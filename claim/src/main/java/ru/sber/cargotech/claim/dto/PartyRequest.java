package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.sber.cargotech.claim.enums.PartyType;

public record PartyRequest(
    @NotNull PartyType type,
    @NotBlank @Size(max = 500) String name,
    @Size(max = 12) String inn,
    @Size(max = 9) String kpp,
    @Size(max = 15) String ogrn,
    String legalAddress,
    String postalAddress,
    @Email @Size(max = 320) String email,
    @Size(max = 64) String phone
) {
}
