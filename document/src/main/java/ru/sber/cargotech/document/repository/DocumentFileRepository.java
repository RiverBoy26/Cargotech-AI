package ru.sber.cargotech.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.document.entity.DocumentFile;

import java.util.UUID;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, UUID> {
}
