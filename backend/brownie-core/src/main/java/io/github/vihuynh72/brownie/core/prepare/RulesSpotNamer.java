package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.template.DocxControlOrigin;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldIds;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import io.github.vihuynh72.brownie.core.template.TableCellLabels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Brownie's own rules for naming the places found in a Word form, and for
 * turning the naming step's decisions into the plan the editor carries
 * out. The rules always run: their labels and types are what the naming
 * step is handed as its hint, and what stands whenever it decides nothing.
 *
 * <p>A place's rules label ({@link SpotCandidate#rulesLabel()}) is the
 * first of: a bracket's own words; a form field's own name or prompt; the
 * words just before the blank in its line, back to the previous blank, a
 * sentence end or a tab, with instruction words taken off ("please write
 * your name here:" is "Name"); the cell to its left, or the header above
 * it; the words just after it; and "Blank N". A place is a date when its
 * label names one or its blank is a date mask.
 *
 * <p>The plan gives each kept place a field id made from its label by
 * {@link FieldIds#fromLabel}, without the words in brackets that end it
 * ({@link TableCellLabels#idWords}), never one any version of the template used
 * before nor one a control of the form keeps (in a header, say). A
 * control the form already tagged with a safe, unique id keeps that id and
 * is not touched. Only one row can repeat, the one {@code
 * FieldBindingCandidateProposer} would have proposed: the last row of the
 * body's first table, with a row above it and no other place in the
 * table. When that table ends in several identical empty rows, one of them
 * repeats and the rest go, with a {@link PreparationNotice#TABLE_ROWS_GROW}
 * line. Every spot is optional: a guess that a place is required is only
 * ever a hint. A place the naming step left out is counted in a {@link
 * PreparationNotice#PLACES_LEFT_OUT} line, so the person is told.
 */
public final class RulesSpotNamer {

    private RulesSpotNamer() {
    }

    /**
     * What the naming step is asked about: every place except the controls
     * the form's author already made fields of, whose names are the form's
     * own and are not second-guessed. A place the rules are sure of ({@link
     * SpotCandidate.Tier#HIGH}) may not be left out, and the places of one
     * table row are kept or left out together.
     */
    public static SpotNamingInput namingInput(FoundSpots found) {
        List<NamingCandidate> candidates = found.candidates().stream()
                .filter(candidate -> !candidate.formControl())
                .map(RulesSpotNamer::asNaming)
                .toList();
        return new SpotNamingInput(DocumentKind.WORD, found.outline(), candidates, found.offeredRowKeys());
    }

    /**
     * The plan for {@code naming}'s decisions. {@code takenIds} holds the
     * ids no new spot may take: every field id the template has ever used,
     * and for an upload every tag the form's controls carry.
     */
    public static SpotPlan plan(FoundSpots found, SpotNaming naming, Set<String> takenIds) {
        Map<String, NamedSpot> decided = new HashMap<>();
        naming.spots().forEach(spot -> decided.putIfAbsent(spot.id(), spot));

        String repeating = repeatingRow(found, naming, decided);
        Set<String> leftOutRows = new HashSet<>();
        List<String> removedRowNodeIds = new ArrayList<>();
        List<PreparationNotice> notices = new ArrayList<>(naming.notices());
        if (repeating != null) {
            for (FoundSpots.CollapsedRow row : found.collapsedRows().getOrDefault(repeating, List.of())) {
                leftOutRows.add(row.rowKey());
                removedRowNodeIds.add(row.rowNodeId());
            }
            notices.add(new PreparationNotice(PreparationNotice.TABLE_ROWS_GROW, 1, null));
        }

        Set<String> taken = new HashSet<>(takenIds);
        taken.addAll(found.standingTags());
        for (SpotCandidate candidate : found.candidates()) {
            if (candidate.keptAsTagged()) {
                taken.add(candidate.keptTag());
            }
        }
        Set<String> labels = new HashSet<>();
        List<SpotPlan.PlannedSpot> spots = new ArrayList<>();
        int leftOut = 0;
        for (SpotCandidate candidate : found.candidates()) {
            if (candidate.rowKey() != null && leftOutRows.contains(candidate.rowKey())) {
                continue;
            }
            FieldCardinality cardinality = repeating != null && repeating.equals(candidate.rowKey())
                    ? FieldCardinality.REPEATED
                    : FieldCardinality.SCALAR;
            if (candidate.keptAsTagged()) {
                FieldDefinition field = new FieldDefinition(candidate.keptTag(), candidate.rulesType(), cardinality,
                        FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(candidate.keptTag()), null,
                        SpotOrigin.FORM, DocxControlOrigin.ORIGINAL, null);
                labels.add(candidate.rulesLabel().toLowerCase(Locale.ROOT));
                spots.add(new SpotPlan.PlannedSpot(candidate.id(), candidate.kind(), field, null, NamingSource.RULES, false,
                        candidate.rulesType().name()));
                continue;
            }
            NamedSpot decision = candidate.formControl() ? RulesOnlySpotNamer.byRules(asNaming(candidate))
                    : decided.getOrDefault(candidate.id(), RulesOnlySpotNamer.byRules(asNaming(candidate)));
            if (!decision.keep() && !candidate.formControl()) {
                // A place to sign is counted by the finder, as a signature line left for the person.
                leftOut += candidate.signatureLike() ? 0 : 1;
                continue;
            }
            String label = FieldIds.normalizeLabel(decision.label());
            label = distinct(label == null ? candidate.rulesLabel() : label, labels);
            String fieldId = FieldIds.fromLabel(TableCellLabels.idWords(label), taken);
            taken.add(fieldId);
            SpotOrigin origin = candidate.formControl() ? SpotOrigin.FORM : SpotOrigin.FOUND_BY_BROWNIE;
            boolean control = candidate.kind() == SpotCandidate.Kind.EXISTING_TAGGED_CONTROL
                    || candidate.kind() == SpotCandidate.Kind.EXISTING_UNTAGGED_CONTROL;
            DocxControlOrigin docxControl = control ? DocxControlOrigin.TAGGED_BY_BROWNIE : DocxControlOrigin.INSERTED_BY_BROWNIE;
            SpotEdit edit = control
                    ? new SpotEdit.Retag(candidate.anchor().part(), candidate.anchor().controlNodeId(), fieldId, label)
                    : new SpotEdit.Insert(candidate.anchor(), fieldId, label, candidate.blankText());
            FieldDefinition field = new FieldDefinition(fieldId, decision.type(), cardinality, FieldRequiredness.OPTIONAL,
                    new FieldBindingTarget.ContentControlTag(fieldId), label, origin, docxControl, control ? null : candidate.blankText());
            spots.add(new SpotPlan.PlannedSpot(candidate.id(), candidate.kind(), field, edit,
                    decision.namedByModel() ? NamingSource.MODEL : NamingSource.RULES, decision.requiredHint(),
                    decision.suggestedType() == null ? decision.type().name() : decision.suggestedType()));
        }
        if (leftOut > 0) {
            notices.add(new PreparationNotice(PreparationNotice.PLACES_LEFT_OUT, leftOut, null));
        }
        return new SpotPlan(spots, removedRowNodeIds, notices);
    }

    /**
     * The naming step's row, when it is one that was offered and at least
     * one kept place sits in it. When the naming step chose no offered row,
     * an offered row that holds only the form's own controls repeats: the
     * naming step is never shown those controls, so it could neither choose
     * nor decline that row, and the form's author made its cells fields.
     */
    private static String repeatingRow(FoundSpots found, SpotNaming naming, Map<String, NamedSpot> decided) {
        String chosen = naming.repeatingRowKey();
        if (chosen == null || !found.offeredRowKeys().contains(chosen)) {
            return rowOfFormControls(found);
        }
        for (SpotCandidate candidate : found.candidates()) {
            if (!chosen.equals(candidate.rowKey())) {
                continue;
            }
            if (candidate.formControl()) {
                return chosen;
            }
            NamedSpot decision = decided.getOrDefault(candidate.id(), RulesOnlySpotNamer.byRules(asNaming(candidate)));
            if (decision.keep()) {
                return chosen;
            }
        }
        return null;
    }

    /** The first offered row whose places are all the form's own controls, or null when there is none. */
    private static String rowOfFormControls(FoundSpots found) {
        for (String rowKey : found.offeredRowKeys()) {
            List<SpotCandidate> inRow = found.candidates().stream()
                    .filter(candidate -> rowKey.equals(candidate.rowKey()))
                    .toList();
            if (!inRow.isEmpty() && inRow.stream().allMatch(SpotCandidate::formControl)) {
                return rowKey;
            }
        }
        return null;
    }

    /** A label no other spot of the form has yet: a second "Member name" is "Member name 2". */
    private static String distinct(String label, Set<String> labels) {
        String candidate = label;
        for (int n = 2; labels.contains(candidate.toLowerCase(Locale.ROOT)); n++) {
            String suffix = " " + n;
            String base = label.length() + suffix.length() > FieldIds.MAX_LABEL_LENGTH
                    ? label.substring(0, FieldIds.MAX_LABEL_LENGTH - suffix.length()).strip()
                    : label;
            candidate = base + suffix;
        }
        labels.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private static NamingCandidate asNaming(SpotCandidate candidate) {
        return new NamingCandidate(candidate.id(), candidate.kind().name(), candidate.rulesLabel(), candidate.rulesType(),
                candidate.context(), candidate.signatureLike(), candidate.rowKey(),
                candidate.tier() == SpotCandidate.Tier.HIGH && !candidate.signatureLike(), candidate.rowKey(), candidate.tableValues());
    }
}
