package com.labflow.backend.molecularinput;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects/{projectId}/inputs")
public class MolecularInputController {

    private final MolecularInputService molecularInputService;

    public MolecularInputController(MolecularInputService molecularInputService) {
        this.molecularInputService = molecularInputService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MolecularInputResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId,
            @RequestPart("file") MultipartFile file
    ) {
        MolecularInputUploadResult result = molecularInputService.upload(
                projectId,
                currentUserId(jwt),
                file.getOriginalFilename(),
                readContent(file)
        );
        MolecularInputResponse response = MolecularInputResponse.from(result.input());
        if (result.created()) {
            URI location = URI.create("/api/projects/" + projectId + "/inputs/" + response.id());
            return ResponseEntity.created(location).body(response);
        }
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping
    public List<MolecularInputResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId
    ) {
        return molecularInputService.list(projectId, currentUserId(jwt)).stream()
                .map(MolecularInputResponse::from)
                .toList();
    }

    @GetMapping("/{inputId}")
    public MolecularInputResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId,
            @PathVariable long inputId
    ) {
        return MolecularInputResponse.from(
                molecularInputService.get(projectId, inputId, currentUserId(jwt))
        );
    }

    private byte[] readContent(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new MolecularInputReadException(exception);
        }
    }

    private long currentUserId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
