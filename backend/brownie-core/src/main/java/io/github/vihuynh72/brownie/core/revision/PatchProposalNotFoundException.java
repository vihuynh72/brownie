package io.github.vihuynh72.brownie.core.revision;

/** No proposal by that ID is visible in the caller's workspace -- whether it never existed, belongs to someone else, or was already accepted. */
public class PatchProposalNotFoundException extends RuntimeException {

    public PatchProposalNotFoundException(long proposalId) {
        super("No patch proposal " + proposalId + " in this workspace.");
    }
}
