package com.labflow.backend.molecularinput;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.labflow.backend.common.api.RestAccessDeniedHandler;
import com.labflow.backend.common.api.RestAuthenticationEntryPoint;
import com.labflow.backend.config.SecurityConfiguration;
import com.labflow.backend.project.ProjectAccessDeniedException;
import com.labflow.backend.project.ProjectNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

@WebMvcTest(MolecularInputController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({
        SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
class MolecularInputControllerTest {

    private static final long PROJECT_ID = 10L;
    private static final long USER_ID = 20L;
    private static final long INPUT_ID = 30L;
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private static final String SHA256 = "ac320214c0999aeaf989356386836df037c824731536d6b9ac6dcb5e1fd66c45";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MolecularInputService molecularInputService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void molecularInputApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/projects/{projectId}/inputs", PROJECT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void newUploadReturns201AndSafeMetadata() throws Exception {
        byte[] content = validXyz();
        MolecularInput input = input();
        when(molecularInputService.upload(PROJECT_ID, USER_ID, "hydrogen.xyz", content))
                .thenReturn(new MolecularInputUploadResult(input, true));

        mockMvc.perform(multipart("/api/projects/{projectId}/inputs", PROJECT_ID)
                        .file(file("hydrogen.xyz", content))
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/projects/" + PROJECT_ID + "/inputs/" + INPUT_ID
                ))
                .andExpect(jsonPath("$.id").value(INPUT_ID))
                .andExpect(jsonPath("$.originalFilename").value("hydrogen.xyz"))
                .andExpect(jsonPath("$.sha256").value(SHA256))
                .andExpect(jsonPath("$.sizeBytes").value(content.length))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.artifactPath").doesNotExist());

        verify(molecularInputService).upload(PROJECT_ID, USER_ID, "hydrogen.xyz", content);
    }

    @Test
    void duplicateUploadReturns200() throws Exception {
        byte[] content = validXyz();
        MolecularInput input = input();
        when(molecularInputService.upload(PROJECT_ID, USER_ID, "hydrogen.xyz", content))
                .thenReturn(new MolecularInputUploadResult(input, false));

        mockMvc.perform(multipart("/api/projects/{projectId}/inputs", PROJECT_ID)
                        .file(file("hydrogen.xyz", content))
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(INPUT_ID));
    }

    @Test
    void listReturnsSafeInputMetadata() throws Exception {
        MolecularInput input = input();
        when(molecularInputService.list(PROJECT_ID, USER_ID)).thenReturn(List.of(input));

        mockMvc.perform(get("/api/projects/{projectId}/inputs", PROJECT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(INPUT_ID))
                .andExpect(jsonPath("$[0].sha256").value(SHA256))
                .andExpect(jsonPath("$[0].artifactPath").doesNotExist());
    }

    @Test
    void getUsesBothProjectAndInputIdentifiers() throws Exception {
        MolecularInput input = input();
        when(molecularInputService.get(PROJECT_ID, INPUT_ID, USER_ID)).thenReturn(input);

        mockMvc.perform(get("/api/projects/{projectId}/inputs/{inputId}", PROJECT_ID, INPUT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(INPUT_ID));

        verify(molecularInputService).get(PROJECT_ID, INPUT_ID, USER_ID);
    }

    @Test
    void invalidInputReturnsStandard400() throws Exception {
        byte[] content = validXyz();
        when(molecularInputService.upload(PROJECT_ID, USER_ID, "hydrogen.xyz", content))
                .thenThrow(new InvalidMolecularInputException("Invalid XYZ content"));

        mockMvc.perform(multipart("/api/projects/{projectId}/inputs", PROJECT_ID)
                        .file(file("hydrogen.xyz", content))
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MOLECULAR_INPUT"))
                .andExpect(jsonPath("$.message").value("Invalid XYZ content"));
    }

    @Test
    void oversizedInputReturns413() throws Exception {
        byte[] content = validXyz();
        when(molecularInputService.upload(PROJECT_ID, USER_ID, "hydrogen.xyz", content))
                .thenThrow(new MolecularInputTooLargeException(1024));

        mockMvc.perform(multipart("/api/projects/{projectId}/inputs", PROJECT_ID)
                        .file(file("hydrogen.xyz", content))
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("MOLECULAR_INPUT_TOO_LARGE"));
    }

    @Test
    void missingFilePartReturnsStandard400() throws Exception {
        mockMvc.perform(multipart("/api/projects/{projectId}/inputs", PROJECT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingInputReturns404() throws Exception {
        when(molecularInputService.get(PROJECT_ID, INPUT_ID, USER_ID))
                .thenThrow(new MolecularInputNotFoundException(PROJECT_ID, INPUT_ID));

        mockMvc.perform(get("/api/projects/{projectId}/inputs/{inputId}", PROJECT_ID, INPUT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOLECULAR_INPUT_NOT_FOUND"));
    }

    @Test
    void missingProjectReturns404() throws Exception {
        when(molecularInputService.list(PROJECT_ID, USER_ID))
                .thenThrow(new ProjectNotFoundException(PROJECT_ID));

        mockMvc.perform(get("/api/projects/{projectId}/inputs", PROJECT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void crossProjectAccessReturns403() throws Exception {
        when(molecularInputService.list(PROJECT_ID, USER_ID))
                .thenThrow(new ProjectAccessDeniedException(PROJECT_ID));

        mockMvc.perform(get("/api/projects/{projectId}/inputs", PROJECT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(USER_ID)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));
    }

    private MockMultipartFile file(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "chemical/x-xyz", content);
    }

    private MolecularInput input() {
        MolecularInput input = mock(MolecularInput.class);
        when(input.getId()).thenReturn(INPUT_ID);
        when(input.getOriginalFilename()).thenReturn("hydrogen.xyz");
        when(input.getSha256()).thenReturn(SHA256);
        when(input.getSizeBytes()).thenReturn((long) validXyz().length);
        when(input.getCreatedAt()).thenReturn(NOW);
        return input;
    }

    private byte[] validXyz() {
        return "2\nHydrogen molecule\nH 0 0 0\nH 0 0 0.74\n".getBytes(StandardCharsets.UTF_8);
    }
}
