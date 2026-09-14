package io.github.vihuynh72.brownie.core.template;

import java.util.List;

/**
 * What actually happened when a draft's own field definitions were filled
 * with synthetic sample content and rendered -- "create a sample document
 * with synthetic content, render it, inspect the capability report"
 * proven, not asserted. {@code failedFieldIds} names every field whose own
 * intended sample text did not survive into the reopened DOCX or the
 * rendered PDF, the same content-integrity check {@code CompilationService}
 * already runs for a real document; empty means every field passed.
 * {@code docxArtifactId}/{@code pdfArtifactId} are the actual generated
 * baseline files, stored the same authorized way any other generated
 * artifact is, so a person can open and inspect exactly what was rendered.
 */
public record BaselineRenderResult(long docxArtifactId, long pdfArtifactId, String rendererVersion, List<String> failedFieldIds) {

    public BaselineRenderResult {
        failedFieldIds = List.copyOf(failedFieldIds);
    }

    public boolean passed() {
        return failedFieldIds.isEmpty();
    }
}
