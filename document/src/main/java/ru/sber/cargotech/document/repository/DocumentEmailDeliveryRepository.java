package ru.sber.cargotech.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.document.entity.DocumentEmailDelivery;

import java.util.List;
import java.util.UUID;

public interface DocumentEmailDeliveryRepository
    extends JpaRepository<DocumentEmailDelivery, UUID> {

    List<DocumentEmailDelivery> findAllByDocument_IdAndOrganizationIdOrderByCreatedAtDesc(
        UUID documentId,
        UUID organizationId
    );
}
