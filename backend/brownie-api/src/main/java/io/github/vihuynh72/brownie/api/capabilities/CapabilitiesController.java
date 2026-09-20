package io.github.vihuynh72.brownie.api.capabilities;

import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.retention.DeletionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * What this deployment accepts, stated before a person chooses a file:
 * the upload size limit and the media types the upload route will
 * finalize, plus which of those Assist can read as a source today, and how
 * long something stays in the trash before it is deleted for good. The
 * values come from the same configuration the upload and deletion routes
 * enforce, so the number a person sees is the number that will be applied.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
class CapabilitiesController {

    private static final List<SupportedMediaType> ASSIST_SOURCE_MEDIA_TYPES = List.of(SupportedMediaType.PLAIN_TEXT);
    private static final List<SupportedMediaType> TEMPLATE_MEDIA_TYPES = List.of(SupportedMediaType.DOCX);

    private final long maxUploadBytes;
    private final int trashRetentionDays;

    CapabilitiesController(
            @Value("${brownie.artifacts.max-upload-bytes:10485760}") long maxUploadBytes,
            DeletionService deletionService) {
        this.maxUploadBytes = maxUploadBytes;
        this.trashRetentionDays = deletionService.trashRetentionDays();
    }

    @GetMapping
    CapabilitiesResponse capabilities() {
        return new CapabilitiesResponse(
                maxUploadBytes,
                Arrays.stream(SupportedMediaType.values()).map(MediaTypeResponse::from).toList(),
                ASSIST_SOURCE_MEDIA_TYPES.stream().map(SupportedMediaType::mimeType).toList(),
                TEMPLATE_MEDIA_TYPES.stream().map(SupportedMediaType::mimeType).toList(),
                trashRetentionDays);
    }

    record MediaTypeResponse(String mediaType, String extension) {
        static MediaTypeResponse from(SupportedMediaType type) {
            return new MediaTypeResponse(type.mimeType(), type.defaultFileExtension());
        }
    }

    record CapabilitiesResponse(
            long maxUploadBytes,
            List<MediaTypeResponse> uploadMediaTypes,
            List<String> assistSourceMediaTypes,
            List<String> templateMediaTypes,
            int trashRetentionDays) {
    }
}
