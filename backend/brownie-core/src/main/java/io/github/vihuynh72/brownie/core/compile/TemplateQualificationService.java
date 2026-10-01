package io.github.vihuynh72.brownie.core.compile;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.BaselineRenderResult;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderer;
import io.github.vihuynh72.brownie.core.template.TemplateKind;
import io.github.vihuynh72.brownie.core.template.TemplateBindingValidator;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves a template draft actually fills and renders before it can
 * activate, by running the exact same fill-render-verify pipeline {@link
 * CompilationService} uses for a real document, against synthetic sample
 * content instead of a real revision's own typed fields. Lives in this
 * package specifically to reuse {@link IntegrityChecker}, which is
 * deliberately package-private -- the same real content-integrity check a
 * real export runs, not a second, separately-maintained one for this
 * qualification path.
 *
 * <p>Implements {@code core.template}'s own {@link TemplateBaselineRenderer}
 * interface, the same dependency-inversion shape {@code
 * PoiDocxStructuralExtractor} already uses for {@code
 * DocxStructuralExtractor} -- {@code core.template} depends only on that
 * narrow interface, never on this class or this package.
 *
 * <p>A PDF template's baseline is a sample fill with the PDF filler and its
 * check, the same pair a real PDF compilation runs, and never touches the
 * Word renderer: the filled sample PDF is the baseline, with no Word file.
 * Each sample is one short word, cut to the form field's own length limit
 * where it has one, and a date is the longest a real date is printed in
 * ("September 30, 2020", as long as any date written out gets), so a
 * place too small for a real date fails here rather than at the first real
 * document.
 */
public class TemplateQualificationService implements TemplateBaselineRenderer {

    /** A fixed, deterministic sample date -- never the current date, so a baseline render is exactly reproducible regardless of when it runs. */
    private static final LocalDate SAMPLE_DATE = LocalDate.of(2020, 1, 1);
    private static final int SAMPLE_REPEATED_ITEM_COUNT = 2;
    private static final String PDF_SAMPLE_TEXT = "Sample";
    /** The date written out longest: the longest month's name and a two-figure day. */
    static final LocalDate PDF_SAMPLE_DATE = LocalDate.of(2020, 9, 30);

    private final ArtifactService artifactService;
    private final TemplateFiller templateFiller;
    private final DocumentRenderer documentRenderer;
    private final PdfTemplateFill pdfTemplateFill;
    private final PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository;

    public TemplateQualificationService(
            ArtifactService artifactService,
            TemplateFiller templateFiller,
            DocumentRenderer documentRenderer,
            PdfTemplateFill pdfTemplateFill,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository) {
        this.artifactService = artifactService;
        this.templateFiller = templateFiller;
        this.documentRenderer = documentRenderer;
        this.pdfTemplateFill = pdfTemplateFill;
        this.pdfFormExtractionVersionRepository = pdfFormExtractionVersionRepository;
    }

    @Override
    public BaselineRenderResult renderBaseline(long workspaceId, long userId, TemplateVersion draftVersion) {
        if (draftVersion.kind() == TemplateKind.PDF) {
            return renderPdfBaseline(workspaceId, userId, draftVersion);
        }
        byte[] templateBytes = readTemplateBytes(workspaceId, userId, draftVersion.sourceArtifactId());
        DocumentContent sample = sampleContent(draftVersion.fieldDefinitions());
        FilledDocument filled = templateFiller.fill(templateBytes, draftVersion.fieldDefinitions(), sample);
        Artifact docxArtifact = storeGenerated(
                workspaceId, userId, "baseline-" + draftVersion.templateId() + "-v" + draftVersion.versionNumber() + ".docx",
                filled.docxBytes());

        RenderedPdf rendered = documentRenderer.renderToPdf(filled.docxBytes());
        Artifact pdfArtifact = storeGenerated(
                workspaceId, userId, "baseline-" + draftVersion.templateId() + "-v" + draftVersion.versionNumber() + ".pdf",
                rendered.pdfBytes());

        List<String> failedFieldIds = IntegrityChecker
                .check(filled.intendedText(), filled.reopenedBodyText(), rendered.extractedText())
                .stream()
                .filter(finding -> !finding.passed())
                .map(IntegrityFinding::fieldId)
                .distinct()
                .toList();

        return new BaselineRenderResult(docxArtifact.id(), pdfArtifact.id(), rendered.rendererVersion(), failedFieldIds);
    }

