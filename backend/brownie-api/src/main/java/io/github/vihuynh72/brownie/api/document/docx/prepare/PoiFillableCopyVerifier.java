package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.DocxNodeLocator;
import io.github.vihuynh72.brownie.api.document.docx.DocxNodeWalker;
import io.github.vihuynh72.brownie.core.compile.FilledDocument;
import io.github.vihuynh72.brownie.core.compile.TemplateFillException;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.prepare.FillableCopyCheck;
import io.github.vihuynh72.brownie.core.prepare.FillableCopyVerifier;
import io.github.vihuynh72.brownie.core.prepare.WorkingCopyPreparationException;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.CandidateBindingReport;
import io.github.vihuynh72.brownie.core.template.CandidateFieldBinding;
import io.github.vihuynh72.brownie.core.template.FieldBindingCandidateProposer;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.TemplateLayout;
import io.github.vihuynh72.brownie.core.template.TemplateLayoutProjector;
import io.github.vihuynh72.brownie.core.template.TemplateLayoutUnavailableException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link FillableCopyVerifier} without a render, in the order the checks
 * are cheapest to fail: nothing active is left in the package; the copy
 * reads as a supported document; its text is what it was before the spots
 * were made (spaces aside, since a spot after a colon brings one);
 * {@link FieldBindingCandidateProposer} finds each field's tag exactly
 * once; {@link TemplateLayoutProjector} places every field; and the filler
 * writes a sample value into every spot. A fill that fails only because of
 * the repeating row is reported as such, so the row can be filled once
 * instead.
 */
public final class PoiFillableCopyVerifier implements FillableCopyVerifier {

    private static final LocalDate SAMPLE_DATE = LocalDate.of(2026, 1, 2);

    private final DocxStructuralExtractor extractor;
    private final TemplateFiller filler;

    public PoiFillableCopyVerifier(DocxStructuralExtractor extractor, TemplateFiller filler) {
        this.extractor = extractor;
        this.filler = filler;
    }

