package com.labflow.backend.molecularinput;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

import com.labflow.backend.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "molecular_inputs")
public class MolecularInput {

    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    private String originalFilename;

    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;

    @Column(name = "artifact_path", nullable = false, length = 255, updatable = false, unique = true)
    private String artifactPath;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MolecularInput() {
    }

    MolecularInput(
            Project project,
            String originalFilename,
            String sha256,
            String artifactPath,
            long sizeBytes,
            Instant createdAt
    ) {
        if (project == null || project.getId() == null) {
            throw new IllegalArgumentException("Project must be persisted before adding a molecular input");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Molecular input size cannot be negative");
        }

        this.project = project;
        this.originalFilename = requireText(originalFilename, "Original filename");
        this.sha256 = requireSha256(sha256);
        this.artifactPath = requireText(artifactPath, "Artifact path");
        this.sizeBytes = sizeBytes;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getSha256() {
        return sha256;
    }

    public String getArtifactPath() {
        return artifactPath;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }

    private static String requireSha256(String value) {
        Objects.requireNonNull(value);
        if (!SHA256_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("SHA-256 must contain exactly 64 lowercase hexadecimal characters");
        }
        return value;
    }
}
