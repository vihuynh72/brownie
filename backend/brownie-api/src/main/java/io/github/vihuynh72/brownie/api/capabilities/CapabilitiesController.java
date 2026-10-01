package io.github.vihuynh72.brownie.api.capabilities;

import io.github.vihuynh72.brownie.api.action.ConfiguredActionOffer;
import io.github.vihuynh72.brownie.api.connector.DriveOffer;
import io.github.vihuynh72.brownie.api.connector.GoogleConnectorSetup;
import io.github.vihuynh72.brownie.api.generation.FillSpotNamingSetting;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
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
 *
 * <p>{@code googleConnectorAccess} is what a person can connect a Google
 * account for here and then use: empty when this deployment has no Google
 * registration, so the interface does not offer it. Calendar is offered
 * whenever Google is set up; Drive only when it is switched on and a reader
 * that can open Drive files is plugged in, so it is never offered before it
 * can work.
 *
 * <p>{@code googleActions} is which changes in a person's Google account this
 * deployment makes (each only on that person's approval of the exact change):
 * empty unless Google is set up and such changes are switched on.
 *
 * <p>{@code formFileTypes} is what a file chooser for a form to fill may
 * offer, and how each is filled: a Word document as it is, another word
 * processor's file after it is converted to one, a PDF as a PDF. A browser
 * reports some types under more than one name, so an extension may appear
 * once for each; the server still judges every file by its bytes.
 *
 * <p>{@code fillSpotNaming} is who names the places Brownie finds in an
 * uploaded form: {@code MODEL} when the form's text is sent to the model for
 * that, {@code RULES} when Brownie's own rules name them on the server.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
class CapabilitiesController {

    private static final List<SupportedMediaType> ASSIST_SOURCE_MEDIA_TYPES = List.of(SupportedMediaType.PLAIN_TEXT);
    private static final List<SupportedMediaType> TEMPLATE_MEDIA_TYPES = List.of(SupportedMediaType.DOCX, SupportedMediaType.PDF);
    private static final List<FormFileTypeResponse> FORM_FILE_TYPES = List.of(
            FormFileTypeResponse.of("docx", SupportedMediaType.DOCX),
            FormFileTypeResponse.of("dotx", SupportedMediaType.DOTX),
            FormFileTypeResponse.of("docm", SupportedMediaType.DOCM),
            FormFileTypeResponse.of("dotm", SupportedMediaType.DOTM),
            FormFileTypeResponse.of("doc", SupportedMediaType.DOC),
            FormFileTypeResponse.of("dot", SupportedMediaType.DOC),
            FormFileTypeResponse.of("rtf", SupportedMediaType.RTF),
            FormFileTypeResponse.of("rtf", "text/rtf", SupportedMediaType.RTF),
            FormFileTypeResponse.of("odt", SupportedMediaType.ODT),
            FormFileTypeResponse.of("ott", "application/vnd.oasis.opendocument.text-template", SupportedMediaType.ODT),
            FormFileTypeResponse.of("pages", SupportedMediaType.PAGES),
            FormFileTypeResponse.of("pages", "application/x-iwork-pages-sffpages", SupportedMediaType.PAGES),
            FormFileTypeResponse.of("pdf", SupportedMediaType.PDF));

    private final long maxUploadBytes;
    private final int trashRetentionDays;
    private final List<String> googleConnectorAccess;
    private final List<String> googleActions;
    private final String fillSpotNaming;

    CapabilitiesController(
            @Value("${brownie.artifacts.max-upload-bytes:10485760}") long maxUploadBytes,
            DeletionService deletionService,
            GoogleConnectorSetup googleConnectorSetup,
            DriveOffer driveOffer,
            ConfiguredActionOffer actionOffer,
            FillSpotNamingSetting fillSpotNamingSetting) {
        this.maxUploadBytes = maxUploadBytes;
        this.trashRetentionDays = deletionService.trashRetentionDays();
        this.googleConnectorAccess = Arrays.stream(ConnectorAccess.values())
                .filter(access -> googleConnectorSetup.configured() && switch (access) {
                    case DRIVE_FILES -> driveOffer.offered();
                    case CALENDAR_EVENTS -> true;
                    // A connection that only writes is offered exactly when some change made through it is.
                    default -> actionOffer.uses(access);
                })
                .map(ConnectorAccess::name)
                .toList();
        this.googleActions = actionOffer.names();
        this.fillSpotNaming = fillSpotNamingSetting.naming();
    }

    @GetMapping
    CapabilitiesResponse capabilities() {
        return new CapabilitiesResponse(
                maxUploadBytes,
                Arrays.stream(SupportedMediaType.values()).map(MediaTypeResponse::from).toList(),
                ASSIST_SOURCE_MEDIA_TYPES.stream().map(SupportedMediaType::mimeType).toList(),
                TEMPLATE_MEDIA_TYPES.stream().map(SupportedMediaType::mimeType).toList(),
                trashRetentionDays,
                googleConnectorAccess,
                googleActions,
                FORM_FILE_TYPES,
                fillSpotNaming);
    }

    /** How a form in this format is filled: as the Word document it is, as a Word copy converted from it, or as a PDF. */
    enum FormFileRoute {
        NATIVE,
        CONVERTED,
        PDF
    }

    record FormFileTypeResponse(String extension, String mediaType, FormFileRoute route) {

        static FormFileTypeResponse of(String extension, SupportedMediaType type) {
            return of(extension, type.mimeType(), type);
        }

        static FormFileTypeResponse of(String extension, String mediaType, SupportedMediaType type) {
            FormFileRoute route = switch (type.wordRoute()) {
                case NATIVE, NATIVE_VARIANT -> FormFileRoute.NATIVE;
                case CONVERT -> FormFileRoute.CONVERTED;
                case NONE -> {
                    if (type != SupportedMediaType.PDF) {
                        throw new IllegalArgumentException(type + " is not a form a person can fill.");
                    }
                    yield FormFileRoute.PDF;
                }
            };
            return new FormFileTypeResponse(extension, mediaType, route);
        }
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
            int trashRetentionDays,
            List<String> googleConnectorAccess,
            List<String> googleActions,
            List<FormFileTypeResponse> formFileTypes,
            String fillSpotNaming) {
    }
}
