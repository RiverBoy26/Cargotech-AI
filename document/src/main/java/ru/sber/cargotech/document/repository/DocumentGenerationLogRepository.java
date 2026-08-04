package ru.sber.cargotech.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.document.entity.DocumentGenerationLog;

import java.util.UUID;

public interface DocumentGenerationLogRepository extends JpaRepository<DocumentGenerationLog, UUID> {
}
