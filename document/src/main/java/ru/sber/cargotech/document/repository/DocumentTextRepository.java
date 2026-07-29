package ru.sber.cargotech.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.document.entity.DocumentText;

import java.util.Optional;
import java.util.UUID;

public interface DocumentTextRepository extends JpaRepository<DocumentText, UUID> {

    Optional<DocumentText> findByDocument_Id(UUID documentId);
}
