package com.labflow.backend.experimentconfig;

import java.time.Instant;
import java.util.Objects;

import com.labflow.backend.auth.AppUser;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "experiment_configs")
public class ExperimentConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(nullable = false, length = 200, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode spec;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExperimentConfig() {
    }

    ExperimentConfig(Project project, String name, int version, JsonNode spec, AppUser createdBy, Instant createdAt) {
        if (project == null || project.getId() == null) {
            throw new IllegalArgumentException("Project must be persisted before adding a config");
        }
        if (createdBy == null || createdBy.getId() == null) {
            throw new IllegalArgumentException("Creator must be persisted before adding a config");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Config version must be positive");
        }
        this.project = project;
        this.name = requireName(name);
        this.version = version;
        this.spec = Objects.requireNonNull(spec).deepCopy();
        this.createdBy = createdBy;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public JsonNode getSpec() { return spec.deepCopy(); }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    private static String requireName(String value) {
        Objects.requireNonNull(value);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException("Config name must contain 1 to 200 characters");
        }
        return normalized;
    }
}
