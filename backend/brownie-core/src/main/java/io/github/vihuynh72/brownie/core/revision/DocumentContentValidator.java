package io.github.vihuynh72.brownie.core.revision;

import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the bounded content representation against one template version. */
final class DocumentContentValidator {

    private DocumentContentValidator() {
    }

    static void validate(DocumentContent content, List<FieldDefinition> definitions) {
        Map<String, FieldDefinition> definitionsById = definitionsById(definitions);
        List<DocumentContentProblem> problems = new ArrayList<>();
        for (Map.Entry<String, FieldValue> entry : content.fields().entrySet()) {
            validateValue(entry.getKey(), entry.getValue(), definitionsById, problems);
        }
        throwIfProblems(problems);
    }

    static DocumentContent applyEdits(
            DocumentContent current, List<DocumentFieldEdit> edits, List<FieldDefinition> definitions) {
        if (edits == null || edits.isEmpty()) {
            throw new IllegalArgumentException("At least one typed field edit is required.");
        }
        Map<String, FieldDefinition> definitionsById = definitionsById(definitions);
        Map<String, FieldValue> fields = new HashMap<>(current.fields());
        List<DocumentContentProblem> problems = new ArrayList<>();
        Set<String> editedFieldIds = new HashSet<>();
        for (DocumentFieldEdit edit : edits) {
            if (!editedFieldIds.add(edit.fieldId())) {
                problems.add(new DocumentContentProblem(
                        edit.fieldId(), DocumentContentProblemReason.DUPLICATE_EDIT,
                        "A field can be edited at most once in one revision."));
                continue;
            }
            switch (edit) {
                case DocumentFieldEdit.SetValue(String fieldId, FieldValue value) -> {
                    if (validateValue(fieldId, value, definitionsById, problems)) {
                        fields.put(fieldId, value);
                    }
                }
                case DocumentFieldEdit.ClearValue(String fieldId) -> {
                    if (definitionsById.containsKey(fieldId)) {
                        fields.remove(fieldId);
                    } else {
                        problems.add(new DocumentContentProblem(
                                fieldId, DocumentContentProblemReason.UNKNOWN_FIELD,
                                "The selected template version does not define this field."));
                    }
                }
            }
        }
        throwIfProblems(problems);
        return new DocumentContent(fields);
    }

    private static Map<String, FieldDefinition> definitionsById(List<FieldDefinition> definitions) {
        Map<String, FieldDefinition> definitionsById = new HashMap<>();
        for (FieldDefinition definition : definitions) {
            FieldDefinition previous = definitionsById.put(definition.fieldId(), definition);
            if (previous != null) {
                throw new IllegalStateException("Template version contains duplicate field ID " + definition.fieldId() + ".");
            }
        }
        return definitionsById;
    }

    private static boolean validateValue(
            String fieldId,
            FieldValue value,
            Map<String, FieldDefinition> definitionsById,
            List<DocumentContentProblem> problems) {
        FieldDefinition definition = definitionsById.get(fieldId);
        if (definition == null) {
            problems.add(new DocumentContentProblem(
                    fieldId, DocumentContentProblemReason.UNKNOWN_FIELD,
                    "The selected template version does not define this field."));
            return false;
        }
        boolean valid = true;
        if (definition.type() != value.type()) {
            problems.add(new DocumentContentProblem(
                    fieldId,
                    DocumentContentProblemReason.TYPE_MISMATCH,
                    "Template type " + definition.type() + " does not accept value type " + value.type() + "."));
            valid = false;
        }
        if (definition.cardinality() != value.cardinality()) {
            problems.add(new DocumentContentProblem(
                    fieldId,
                    DocumentContentProblemReason.CARDINALITY_MISMATCH,
                    "Template cardinality " + definition.cardinality()
                            + " does not accept value cardinality " + value.cardinality() + "."));
            valid = false;
        }
        return valid;
    }

    private static void throwIfProblems(List<DocumentContentProblem> problems) {
        if (!problems.isEmpty()) {
            throw new DocumentContentValidationException(problems);
        }
    }
}
