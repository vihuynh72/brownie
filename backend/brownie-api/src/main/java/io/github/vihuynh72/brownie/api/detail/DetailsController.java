package io.github.vihuynh72.brownie.api.detail;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.core.detail.DetailKey;
import io.github.vihuynh72.brownie.core.detail.DetailOrigin;
import io.github.vihuynh72.brownie.core.detail.SavedDetail;
import io.github.vihuynh72.brownie.core.detail.SavedDetailRepository;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The person's saved details -- "Your details". Every route acts as the
 * signed-in owner and on their own rows only; the row-level security on
 * {@code saved_detail} is the backstop. Values are never logged anywhere.
 */
@RestController
@RequestMapping("/api/v1/me/details")
class DetailsController {

    private static final int PLAIN_MAX = 2000;
    private static final int SENSITIVE_MAX = 200;

    private final SavedDetailRepository savedDetailRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final DetailSealer detailSealer;

    DetailsController(
            SavedDetailRepository savedDetailRepository,
            WorkspaceRepository workspaceRepository,
            UserIdentityRepository userIdentityRepository,
            DetailSealer detailSealer) {
        this.savedDetailRepository = savedDetailRepository;
        this.workspaceRepository = workspaceRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.detailSealer = detailSealer;
    }

    @GetMapping
    DetailsResponse list(@AuthenticationPrincipal OidcUser oidcUser) {
        UserIdentity identity = identityOf(oidcUser);
        List<SavedDetailResponse> details = savedDetailRepository.list(identity.id()).stream()
                .map(DetailsController::toResponse)
                .sorted(Comparator.comparingInt(SavedDetailResponse::groupOrdinal).thenComparingInt(SavedDetailResponse::keyOrdinal))
                .toList();
        return new DetailsResponse(details, detailSealer.available());
    }

    /**
     * A hand save: a non-sensitive value lands plainly; a sensitive one is
     * sealed before it reaches the store and only its hint comes back.
     */
    @PutMapping("/{key}")
    SavedDetailResponse save(@AuthenticationPrincipal OidcUser oidcUser, @PathVariable String key,
            @RequestBody SaveDetailRequest request) {
        UserIdentity identity = identityOf(oidcUser);
        DetailKey detailKey = DetailKey.find(key).orElseThrow(() -> new SavedDetailNotFoundException(key));
        String value = request.value();
        if (value == null || value.isBlank()) {
            throw new SavedDetailValueException("A saved detail needs a value.");
        }
        int max = detailKey.sensitive() ? SENSITIVE_MAX : PLAIN_MAX;
        if (value.length() > max) {
            throw new SavedDetailValueException("A saved detail can be at most " + max + " characters.");
        }
        String valueText = null;
        byte[] valueSealed = null;
        String valueHint = null;
        if (detailKey.sensitive()) {
            valueSealed = detailSealer.seal(value);
            valueHint = DetailSealer.hint(value);
        } else {
            valueText = value;
        }
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(identity.id()).id();
        SavedDetail row = savedDetailRepository.upsert(
                identity.id(), workspaceId, detailKey.key(), detailKey.label(),
                valueText, valueSealed, valueHint, detailKey.sensitive(),
                DetailOrigin.MANUAL, null, null, null);
        return toResponse(row);
    }

    @DeleteMapping("/{key}")
    ResponseEntity<Void> deleteOne(@AuthenticationPrincipal OidcUser oidcUser, @PathVariable String key) {
        UserIdentity identity = identityOf(oidcUser);
        savedDetailRepository.delete(identity.id(), key);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    ResponseEntity<Void> deleteAll(@AuthenticationPrincipal OidcUser oidcUser) {
        UserIdentity identity = identityOf(oidcUser);
        savedDetailRepository.deleteAll(identity.id());
        return ResponseEntity.noContent().build();
    }

    private static SavedDetailResponse toResponse(SavedDetail detail) {
        Optional<DetailKey> detailKey = DetailKey.find(detail.key());
        int groupOrdinal = detailKey.map(each -> each.group().ordinal()).orElse(Integer.MAX_VALUE);
        int keyOrdinal = detailKey.map(Enum::ordinal).orElse(Integer.MAX_VALUE);
        DetailSource source = detail.sourceDocumentId() == null && detail.sourceLabel() == null && detail.sourceTitle() == null
                ? null
                : new DetailSource(detail.sourceDocumentId(), detail.sourceLabel(), detail.sourceTitle());
        return new SavedDetailResponse(
                detail.key(),
                detail.label(),
                detailKey.map(each -> each.group().name()).orElse("SENSITIVE"),
                detail.sensitive(),
                detail.valueText(),
                detail.valueHint(),
                detail.origin().name(),
                source,
                detail.savedAt(),
                detail.confirmedAt(),
                groupOrdinal,
                keyOrdinal);
    }

    private UserIdentity identityOf(OidcUser oidcUser) {
        if (oidcUser == null) {
            throw new AuthenticatedIdentityMissingException();
        }
        return userIdentityRepository
                .findByIssuerAndSubject(oidcUser.getIssuer().toString(), oidcUser.getSubject())
                .orElseThrow(AuthenticatedIdentityMissingException::new);
    }




}
