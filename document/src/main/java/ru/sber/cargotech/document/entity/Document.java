package ru.sber.cargotech.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import ru.sber.cargotech.document.enums.DocumentSource;
import ru.sber.cargotech.document.enums.DocumentStatus;
import ru.sber.cargotech.document.enums.DocumentType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(schema = "cargotech", name = "document_documents")
public class Document {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private DocumentFile file;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 64)
    private DocumentType documentType;

    @Column(name = "document_number")
    private String documentNumber;

    @Column(name = "document_date")
    private LocalDate documentDate;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(nullable = false)
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (source == null) {
            source = DocumentSource.UPLOADED;
        }
        if (status == null) {
            status = DocumentStatus.ACTIVE;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
        version++;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public DocumentFile getFile() { return file; }
    public void setFile(DocumentFile file) { this.file = file; }
    public DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentType documentType) { this.documentType = documentType; }
    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }
    public LocalDate getDocumentDate() { return documentDate; }
    public void setDocumentDate(LocalDate documentDate) { this.documentDate = documentDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public DocumentSource getSource() { return source; }
    public void setSource(DocumentSource source) { this.source = source; }
    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
