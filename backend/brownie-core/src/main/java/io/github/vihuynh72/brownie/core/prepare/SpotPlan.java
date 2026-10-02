package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The spots a form will have and the edits that make them, decided before
 * the file is touched. {@code removedRowNodeIds} are the identical empty
 * table rows that go because the one row above them repeats instead.
 */
public record SpotPlan(List<PlannedSpot> spots, List<String> removedRowNodeIds, List<PreparationNotice> notices) {

    /**
     * One spot: the field it becomes, the edit that makes it (null for a
     * control the form already tagged with a usable id, which is kept as it
     * is), and who named it.
     */
    public record PlannedSpot(
            String candidateId,
            SpotCandidate.Kind kind,
            FieldDefinition field,
            SpotEdit edit,
            NamingSource namedBy,
            boolean requiredHint,
            String suggestedType) {

        public PlannedSpot {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(namedBy, "namedBy");
        }
    }

    public SpotPlan {
        spots = List.copyOf(spots);
        removedRowNodeIds = List.copyOf(removedRowNodeIds);
        notices = List.copyOf(notices);
    }

    public List<FieldDefinition> fields() {
        return spots.stream().map(PlannedSpot::field).toList();
    }

    /** The edits in the order the spots are listed; a kept control has none. */
    public List<SpotEdit> edits() {
        return spots.stream().map(PlannedSpot::edit).filter(Objects::nonNull).toList();
    }

    /**
     * The same plan without the spots whose fields failed a check, with a
     * {@link PreparationNotice#SPOTS_SKIPPED} line saying how many. When no
     * spot is left to repeat, the rows that were to go for the repeating
     * row stay, and nothing says the table grows.
     */
    public SpotPlan without(Set<String> fieldIds) {
        if (fieldIds.isEmpty()) {
            return this;
        }
        List<PlannedSpot> kept = spots.stream().filter(spot -> !fieldIds.contains(spot.field().fieldId())).toList();
        int skipped = spots.size() - kept.size();
        List<PreparationNotice> updated = new ArrayList<>(notices);
        if (skipped > 0) {
            updated.add(new PreparationNotice(PreparationNotice.SPOTS_SKIPPED, skipped, null));
        }
        SpotPlan fewer = new SpotPlan(kept, removedRowNodeIds, updated);
        return fewer.hasRepeatingRow() ? fewer : fewer.withRowsKept();
    }

    /**
     * The same plan with its one repeating row filled once, as single
     * values: what is left when repeating it does not work. The rows that
     * were to go for it stay, as the form had them.
     */
    public SpotPlan withoutRepeatingRow() {
        List<PlannedSpot> single = spots.stream().map(spot -> spot.field().cardinality() != FieldCardinality.REPEATED ? spot
                : new PlannedSpot(spot.candidateId(), spot.kind(), scalar(spot.field()), spot.edit(), spot.namedBy(),
                        spot.requiredHint(), spot.suggestedType())).toList();
        return new SpotPlan(single, removedRowNodeIds, notices).withRowsKept();
    }

    private SpotPlan withRowsKept() {
        List<PreparationNotice> updated = notices.stream()
                .filter(notice -> !notice.code().equals(PreparationNotice.TABLE_ROWS_GROW))
                .toList();
        return new SpotPlan(spots, List.of(), updated);
    }

    public boolean hasRepeatingRow() {
        return spots.stream().anyMatch(spot -> spot.field().cardinality() == FieldCardinality.REPEATED);
    }

    private static FieldDefinition scalar(FieldDefinition field) {
        return new FieldDefinition(field.fieldId(), field.type(), FieldCardinality.SCALAR, field.requiredness(), field.binding(),
                field.label(), field.origin(), field.docxControl(), field.blankText());
    }
}
