package io.github.vihuynh72.brownie.api.document;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.ExtractionResult;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionNotFoundException;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfPage;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersion;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.text.CodePoints;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Triggers structural extraction on a READY artifact and reads back its
 * result, dispatching by the artifact's own detected media type rather
 * than asking the caller which extractor to run -- the server already
 * knows. Deliberately minimal: nothing downstream consumes this yet
 * (template teaching and generation, which will, do not exist), so this is
 * a small, real, working surface rather than a placeholder -- extended
 * when an actual caller needs more than trigger-and-read.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/artifacts/{artifactId}/extraction")
class ExtractionController {

    private final DocumentExtractionService documentExtractionService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    ExtractionController(
            DocumentExtractionService documentExtractionService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.documentExtractionService = documentExtractionService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    /**
     * Always returns 200 with whatever terminal outcome the extraction
     * actually reached -- COMPLETE, UNSUPPORTED, or FAILED are all real,
     * successfully persisted answers, not error responses. A real HTTP
     * error means the request itself could not even be attempted: the
     * artifact does not exist (404), or is not READY yet (409).
     */
    @PostMapping
    ExtractionResponse extract(@PathVariable long workspaceId, @PathVariable long artifactId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        return ExtractionResponse.from(documentExtractionService.extract(workspaceId, userId, artifactId));
    }

    @GetMapping
    ExtractionResponse latest(@PathVariable long workspaceId, @PathVariable long artifactId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        return documentExtractionService
                .findLatestResult(workspaceId, userId, artifactId)
                .map(ExtractionResponse::from)
                .orElseThrow(() -> new ExtractionVersionNotFoundException(artifactId));
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated principal has no recorded identity for issuer/subject " + issuer + "/" + subject))
                .id();
    }

    record FeatureFindingResponse(String feature, String location, String detail) {
        static FeatureFindingResponse from(DocxFeatureFinding finding) {
            return new FeatureFindingResponse(finding.feature().name(), finding.location(), finding.detail());
        }
    }

    record PageResponse(int pageNumber, double width, double height, int rotationDegrees, boolean hasExtractableText, int lineCount) {
        static PageResponse from(PdfPage page) {
            return new PageResponse(
                    page.pageNumber(), page.width(), page.height(), page.rotationDegrees(), page.hasExtractableText(), page.lines().size());
        }
    }

    /**
     * One combined response shape for every format: {@code
     * unsupportedFeatures} is populated only for a DOCX result, {@code
     * unsupportedReason}/{@code pages} only for a PDF one, {@code
     * normalizedTextLength} only for a plain-text one -- the same
     * per-format-optional-field convention {@code Artifact} itself already
     * uses. Full page/line/text detail is deliberately not serialized
     * here; only summaries, since nothing downstream reads any of it over
     * HTTP yet and a real transcript's full text easily dwarfs a status
     * response.
     */
    record ExtractionResponse(
            long id,
            String format,
            String parserVersion,
            String status,
            List<FeatureFindingResponse> unsupportedFeatures,
            String unsupportedReason,
            String unsupportedDetail,
            List<PageResponse> pages,
            Integer normalizedTextLength,
            String failureReason) {
        static ExtractionResponse from(ExtractionResult result) {
            return switch (result) {
                case ExtractionResult.Docx docx -> fromDocx(docx.version());
                case ExtractionResult.Pdf pdf -> fromPdf(pdf.version());
                case ExtractionResult.PlainText plainText -> fromPlainText(plainText.version());
            };
        }

        private static ExtractionResponse fromDocx(ExtractionVersion version) {
            List<FeatureFindingResponse> findings = version.featureReport() == null
                    ? List.of()
                    : version.featureReport().findings().stream().map(FeatureFindingResponse::from).toList();
            return new ExtractionResponse(
                    version.id(), "DOCX", version.parserVersion(), version.status().name(), findings, null, null, List.of(), null,
                    version.failureReason());
        }

        private static ExtractionResponse fromPdf(PdfExtractionVersion version) {
            List<PageResponse> pages = version.graph() == null
                    ? List.of()
                    : version.graph().pages().stream().map(PageResponse::from).toList();
            return new ExtractionResponse(
                    version.id(),
                    "PDF",
                    version.parserVersion(),
                    version.status().name(),
                    List.of(),
                    version.unsupportedReason() == null ? null : version.unsupportedReason().name(),
                    version.unsupportedDetail(),
                    pages,
                    null,
                    version.failureReason());
        }

        private static ExtractionResponse fromPlainText(PlainTextExtractionVersion version) {
            Integer normalizedTextLength =
                    version.graph() == null ? null : CodePoints.length(version.graph().normalizedText());
            return new ExtractionResponse(
                    version.id(),
                    "PLAIN_TEXT",
                    version.parserVersion(),
                    version.status().name(),
                    List.of(),
                    null,
                    null,
                    List.of(),
                    normalizedTextLength,
                    version.failureReason());
        }
    }
}
