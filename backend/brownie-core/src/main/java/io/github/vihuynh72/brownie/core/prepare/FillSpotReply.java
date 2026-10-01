package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;
import java.util.Objects;

/**
 * A naming reply exactly as the model gave it, once its shape has been
 * checked and before anything in it is trusted: an id may not have been
 * offered, a label may be unusable, a type may be one Brownie does not
 * store. {@link ModelSpotNamer} decides what of it to keep.
 */
public record FillSpotReply(List<Spot> spots, String repeatingRow) {

    public FillSpotReply {
        spots = List.copyOf(spots);
    }

    /** One decision in the reply; {@code type} is the model's word for it (TEXT, DATE, NUMBER or LONG_TEXT). */
    public record Spot(String id, boolean keep, String label, String type, boolean required) {

        public Spot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(type, "type");
        }
    }
}
