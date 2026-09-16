package com.labflow.backend.artifact;

import java.io.InputStream;

public interface ArtifactStorage {

    StoredArtifact store(InputStream content);

    InputStream open(String artifactPath);

    void delete(String artifactPath);
}
