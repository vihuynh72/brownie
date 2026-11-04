package io.github.vihuynh72.brownie.api.form;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.form.CatalogReadyRepository;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The form library's two questions: "what forms do you have?" -- answered
 * anonymously, so the landing page can show them -- and "start this one for
 * me", which fetches the official PDF and runs it through the same intake a
 * person's own upload takes.
 */
@RestController
@RequestMapping("/api/v1/forms")
class FormsController {

    private final FormLibraryCatalog catalog;
    private final CatalogFormFetcher fetcher;
    private final CatalogReadyService catalogReadyService;
    private final FormIntakeService intakeService;
    private final RevisionService revisionService;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;

    FormsController(
            FormLibraryCatalog catalog,
            CatalogFormFetcher fetcher,
            CatalogReadyService catalogReadyService,
            FormIntakeService intakeService,
            RevisionService revisionService,
            CanonicalRequestHasher canonicalRequestHasher,
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository) {
        this.catalog = catalog;
        this.fetcher = fetcher;
        this.catalogReadyService = catalogReadyService;
        this.intakeService = intakeService;
        this.revisionService = revisionService;
        this.canonicalRequestHasher = canonicalRequestHasher;
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
    }

    @GetMapping
    FormsResponse search(@RequestParam(name = "q", required = false) String q) {
        return new FormsResponse(catalog.search(q).stream().map(FormsController::formResponse).toList());
    }

    @PostMapping("/{id}/start")
    StartResponse start(@PathVariable String id, @AuthenticationPrincipal OidcUser oidcUser) throws IOException {
        FormLibraryCatalog.Entry entry = catalog.find(id).orElseThrow(() -> new FormLibraryNotFoundException(id));
        UserIdentity identity = identityOf(oidcUser);
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(identity.id()).id();
        // Kept agency bytes skip the download while they are fresh; the take-in
        // below replays the kept preparation, so a second start only stores the
        // file and builds the template -- the read and the naming do not run again.
        byte[] pdf;
        java.util.Optional<CatalogReadyRepository.ReadyForm> fresh = catalogReadyService.fresh(id);
        if (fresh.isPresent()) {
            pdf = fresh.get().sourceBytes();
        } else {
            try {
                pdf = fetcher.fetch(entry);
            } catch (IOException | IllegalStateException e) {
                throw new FormFetchException(entry.agency(), e instanceof IOException ? e : new IOException(e));
            }
            java.util.Optional<CatalogReadyRepository.ReadyForm> stale = catalogReadyService.find(id);
            if (stale.isPresent() && catalogReadyService.sameBytes(stale.get(), pdf)) {
                catalogReadyService.restamp(id);
            } else {
                catalogReadyService.remember(id, pdf);
            }
        }
        FormIntakeService.IntakeResult taken =
                intakeService.takeIn(workspaceId, identity.id(), entry.title(), new ByteArrayInputStream(pdf), true);
        DocumentMutationResult mutation = revisionService.createDocument(
                workspaceId,
                identity.id(),
                new IdempotencyKey("form-library-" + entry.id() + "-" + UUID.randomUUID()),
                canonicalRequestHasher.hash(java.util.Map.of("op", "form_library_start", "form_id", entry.id())),
                entry.title(),
                taken.template().id(),
                taken.activeVersion().id(),
                DocumentContent.empty(),
                Map.of(),
                "Started from the form library");
        return new StartResponse(mutation.document().id(), mutation.document().title());
    }

    private UserIdentity identityOf(OidcUser oidcUser) {
        if (oidcUser == null) {
            throw new AuthenticatedIdentityMissingException();
        }
        return userIdentityRepository
                .findByIssuerAndSubject(oidcUser.getIssuer().toString(), oidcUser.getSubject())
                .orElseThrow(AuthenticatedIdentityMissingException::new);
    }

    private static FormResponse formResponse(FormLibraryCatalog.Entry entry) {
        return new FormResponse(
                entry.id(), entry.title(), entry.agency(), entry.category(), entry.description());
    }

    record FormsResponse(List<FormResponse> forms) {
    }

    record FormResponse(String id, String title, String agency, String category, String description) {
    }

    record StartResponse(long documentId, String title) {
    }
}
