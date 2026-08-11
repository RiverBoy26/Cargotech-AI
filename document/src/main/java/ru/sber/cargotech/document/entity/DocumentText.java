package ru.sber.cargotech.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(schema = "cargotech", name = "document_document_texts")
public class DocumentText {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "text_content", nullable = false, columnDefinition = "text")
    private String textContent;

    @Column(name = "extraction_method", nullable = false, length = 64)
    private String extractionMethod;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(length = 16)
    private String language;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extraction_metadata", columnDefinition = "jsonb")
    private Map<String, Object> extractionMetadata;

    @Column(name = "extracted_at", nullable = false)
    private OffsetDateTime extractedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (extractedAt == null) {
            extractedAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }
    public String getTextContent() { return textContent; }
    public void setTextContent(String textContent) { this.textContent = textContent; }
    public String getExtractionMethod() { return extractionMethod; }
    public void setExtractionMethod(String extractionMethod) { this.extractionMethod = extractionMethod; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Map<String, Object> getExtractionMetadata() { return extractionMetadata; }
    public void setExtractionMetadata(Map<String, Object> extractionMetadata) { this.extractionMetadata = extractionMetadata; }
    public OffsetDateTime getExtractedAt() { return extractedAt; }
    public void setExtractedAt(OffsetDateTime extractedAt) { this.extractedAt = extractedAt; }
}
