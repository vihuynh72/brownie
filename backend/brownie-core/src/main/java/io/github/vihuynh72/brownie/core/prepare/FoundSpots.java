package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link FillSpotCandidateFinder} found in one form: the candidate
 * places in document order, the outline the naming step reads them in, the
 * table rows that could repeat (only ever the filler's one repeatable
 * row), and what the person should be told about places that could not
 * become spots. {@code collapsedRows} maps an offered row to the identical
 * empty rows under it that go if it repeats, since one repeating row then
 * stands for all of them. {@code standingTags} are the tags the form's
 * controls keep whatever the plan does -- kept ones, and those in headers,
 * footers, text boxes, checkboxes and pictures, which are never re-tagged
 * -- so no new field id may be one of them.
 */
public record FoundSpots(
        List<SpotCandidate> candidates,
        List<OutlineLine> outline,
        List<String> offeredRowKeys,
        Map<String, List<CollapsedRow>> collapsedRows,
        List<PreparationNotice> notices,
        Set<String> standingTags) {

    /** A row that is left out when the row above it repeats: its key, and its node id in the main document. */
    public record CollapsedRow(String rowKey, String rowNodeId) {
    }

    public FoundSpots {
        candidates = List.copyOf(candidates);
        outline = List.copyOf(outline);
        offeredRowKeys = List.copyOf(offeredRowKeys);
        collapsedRows = Map.copyOf(collapsedRows);
        notices = List.copyOf(notices);
        standingTags = Set.copyOf(standingTags);
    }

    public FoundSpots(List<SpotCandidate> candidates, List<OutlineLine> outline, List<String> offeredRowKeys,
                      Map<String, List<CollapsedRow>> collapsedRows, List<PreparationNotice> notices) {
        this(candidates, outline, offeredRowKeys, collapsedRows, notices, Set.of());
    }
}
