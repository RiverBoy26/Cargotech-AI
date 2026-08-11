package ru.sber.cargotech.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(schema = "cargotech", name = "document_claim_template_versions")
public class ClaimTemplateVersion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private ClaimTemplate template;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "encrypted_content", nullable = false, columnDefinition = "text")
    private String encryptedContent;

    @Column(name = "content_sha256", nullable = false, length = 128)
    private String contentSha256;

    @Column(name = "encryption_key_id", nullable = false, length = 128)
    private String encryptionKeyId;

    @Column(name = "encryption_algorithm", nullable = false, length = 64)
    private String encryptionAlgorithm;

    @Column(name = "content_format", nullable = false, length = 32)
    private String contentFormat;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> variables;

    @Column(name = "change_comment", columnDefinition = "text")
    private String changeComment;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (encryptionAlgorithm == null || encryptionAlgorithm.isBlank()) {
            encryptionAlgorithm = "AES-256-GCM";
        }
        if (contentFormat == null || contentFormat.isBlank()) {
            contentFormat = "MUSTACHE_TEXT";
        }
        if (variables == null) {
            variables = List.of();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public ClaimTemplate getTemplate() { return template; }
    public void setTemplate(ClaimTemplate template) { this.template = template; }
    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
    public String getEncryptedContent() { return encryptedContent; }
    public void setEncryptedContent(String encryptedContent) { this.encryptedContent = encryptedContent; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getEncryptionKeyId() { return encryptionKeyId; }
    public void setEncryptionKeyId(String encryptionKeyId) { this.encryptionKeyId = encryptionKeyId; }
    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    public void setEncryptionAlgorithm(String encryptionAlgorithm) { this.encryptionAlgorithm = encryptionAlgorithm; }
    public String getContentFormat() { return contentFormat; }
    public void setContentFormat(String contentFormat) { this.contentFormat = contentFormat; }
    public List<String> getVariables() { return variables; }
    public void setVariables(List<String> variables) { this.variables = variables; }
    public String getChangeComment() { return changeComment; }
    public void setChangeComment(String changeComment) { this.changeComment = changeComment; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
