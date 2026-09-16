package com.labflow.backend.project;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findAllByOwner_IdOrderByCreatedAtDesc(Long ownerId);

    @EntityGraph(attributePaths = "owner")
    @Query("""
            SELECT project
            FROM Project project
            WHERE project.owner.id = :userId
               OR EXISTS (
                   SELECT membership.id
                   FROM ProjectMember membership
                   WHERE membership.project.id = project.id
                     AND membership.user.id = :userId
               )
            ORDER BY project.createdAt DESC
            """)
    List<Project> findAllVisibleTo(@Param("userId") Long userId);
}
