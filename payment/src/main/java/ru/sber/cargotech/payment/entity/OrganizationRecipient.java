package ru.sber.cargotech.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

@Getter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "auth_organizations", schema = "cargotech")
public class OrganizationRecipient {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "inn")
    private String inn;
}
