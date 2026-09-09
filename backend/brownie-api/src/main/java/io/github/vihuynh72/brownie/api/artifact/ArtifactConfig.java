package io.github.vihuynh72.brownie.api.artifact;

import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
class ArtifactConfig {

    // A pilot default, not yet exposed as its own setting -- nothing today
    // needs it to be smaller for testing, since expiry is only ever
    // checked lazily when an artifact is actually touched again.
    private static final Duration ABANDONED_UPLOAD_TTL = Duration.ofHours(24);

    @Bean
    ArtifactService artifactService(
            ArtifactRepository artifactRepository,
            BlobStore blobStore,
            @Value("${brownie.artifacts.max-upload-bytes:10485760}") long maxUploadBytes) {
        return new ArtifactService(artifactRepository, blobStore, maxUploadBytes, ABANDONED_UPLOAD_TTL);
    }
}
