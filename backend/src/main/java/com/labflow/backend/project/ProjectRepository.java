package com.labflow.backend.project;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findAllByOwner_IdOrderByCreatedAtDesc(Long ownerId);
}