    private BaselineRenderResult renderPdfBaseline(long workspaceId, long userId, TemplateVersion draftVersion) {
        byte[] sourceBytes = readTemplateBytes(workspaceId, userId, draftVersion.sourceArtifactId());
        PdfFormGraph graph = pdfFormExtractionVersionRepository
                .findById(workspaceId, userId, draftVersion.pdfFormExtractionId())
                .map(PdfFormExtractionVersion::graph)
                .orElseThrow(() -> new IllegalStateException(
                        "PDF form reading " + draftVersion.pdfFormExtractionId() + " of template version " + draftVersion.id() + " no longer exists."));
        PdfTemplateFill.Result result = pdfTemplateFill.fill(
                sourceBytes, draftVersion.fieldDefinitions(), pdfSampleContent(draftVersion.fieldDefinitions(), graph));
        Artifact pdfArtifact = storeGenerated(
                workspaceId, userId, "baseline-" + draftVersion.templateId() + "-v" + draftVersion.versionNumber() + ".pdf",
                result.filled().bytes());
        return new BaselineRenderResult(null, pdfArtifact.id(), pdfTemplateFill.fillerVersion(), result.failedFieldIds());
    }

    /** One short sample per field of a PDF template: see this class's javadoc. A list is left as it is for the fill to refuse. */
    static DocumentContent pdfSampleContent(List<FieldDefinition> fieldDefinitions, PdfFormGraph graph) {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        for (FieldDefinition field : fieldDefinitions) {
            FieldValue value = sampleValue(field);
            if (value instanceof FieldValue.TextValue && field.binding() instanceof FieldBindingTarget.AcroFormField(String name)) {
                Integer maxLen = TemplateBindingValidator.formField(graph, name).map(PdfFormGraph.Field::maxLen).orElse(null);
                value = new FieldValue.TextValue(maxLen != null && maxLen > 0 && maxLen < PDF_SAMPLE_TEXT.length()
                        ? PDF_SAMPLE_TEXT.substring(0, maxLen)
                        : PDF_SAMPLE_TEXT);
            } else if (value instanceof FieldValue.TextValue) {
                value = new FieldValue.TextValue(PDF_SAMPLE_TEXT);
            } else if (value instanceof FieldValue.DateValue) {
                value = new FieldValue.DateValue(PDF_SAMPLE_DATE);
            }
            fields.put(field.fieldId(), value);
        }
        return new DocumentContent(fields);
    }

    /**
     * One representative sample per field, typed but deliberately generic
     * -- this is a capability proof, not a claim about what a real document
     * would say. A {@link FieldCardinality#REPEATED} field gets two sample
     * items, matching {@code PoiTemplateFiller}'s own requirement that
     * every repeated field in the same group supply the same item count.
     */
    private static DocumentContent sampleContent(List<FieldDefinition> fieldDefinitions) {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        for (FieldDefinition field : fieldDefinitions) {
            fields.put(field.fieldId(), sampleValue(field));
        }
        return new DocumentContent(fields);
    }

    private static FieldValue sampleValue(FieldDefinition field) {
        return switch (field.cardinality()) {
            case SCALAR -> switch (field.type()) {
                case TEXT -> new FieldValue.TextValue("Sample value for " + field.fieldId());
                case DATE -> new FieldValue.DateValue(SAMPLE_DATE);
            };
            case REPEATED -> switch (field.type()) {
                case TEXT -> new FieldValue.RepeatedTextValue(sampleTextItems(field.fieldId()));
                case DATE -> new FieldValue.RepeatedDateValue(sampleDateItems());
            };
        };
    }

    private static List<String> sampleTextItems(String fieldId) {
        return java.util.stream.IntStream.rangeClosed(1, SAMPLE_REPEATED_ITEM_COUNT)
                .mapToObj(index -> "Sample value " + index + " for " + fieldId)
                .toList();
    }

    private static List<LocalDate> sampleDateItems() {
        return java.util.stream.IntStream.range(0, SAMPLE_REPEATED_ITEM_COUNT).mapToObj(SAMPLE_DATE::plusDays).toList();
    }

    private byte[] readTemplateBytes(long workspaceId, long userId, long artifactId) {
        ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId);
        try (readable) {
            return readable.content().readAllBytes();
        } catch (IOException e) {
            throw new TemplateFillException(
                    TemplateFillProblemReason.UNREADABLE_TEMPLATE, "Failed to read template source artifact " + artifactId + " for baseline render.", e);
        }
    }

    /** Mirrors {@code CompilationService}'s own identical helper: a baseline artifact is scanned and gated exactly like any other generated one. */
    private Artifact storeGenerated(long workspaceId, long userId, String filename, byte[] bytes) {
        Artifact allocated = artifactService.initiateUpload(workspaceId, userId, filename);
        try (InputStream content = new ByteArrayInputStream(bytes)) {
            artifactService.receiveContent(workspaceId, userId, allocated.id(), content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stage generated baseline artifact bytes in memory.", e);
        }
        Artifact finalized = artifactService.finalizeUpload(workspaceId, userId, allocated.id());
        if (finalized.status() != ArtifactStatus.READY) {
            throw new GeneratedArtifactUnavailableException(
                    "Generated baseline artifact " + finalized.id() + " did not reach READY (status " + finalized.status() + ").");
        }
        return finalized;
    }
}
