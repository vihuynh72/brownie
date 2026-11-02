package io.github.vihuynh72.brownie.api.detail;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.core.detail.DetailLearningService;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Learns the answers a person approved on a form into their saved details,
 * and undoes one such run. Owner-only: the service reads and writes as the
 * signed-in person, and a batch fill never teaches anything.
 */
@RestController
@RequestMapping("/api/v1/me/details")
class DetailLearningController {

    private final DetailLearningService detailLearningService;
    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;

    DetailLearningController(
            DetailLearningService detailLearningService,
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository) {
        this.detailLearningService = detailLearningService;
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /** Called after the person approves a fill: decides who the form is about and saves the mapped answers. */
    @PostMapping("/learn")
    LearningResponse learn(@AuthenticationPrincipal OidcUser oidcUser, @RequestBody LearnRequest request) {
        UserIdentity identity = identityOf(oidcUser);
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(identity.id()).id();
        DetailLearningService.LearningResult result =
                detailLearningService.learn(workspaceId, identity.id(), request.documentId(), request.revisionId(), identity.displayName());
        return new LearningResponse(
                result.learningId(), result.subject().name(),
                result.saved().stream().map(each -> new LearnedKey(each.key(), each.label())).toList(),
                result.replaced().stream().map(each -> new LearnedKey(each.key(), each.label())).toList(),
                result.reason());
    }

    /** Undoes one learn run: restores what it replaced, deletes what it added, only where the value is still the one it wrote. */
    @PostMapping("/learnings/{id}/undo")
    ResponseEntity<Void> undo(@AuthenticationPrincipal OidcUser oidcUser, @PathVariable long id) {
        UserIdentity identity = identityOf(oidcUser);
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(identity.id()).id();
        detailLearningService.undo(workspaceId, identity.id(), id);
        return ResponseEntity.noContent().build();
    }

    private UserIdentity identityOf(OidcUser oidcUser) {
        if (oidcUser == null) {
            throw new AuthenticatedIdentityMissingException();
        }
        return userIdentityRepository
                .findByIssuerAndSubject(oidcUser.getIssuer().toString(), oidcUser.getSubject())
                .orElseThrow(AuthenticatedIdentityMissingException::new);
    }

    public record LearnRequest(long documentId, long revisionId) {
    }

    public record LearnedKey(String key, String label) {
    }

    public record LearningResponse(Long learningId, String subject, List<LearnedKey> saved, List<LearnedKey> replaced, String reason) {
    }
}
