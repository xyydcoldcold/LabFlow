package com.labflow.backend.project;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {

    @EntityGraph(attributePaths = "user")
    Optional<ProjectMember> findByProject_IdAndUser_Id(Long projectId, Long userId);

    boolean existsByProject_IdAndUser_Id(Long projectId, Long userId);

    @EntityGraph(attributePaths = "user")
    List<ProjectMember> findAllByProject_IdOrderByCreatedAtAsc(Long projectId);

    List<ProjectMember> findAllByUser_IdOrderByCreatedAtDesc(Long userId);
}