    @Override
    public FillableCopyCheck check(byte[] before, byte[] after, List<FieldDefinition> fields) {
        List<String> problems = new ArrayList<>();
        try {
            PoiWorkingCopyPreparer.requireNoMacroOrSignatureParts(after);
        } catch (WorkingCopyPreparationException e) {
            return copyFailed(problems, "active content: " + e.getMessage());
        }
        DocxStructuralGraph graph;
        try {
            DocxExtractionOutcome outcome = extractor.extract(new ByteArrayInputStream(after));
            if (!(outcome instanceof DocxExtractionOutcome.Supported supported)) {
                return copyFailed(problems, "the copy is not a supported document");
            }
            graph = supported.graph();
        } catch (IOException | DocxParseException e) {
            return copyFailed(problems, "the copy does not read: " + e.getMessage());
        }
        if (!withoutSpaces(visibleText(before)).equals(withoutSpaces(visibleText(after)))) {
            return copyFailed(problems, "the copy's text changed");
        }

        Set<String> failed = new LinkedHashSet<>();
        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);
        Set<String> foundOnce = new HashSet<>();
        for (CandidateFieldBinding candidate : report.candidates()) {
            foundOnce.add(candidate.fieldId());
        }
        for (FieldDefinition field : fields) {
            String tag = tagOf(field);
            if (tag == null || !foundOnce.contains(tag)) {
                failed.add(field.fieldId());
                problems.add(field.fieldId() + ": its tag is not found exactly once where the filler writes");
            }
        }
        try {
            TemplateLayout layout = TemplateLayoutProjector.project(0, 0, graph, fields);
            for (String fieldId : layout.unplacedFieldIds()) {
                if (failed.add(fieldId)) {
                    problems.add(fieldId + ": the page has no place for it");
                }
            }
        } catch (TemplateLayoutUnavailableException e) {
            return copyFailed(problems, "the page cannot be drawn: " + e.getMessage());
        }
        if (!failed.isEmpty()) {
            return new FillableCopyCheck(false, failed, false, problems);
        }
        return sampleFill(after, fields, problems);
    }

    /** Fills every spot with a value of its own and looks for each text value in the result. */
    private FillableCopyCheck sampleFill(byte[] after, List<FieldDefinition> fields, List<String> problems) {
        try {
            Set<String> failed = missingValues(after, fields);
            failed.forEach(fieldId -> problems.add(fieldId + ": a sample value did not appear"));
            return new FillableCopyCheck(false, failed, false, problems);
        } catch (TemplateFillException e) {
            boolean repeats = fields.stream().anyMatch(field -> field.cardinality() == FieldCardinality.REPEATED);
            if (repeats) {
                try {
                    List<FieldDefinition> single = fields.stream().map(PoiFillableCopyVerifier::scalar).toList();
                    if (missingValues(after, single).isEmpty()) {
                        problems.add("the repeating row does not fill: " + e.getMessage());
                        return new FillableCopyCheck(false, Set.of(), true, problems);
                    }
                } catch (TemplateFillException ignored) {
                    // The copy fails as single values too, which is reported below.
                }
            }
            return copyFailed(problems, "a sample fill failed: " + e.getMessage());
        }
    }

    private Set<String> missingValues(byte[] after, List<FieldDefinition> fields) {
        Map<String, FieldValue> values = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        int n = 0;
        for (FieldDefinition field : fields) {
            n++;
            String sample = "Sample value " + n + " for the check";
            boolean date = field.type() == FieldType.DATE;
            if (field.cardinality() == FieldCardinality.REPEATED) {
                values.put(field.fieldId(), date
                        ? new FieldValue.RepeatedDateValue(List.of(SAMPLE_DATE, SAMPLE_DATE))
                        : new FieldValue.RepeatedTextValue(List.of(sample + " a", sample + " b")));
            } else {
                values.put(field.fieldId(), date ? new FieldValue.DateValue(SAMPLE_DATE) : new FieldValue.TextValue(sample));
            }
            if (!date) {
                expected.put(field.fieldId(), field.cardinality() == FieldCardinality.REPEATED ? sample + " b" : sample);
            }
        }
        FilledDocument filled = filler.fill(after, fields, new DocumentContent(values));
        String text = visibleText(filled.docxBytes());
        Set<String> missing = new LinkedHashSet<>();
        expected.forEach((fieldId, sample) -> {
            if (!text.contains(sample)) {
                missing.add(fieldId);
            }
        });
        return missing;
    }

    /** Every paragraph's text, controls' text included, in every part the graph reads. */
    static String visibleText(byte[] docx) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder text = new StringBuilder();
            for (DocxNodeWalker.Part part : DocxNodeWalker.walk(document)) {
                for (DocxNodeWalker.Block block : part.blocks()) {
                    switch (block) {
                        case DocxNodeWalker.Paragraph paragraph -> append(text, paragraph);
                        case DocxNodeWalker.Table table -> table.rows().forEach(row -> row.cells().forEach(cell ->
                                cell.paragraphs().forEach(paragraph -> append(text, paragraph))));
                        case DocxNodeWalker.OtherBlock ignored -> {
                            // Its content has no ids and is the same before and after.
                        }
                    }
                }
            }
            return text.toString();
        } catch (IOException | RuntimeException e) {
            throw new DocxParseException("A copy being checked could not be read.", e);
        }
    }

    private static void append(StringBuilder text, DocxNodeWalker.Paragraph paragraph) {
        for (DocxNodeWalker.Inline inline : paragraph.inlines()) {
            switch (inline) {
                case DocxNodeWalker.Run run -> text.append(DocxNodeLocator.shownText(run.run()));
                case DocxNodeWalker.Control control -> control.runs().forEach(run -> text.append(DocxNodeLocator.shownText(run.run())));
            }
        }
        text.append('\n');
    }

    private static String withoutSpaces(String text) {
        return text.replace(" ", "");
    }

    private static String tagOf(FieldDefinition field) {
        return field.binding() instanceof FieldBindingTarget.ContentControlTag(String tag) ? tag : null;
    }

    private static FieldDefinition scalar(FieldDefinition field) {
        return new FieldDefinition(field.fieldId(), field.type(), FieldCardinality.SCALAR, field.requiredness(), field.binding(),
                field.label(), field.origin(), field.docxControl(), field.blankText());
    }

    private static FillableCopyCheck copyFailed(List<String> problems, String problem) {
        problems.add(problem);
        return new FillableCopyCheck(true, Set.of(), false, problems);
    }
}
