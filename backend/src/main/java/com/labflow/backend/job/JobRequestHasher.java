package com.labflow.backend.job;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class JobRequestHasher {

    public String hash(CreateJobRequest request) {
        // A versioned semantic representation: JSON whitespace and property order do not matter.
        String canonical = "v1\n" + request.projectId() + "\n"
                + request.molecularInputId() + "\n" + request.experimentConfigId();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
