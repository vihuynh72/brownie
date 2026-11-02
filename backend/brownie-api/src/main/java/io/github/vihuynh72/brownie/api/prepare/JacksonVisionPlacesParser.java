package io.github.vihuynh72.brownie.api.prepare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.prepare.VisionPlacesParser;
import io.github.vihuynh72.brownie.core.prepare.VisionSpotFinder;
import io.github.vihuynh72.brownie.core.template.FieldIds;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Jackson's half of {@link VisionPlacesParser}: the model's places JSON, as found boxes. */
public final class JacksonVisionPlacesParser implements VisionPlacesParser {

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public List<VisionSpotFinder.FoundBox> parse(int pageNumber, String content) {
        List<VisionSpotFinder.FoundBox> boxes = new ArrayList<>();
        JsonNode places;
        try {
            places = json.readTree(content).path("places");
        } catch (IOException e) {
            return List.of();
        }
        if (!places.isArray()) {
            return List.of();
        }
        for (JsonNode place : places) {
            String label = FieldIds.normalizeLabel(place.path("label").asText(""));
            String type = place.path("type").asText("TEXT");
            JsonNode box = place.path("box");
            if (label == null || !box.isArray() || box.size() != 4) {
                continue;
            }
            boxes.add(new VisionSpotFinder.FoundBox(label, type,
                    box.get(0).asDouble() / 1000.0, box.get(1).asDouble() / 1000.0,
                    box.get(2).asDouble() / 1000.0, box.get(3).asDouble() / 1000.0, pageNumber));
        }
        return boxes;
    }
}
