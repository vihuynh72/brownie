package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.template.FieldType;

import java.util.Objects;

/**
 * The decision about one candidate: whether it becomes a place to fill,
 * what it is called and what type it holds. {@code suggestedType} is the
 * type as the namer put it (TEXT, DATE, NUMBER or LONG_TEXT), kept beside
 * the stored {@code type}, which only has TEXT and DATE today.
 * {@code requiredHint} is only a hint: a guess must never stop an export,
 * so nothing makes a place required on its strength.
 */
public record NamedSpot(String id, boolean keep, String label, FieldType type, String suggestedType,
                        boolean requiredHint, boolean namedByModel) {

    public NamedSpot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
    }
}
