package io.github.vihuynh72.brownie.core.template;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One real-world form Brownie gives a brand-new workspace, packaged with the
 * application. Unlike {@link BuiltInMinutesTemplate} -- the fixture catalog
 * behind the qualification tests, whose entries carry repository-relative
 * fixture paths and generated sample documents -- a built-in a person
 * actually uses needs only its packaged source file and the fields bound to
 * it. {@code extension} names the file type under {@code
 * builtin-templates/} ("pdf", "docx"), and {@code fields} are the
 * definitions provisioning binds, each with the {@link FieldBindingTarget}
 * that belongs to that kind of source.
 */
public record BuiltInTemplate(
        String id, String displayName, String extension, List<FieldDefinition> fields, boolean retired) {

    public BuiltInTemplate(String id, String displayName, String extension, List<FieldDefinition> fields) {
        this(id, displayName, extension, fields, false);
    }

    public BuiltInTemplate {
        requireText(id, "id");
        requireText(displayName, "displayName");
        requireText(extension, "extension");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields must not be empty");
        }
        Set<String> fieldIds = new HashSet<>();
        for (FieldDefinition field : fields) {
            if (!fieldIds.add(field.fieldId())) {
                throw new IllegalArgumentException("fields contains duplicate fieldId \"" + field.fieldId() + "\"");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
