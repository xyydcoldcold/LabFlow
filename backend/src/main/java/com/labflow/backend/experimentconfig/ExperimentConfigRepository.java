package com.labflow.backend.experimentconfig;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperimentConfigRepository extends JpaRepository<ExperimentConfig, Long> {

    @Query("""
            SELECT COALESCE(MAX(config.version), 0)
            FROM ExperimentConfig config
            WHERE config.project.id = :projectId AND config.name = :name
            """)
    int findLatestVersion(@Param("projectId") Long projectId, @Param("name") String name);

    Optional<ExperimentConfig> findByIdAndProject_Id(Long id, Long projectId);

    List<ExperimentConfig> findAllByProject_IdOrderByNameAscVersionDesc(Long projectId);
}
