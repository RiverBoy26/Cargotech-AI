package ru.sber.cargotech.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.sber.cargotech.auth.enums.OrganizationStatus;

public record CreateOrganizationRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 12) String inn,
    @Size(max = 9) String kpp,
    @Size(max = 15) String ogrn,
    String legalAddress,
    String postalAddress,
    @Email @Size(max = 320) String email,
    @Size(max = 64) String phone,
    OrganizationStatus status
) {
}
