package io.github.vihuynh72.brownie.api.detail;

import java.util.List;

/** The person's saved details plus whether this Brownie can seal sensitive ones. */
public record DetailsResponse(List<SavedDetailResponse> details, boolean sealingAvailable) {
}
