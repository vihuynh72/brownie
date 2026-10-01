package io.github.vihuynh72.brownie.core.template;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A person saying a spot Brownie found is right. Until then the page marks
 * the spot as found by Brownie; afterwards it reads like any other field.
 * A review belongs to the template, not to one version of it: a field's id
 * is never reused within a template, so a kept field stays kept in every
 * later version that still has it. Keeping a field twice changes nothing.
 */
public class FillSpotReviewService {

    private final TemplateRepository templateRepository;
    private final FillSpotReviewRepository reviewRepository;

    public FillSpotReviewService(TemplateRepository templateRepository, FillSpotReviewRepository reviewRepository) {
        this.templateRepository = templateRepository;
        this.reviewRepository = reviewRepository;
    }

    /**
     * Keeps each field. Throws {@link TemplateNotFoundException} for a
     * template the person cannot see, {@link MalformedTemplateRequestException}
     * for an id that could not be a field's, and {@link
     * TemplateFieldNotFoundException} for one the template's open draft and
     * active version both lack; nothing is kept unless every id is good.
     */
    public void keep(long workspaceId, long userId, long templateId, List<String> fieldIds) {
        Template template = templateRepository.find(workspaceId, userId, templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
        if (fieldIds.isEmpty()) {
            throw new MalformedTemplateRequestException("Name at least one field to keep.");
        }
        Set<String> known = currentFieldIds(workspaceId, userId, template);
        for (String fieldId : fieldIds) {
            if (!FieldIds.isSafeId(fieldId) || fieldId.length() > MAX_REVIEWED_ID_LENGTH) {
                throw new MalformedTemplateRequestException("\"" + fieldId + "\" is not a field id.");
            }
            if (!known.contains(fieldId)) {
                throw new TemplateFieldNotFoundException(templateId, fieldId);
            }
        }
        reviewRepository.keep(workspaceId, userId, templateId, Set.copyOf(fieldIds));
    }

    /** The template's kept fields; a template the person cannot see has none. */
    public Set<String> keptFieldIds(long workspaceId, long userId, long templateId) {
        return reviewRepository.keptFieldIds(workspaceId, userId, templateId);
    }

    /** The longest field id a review can name, as its table allows. */
    static final int MAX_REVIEWED_ID_LENGTH = 64;

    private Set<String> currentFieldIds(long workspaceId, long userId, Template template) {
        Set<String> ids = new HashSet<>();
        templateRepository.findDraftVersion(workspaceId, userId, template.id())
                .ifPresent(draft -> draft.fieldDefinitions().forEach(field -> ids.add(field.fieldId())));
        if (template.currentActiveVersionId() != null) {
            templateRepository.findVersion(workspaceId, userId, template.id(), template.currentActiveVersionId())
                    .ifPresent(active -> active.fieldDefinitions().forEach(field -> ids.add(field.fieldId())));
        }
        return ids;
    }
}
