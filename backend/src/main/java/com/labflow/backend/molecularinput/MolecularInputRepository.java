package com.labflow.backend.molecularinput;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MolecularInputRepository extends JpaRepository<MolecularInput, Long> {

    Optional<MolecularInput> findByProject_IdAndSha256(Long projectId, String sha256);

    Optional<MolecularInput> findByIdAndProject_Id(Long id, Long projectId);

    List<MolecularInput> findAllByProject_IdOrderByCreatedAtDesc(Long projectId);
}
