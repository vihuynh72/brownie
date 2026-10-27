package io.github.vihuynh72.brownie.api.mcp;

/** The attached file's URL is one Brownie will not ask for. */
final class FormFileDownloadRefusal extends RuntimeException {
    FormFileDownloadRefusal(String message) {
        super(message);
    }
}
