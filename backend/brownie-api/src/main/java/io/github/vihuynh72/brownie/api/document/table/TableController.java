package io.github.vihuynh72.brownie.api.document.table;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStorageException;
import io.github.vihuynh72.brownie.core.batch.BatchService;
import io.github.vihuynh72.brownie.core.batch.TableReading;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Reads an uploaded file as a table, once per ask: the preview a person
 * confirms before a batch is created against it. The read re-opens the file
 * every time rather than trusting an earlier read, because the batch it
 * feeds re-reads the same file the same way when it fills.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/artifacts/{artifactId}/table")
class TableController {

    private final BatchService batchService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    TableController(
            BatchService batchService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.batchService = batchService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    TableReadingResponse read(
            @PathVariable long workspaceId,
            @PathVariable long artifactId,
            @RequestBody(required = false) ReadTableRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = userIdentityRepository
                .findByIssuerAndSubject(principal.getIssuer().toString(), principal.getSubject())
                .orElseThrow(AuthenticatedIdentityMissingException::new)
                .id();
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        try {
            TableReading reading = batchService.readArtifactTable(
                    workspaceId, userId, artifactId, request == null
                            ? new ReadTableRequest(null, null, null).toDomain()
                            : request.toDomain());
            return TableReadingResponse.from(artifactId, reading, batchService.maxRows());
        } catch (IOException e) {
            throw new ArtifactStorageException("The file could not be read from storage.", e);
        }
    }
}
