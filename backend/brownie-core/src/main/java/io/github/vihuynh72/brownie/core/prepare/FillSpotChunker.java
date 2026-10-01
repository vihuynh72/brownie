package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.model.ModelRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a document into the parts {@link ModelSpotNamer} sends, one
 * request each, in document order. A part holds at most a set number of
 * places and at most a set estimate of input tokens, measured on the very
 * request that will be sent ({@link UsageBudget#estimateInputTokens}), so
 * the bound is the one the usage ledger reserves against.
 *
 * <p>The outline is cut only between units: a line on its own, or a whole
 * table (the run of lines whose keys name the same table) together with
 * the line just before it, which is usually its heading, since a table's
 * blanks are named by its header row and the heading above it. A unit
 * too big for any part is cut into its lines; a single line too big for
 * any part, and every place after the last part the caller allows, is
 * left over for the rules to name. Places no outline line marks (a PDF
 * form field known only by its tooltip, say) come after the outline.
 */
final class FillSpotChunker {

    private static final Pattern MARKER = Pattern.compile("\\[\\[([A-Za-z0-9._-]{1,32})]]");
    private static final Pattern TABLE_KEY = Pattern.compile("T([0-9]+)(?![0-9]).*");

    private FillSpotChunker() {
    }

    /** One request's worth of the document: its places, the rows among them that could repeat, and the request itself. */
    record Chunk(List<NamingCandidate> candidates, List<String> rowKeys, ModelRequest request) {
    }

    /** The parts to send, in document order, and the places that fit in none of them. */
    record Plan(List<Chunk> chunks, List<NamingCandidate> leftOver) {
    }

    static Plan plan(SpotNamingInput input, int maxChunks, int maxInputTokens, int maxCandidates) {
        List<Unit> units = units(input);
        List<Chunk> chunks = new ArrayList<>();
        List<NamingCandidate> leftOver = new ArrayList<>();
        int start = 0;
        while (start < units.size()) {
            if (chunks.size() == maxChunks) {
                units.subList(start, units.size()).forEach(unit -> leftOver.addAll(unit.candidates()));
                break;
            }
            Packing packing = new Packing(input, units, start, maxInputTokens, maxCandidates);
            int taken = packing.largestFittingCount();
            if (taken == 0) {
                Unit unit = units.get(start);
                if (unit.lines().size() > 1) {
                    units.remove(start);
                    units.addAll(start, unit.oneLineEach(input));
                } else {
                    leftOver.addAll(unit.candidates());
                    start++;
                }
                continue;
            }
            Chunk chunk = packing.chunk(taken);
            if (chunk != null) {
                chunks.add(chunk);
            }
            start += taken;
        }
        return new Plan(chunks, leftOver);
    }

    /** The outline's units in order, then one unit for each place no line marks. */
    private static List<Unit> units(SpotNamingInput input) {
        Map<String, NamingCandidate> byId = new LinkedHashMap<>();
        input.candidates().forEach(candidate -> byId.put(candidate.id(), candidate));
        Set<String> placed = new HashSet<>();
        List<OutlineLine> outline = input.outline();
        List<List<NamingCandidate>> marked = new ArrayList<>(outline.size());
        for (OutlineLine line : outline) {
            List<NamingCandidate> onLine = new ArrayList<>();
            Matcher matcher = MARKER.matcher(line.text());
            while (matcher.find()) {
                NamingCandidate candidate = byId.get(matcher.group(1));
                if (candidate != null && placed.add(candidate.id())) {
                    onLine.add(candidate);
                }
            }
            marked.add(onLine);
        }

        List<Unit> units = new ArrayList<>();
        int i = 0;
        while (i < outline.size()) {
            String table = tableOf(outline.get(i));
            if (table == null) {
                units.add(new Unit(List.of(i), marked.get(i)));
                i++;
                continue;
            }
            int end = i;
            while (end < outline.size() && table.equals(tableOf(outline.get(end)))) {
                end++;
            }
            List<Integer> lines = new ArrayList<>();
            List<NamingCandidate> candidates = new ArrayList<>();
            Unit before = units.isEmpty() ? null : units.getLast();
            if (before != null && before.lines().size() == 1 && before.lines().getFirst() == i - 1) {
                units.removeLast();
                lines.add(i - 1);
                candidates.addAll(before.candidates());
            }
            for (int line = i; line < end; line++) {
                lines.add(line);
                candidates.addAll(marked.get(line));
            }
            units.add(new Unit(lines, candidates));
            i = end;
        }
        for (NamingCandidate candidate : input.candidates()) {
            if (!placed.contains(candidate.id())) {
                units.add(new Unit(List.of(), List.of(candidate)));
            }
        }
        return units;
    }

    private static String tableOf(OutlineLine line) {
        Matcher matcher = TABLE_KEY.matcher(line.key());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Some consecutive outline lines (possibly none) and the places they mark. */
    private record Unit(List<Integer> lines, List<NamingCandidate> candidates) {

        List<Unit> oneLineEach(SpotNamingInput input) {
            Set<String> mine = new HashSet<>();
            candidates.forEach(candidate -> mine.add(candidate.id()));
            Set<String> placed = new HashSet<>();
            List<Unit> units = new ArrayList<>();
            for (int line : lines) {
                List<NamingCandidate> onLine = new ArrayList<>();
                Matcher matcher = MARKER.matcher(input.outline().get(line).text());
                while (matcher.find()) {
                    String id = matcher.group(1);
                    if (mine.contains(id) && placed.add(id)) {
                        candidates.stream().filter(candidate -> candidate.id().equals(id)).findFirst().ifPresent(onLine::add);
                    }
                }
                units.add(new Unit(List.of(line), onLine));
            }
            return units;
        }
    }

    /**
     * Finds how many units, from {@code start}, fit in one request. Adding
     * a unit never makes a request smaller, so the answer is found by
     * doubling and then halving, building only a handful of requests
     * rather than one per unit.
     */
    private static final class Packing {

        private final SpotNamingInput input;
        private final List<Unit> units;
        private final int start;
        private final int maxInputTokens;
        private final int maxCandidates;

        Packing(SpotNamingInput input, List<Unit> units, int start, int maxInputTokens, int maxCandidates) {
            this.input = input;
            this.units = units;
            this.start = start;
            this.maxInputTokens = maxInputTokens;
            this.maxCandidates = maxCandidates;
        }

        int largestFittingCount() {
            int available = units.size() - start;
            int fits = 0;
            int probe = 1;
            while (probe <= available && fits(probe)) {
                fits = probe;
                probe *= 2;
            }
            int doesNotFit = Math.min(probe, available + 1);
            while (doesNotFit - fits > 1) {
                int middle = (fits + doesNotFit) >>> 1;
                if (fits(middle)) {
                    fits = middle;
                } else {
                    doesNotFit = middle;
                }
            }
            return fits;
        }

        private boolean fits(int count) {
            List<NamingCandidate> candidates = candidates(count);
            if (candidates.size() > maxCandidates) {
                return false;
            }
            // Lines with no place yet cost nothing to carry: the first place that joins them decides what is shown.
            return candidates.isEmpty() || UsageBudget.estimateInputTokens(request(count, candidates)) <= maxInputTokens;
        }

        /** The request for the first {@code count} units, or null when they hold no place and there is nothing to ask. */
        Chunk chunk(int count) {
            List<NamingCandidate> candidates = candidates(count);
            if (candidates.isEmpty()) {
                return null;
            }
            return new Chunk(candidates, rowKeys(candidates), request(count, candidates));
        }

        private List<NamingCandidate> candidates(int count) {
            List<NamingCandidate> candidates = new ArrayList<>();
            units.subList(start, start + count).forEach(unit -> candidates.addAll(unit.candidates()));
            return candidates;
        }

        private ModelRequest request(int count, List<NamingCandidate> candidates) {
            List<OutlineLine> lines = new ArrayList<>();
            units.subList(start, start + count).forEach(unit -> unit.lines().forEach(line -> lines.add(input.outline().get(line))));
            return FillSpotPromptBuilder.build(input.kind(), lines, candidates, rowKeys(candidates));
        }

        /** The offered rows a place in this part sits in, in the order they were offered: a part can only choose a row it shows. */
        private List<String> rowKeys(List<NamingCandidate> candidates) {
            Set<String> carried = new HashSet<>();
            candidates.forEach(candidate -> {
                if (candidate.rowKey() != null) {
                    carried.add(candidate.rowKey());
                }
            });
            return input.offeredRowKeys().stream().filter(carried::contains).distinct().toList();
        }
    }
}
