package io.github.vihuynh72.brownie.core.template;

/**
 * Fills a draft's own source DOCX with synthetic sample content matching
 * its current field definitions and renders it, independently verifying
 * the result -- the "capability baseline" {@link TemplateService#activate}
 * requires before an immutable version can activate. A real implementation
 * needs the fill/render machinery {@code core.compile} already owns
 * ({@code TemplateFiller}, {@code DocumentRenderer}, and the same
 * content-integrity check {@code CompilationService} uses for a real
 * document); this interface carries none of that itself, so {@code
 * core.template} depends only on this narrow contract, the same
 * dependency-inversion shape {@code DocxStructuralExtractor} already
 * establishes for extraction.
 */
public interface TemplateBaselineRenderer {

    BaselineRenderResult renderBaseline(long workspaceId, long userId, TemplateVersion draftVersion);
}
