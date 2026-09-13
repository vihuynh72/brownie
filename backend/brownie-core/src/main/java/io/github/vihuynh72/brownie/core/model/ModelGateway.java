package io.github.vihuynh72.brownie.core.model;

/**
 * The narrow contract a model-calling adapter must satisfy. Nothing here
 * knows about meeting minutes, templates, evidence, or generation runs --
 * it sends an ordered list of messages against one fixed, centrally
 * configured model and reports what came back, the same dependency-
 * inversion shape {@code MalwareScanner} and {@code BlobStore} already
 * establish for this codebase's other external-provider boundaries. A
 * real implementation must never execute a provider-side tool, never send
 * this request to more than one model, and never silently retry against a
 * different provider after a failure -- those guarantees belong to the
 * adapter itself, not to whoever calls this interface.
 */
public interface ModelGateway {

    ModelCompletion complete(ModelRequest request) throws ModelTransportException;
}
