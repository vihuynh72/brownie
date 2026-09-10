package io.github.vihuynh72.brownie.api.source;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.evidence.EvidenceLocator;
import io.github.vihuynh72.brownie.core.evidence.InvalidEvidenceLocatorException;
import io.github.vihuynh72.brownie.core.evidence.ResolvedEvidence;
import io.github.vihuynh72.brownie.core.evidence.SourceSpan;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.source.SourceService;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * Attaches artifacts as sources and creates/resolves citations into them.
 * Deliberately minimal, the same reasoning already applied to {@code
 * ExtractionController}: nothing yet drives this (a generation workflow,
 * a citation UI) exists, but a real, small, working surface is easier to
 * extend later than a placeholder is to first build correctly.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/sources")
class SourceController {

    private final SourceService sourceService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    SourceController(
            SourceService sourceService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.sourceService = sourceService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    /** Attaches a READY artifact as a source, or returns the snapshot that already does. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SnapshotResponse attach(@PathVariable long workspaceId, @AuthenticationPrincipal OidcUser principal, @RequestBody AttachRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        return SnapshotResponse.from(sourceService.attachSnapshot(workspaceId, userId, request.artifactId()));
    }

    /**
     * Creates a citation into a snapshot's current extraction. A locator
     * that does not actually resolve against real extracted content is a
     * real 400, not a persisted span -- see {@code
     * ApiExceptionHandler#handleInvalidEvidenceLocator}.
     */
    @PostMapping("/{snapshotId}/spans")
    @ResponseStatus(HttpStatus.CREATED)
    SpanResponse createSpan(
            @PathVariable long workspaceId,
            @PathVariable long snapshotId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody CreateSpanRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        SourceSpan span = sourceService.createSpan(workspaceId, userId, snapshotId, request.toLocator());
        ResolvedEvidence resolved = sourceService.resolveSpan(workspaceId, userId, span.id());
        return SpanResponse.from(resolved);
    }

    @GetMapping("/{snapshotId}/spans/{spanId}")
    SpanResponse resolveSpan(
            @PathVariable long workspaceId,
            @PathVariable long snapshotId,
            @PathVariable long spanId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        ResolvedEvidence resolved = sourceService.resolveSpan(workspaceId, userId, spanId);
        return SpanResponse.from(resolved);
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

    record AttachRequest(long artifactId) {
    }

    record SnapshotResponse(long id, long artifactId, String kind, OffsetDateTime fetchedAt) {
        static SnapshotResponse from(SourceSnapshot snapshot) {
            return new SnapshotResponse(snapshot.id(), snapshot.artifactId(), snapshot.kind().name(), snapshot.fetchedAt());
        }
    }

    /**
     * {@code type} selects which of the other, format-specific fields are
     * used: {@code partName}/{@code nodeId} for {@code "DOCX"}, {@code
     * pageNumber}/{@code lineIndex} for {@code "PDF"}, neither for {@code
     * "PLAIN_TEXT"}. {@code startCodePoint}/{@code endCodePointExclusive}
     * apply to every type.
     */
    record CreateSpanRequest(
            String type, String partName, String nodeId, Integer pageNumber, Integer lineIndex, int startCodePoint,
            int endCodePointExclusive) {
        EvidenceLocator toLocator() {
            if (type == null) {
                throw new InvalidEvidenceLocatorException("A locator 'type' is required.");
            }
            return switch (type) {
                case "DOCX" -> new EvidenceLocator.Docx(
                        requireField(partName, "partName"), requireField(nodeId, "nodeId"), startCodePoint, endCodePointExclusive);
                case "PDF" -> new EvidenceLocator.Pdf(
                        requireField(pageNumber, "pageNumber"), requireField(lineIndex, "lineIndex"), startCodePoint,
                        endCodePointExclusive);
                case "PLAIN_TEXT" -> new EvidenceLocator.PlainText(startCodePoint, endCodePointExclusive);
                default -> throw new InvalidEvidenceLocatorException("Unknown locator type '" + type + "'.");
            };
        }

        private static <T> T requireField(T value, String fieldName) {
            if (value == null) {
                throw new InvalidEvidenceLocatorException("Field '" + fieldName + "' is required for this locator type.");
            }
            return value;
        }
    }

    record SpanResponse(
            long id, long sourceSnapshotId, String extractionParserVersion, String excerptHash, String excerptText,
            OffsetDateTime createdAt) {
        static SpanResponse from(ResolvedEvidence resolved) {
            SourceSpan span = resolved.span();
            return new SpanResponse(
                    span.id(), span.sourceSnapshotId(), span.extractionParserVersion(), span.excerptHash(), resolved.excerptText(),
                    span.createdAt());
        }
    }
}
