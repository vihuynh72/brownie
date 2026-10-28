package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;

/** The JSON the vision model answers, as the places it found. */
public interface VisionPlacesParser {

    /** The places the model answered with, or an empty list when the answer was not the places list. */
    List<VisionSpotFinder.FoundBox> parse(int pageNumber, String content);
}
