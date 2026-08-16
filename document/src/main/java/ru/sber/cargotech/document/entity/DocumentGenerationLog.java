package ru.sber.cargotech.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.sber.cargotech.document.enums.GeneratedDocumentType;
import ru.sber.cargotech.document.enums.GenerationStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(schema = "cargotech", name = "document_generation_logs")
public class DocumentGenerationLog {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "claim_version_id")
    private UUID claimVersionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_type", nullable = false, length = 64)
    private GeneratedDocumentType outputType;

    @Column(name = "source_version_id")
    private UUID sourceVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GenerationStatus status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "generated_by")
    private UUID generatedBy;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null) {
            status = GenerationStatus.CREATED;
        }
        if (generatedAt == null) {
            generatedAt = OffsetDateTime.now();
        }
        if (requestSnapshot == null) {
            requestSnapshot = Map.of();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID claimId) { this.claimId = claimId; }
    public UUID getClaimVersionId() { return claimVersionId; }
    public void setClaimVersionId(UUID claimVersionId) { this.claimVersionId = claimVersionId; }
    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }
    public GeneratedDocumentType getOutputType() { return outputType; }
    public void setOutputType(GeneratedDocumentType outputType) { this.outputType = outputType; }
    public UUID getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(UUID sourceVersionId) { this.sourceVersionId = sourceVersionId; }
    public Map<String, Object> getRequestSnapshot() { return requestSnapshot; }
    public void setRequestSnapshot(Map<String, Object> requestSnapshot) { this.requestSnapshot = requestSnapshot; }
    public GenerationStatus getStatus() { return status; }
    public void setStatus(GenerationStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public UUID getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(UUID generatedBy) { this.generatedBy = generatedBy; }
    public OffsetDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(OffsetDateTime generatedAt) { this.generatedAt = generatedAt; }
}
