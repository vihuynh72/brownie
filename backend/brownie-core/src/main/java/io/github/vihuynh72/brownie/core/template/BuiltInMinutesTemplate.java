package io.github.vihuynh72.brownie.core.template;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One supported meeting-minutes fixture and the synthetic documents that
 * qualify its declared bindings. Fixture paths are repository-relative so
 * a delivery adapter can import the bytes without duplicating the schema.
 */
public record BuiltInMinutesTemplate(
        String id,
        String displayName,
        String templateFixturePath,
        List<FieldDefinition> fields,
        List<QualificationArtifact> qualificationArtifacts) {

    public BuiltInMinutesTemplate {
        requireText(id, "id");
        requireText(displayName, "displayName");
        requireText(templateFixturePath, "templateFixturePath");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        qualificationArtifacts = List.copyOf(Objects.requireNonNull(qualificationArtifacts, "qualificationArtifacts"));
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields must not be empty");
        }
        if (qualificationArtifacts.isEmpty()) {
            throw new IllegalArgumentException("qualificationArtifacts must not be empty");
        }
        Set<String> fieldIds = new HashSet<>();
        for (FieldDefinition field : fields) {
            if (!fieldIds.add(field.fieldId())) {
                throw new IllegalArgumentException("fields contains duplicate fieldId \"" + field.fieldId() + "\"");
            }
        }
    }

    /** One generated, synthetic document used to check a template's behavior. */
    public record QualificationArtifact(
            String id, String fixturePath, List<String> expectedText, List<String> forbiddenText) {

        public QualificationArtifact {
            requireText(id, "id");
            requireText(fixturePath, "fixturePath");
            expectedText = List.copyOf(Objects.requireNonNull(expectedText, "expectedText"));
            forbiddenText = List.copyOf(Objects.requireNonNull(forbiddenText, "forbiddenText"));
            if (expectedText.isEmpty()) {
                throw new IllegalArgumentException("expectedText must not be empty");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
