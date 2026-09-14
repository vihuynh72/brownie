package io.github.vihuynh72.brownie.core.compile;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.BaselineRenderResult;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderer;
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
 */
public class TemplateQualificationService implements TemplateBaselineRenderer {

    /** A fixed, deterministic sample date -- never the current date, so a baseline render is exactly reproducible regardless of when it runs. */
    private static final LocalDate SAMPLE_DATE = LocalDate.of(2020, 1, 1);
    private static final int SAMPLE_REPEATED_ITEM_COUNT = 2;

    private final ArtifactService artifactService;
    private final TemplateFiller templateFiller;
    private final DocumentRenderer documentRenderer;

    public TemplateQualificationService(ArtifactService artifactService, TemplateFiller templateFiller, DocumentRenderer documentRenderer) {
        this.artifactService = artifactService;
        this.templateFiller = templateFiller;
        this.documentRenderer = documentRenderer;
    }

    @Override
    public BaselineRenderResult renderBaseline(long workspaceId, long userId, TemplateVersion draftVersion) {
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
